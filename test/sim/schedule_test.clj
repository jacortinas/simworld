(ns sim.schedule-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.schedule :as schedule]))

(deftest bands-shape
  (is (= 1 (:normal schedule/bands)))
  (is (= 125 (:rare schedule/bands)))
  (is (= 1000 (:long schedule/bands))))

(deftest bucket-math
  (testing "home-bucket is id mod interval"
    (is (= 7 (schedule/home-bucket 7 125)))
    (is (= 5 (schedule/home-bucket 130 125))))
  (testing "due-bucket is clock mod interval"
    (is (= 0 (schedule/due-bucket 0 125)))
    (is (= 4 (schedule/due-bucket 129 125))))
  (testing "due? is true exactly when home-bucket == due-bucket"
    (is (true?  (schedule/due? 7 125 7)))     ; clock 7, id 7 -> both bucket 7
    (is (true?  (schedule/due? 132 125 7)))   ; clock 132 -> bucket 7
    (is (false? (schedule/due? 8 125 7)))))   ; clock 8 -> bucket 8, id bucket 7

(defn- item [id]  {:id id :kind :item :ticker-type :long})
(defn- rare [id]  {:id id :kind :test :ticker-type :rare})
(defn- never [id] {:id id :kind :pawn :ticker-type :never})

(deftest empty-index-shape
  (let [idx (schedule/empty-index)]
    (is (= 125 (count (:rare idx))))
    (is (= 1000 (count (:long idx))))
    (is (every? #(= #{} %) (:rare idx)))
    (is (nil? (:normal idx)) "normal band is unbucketed")))

(deftest register-places-in-home-bucket
  (let [w {:schedule (schedule/empty-index)}]
    (testing ":long entity lands in its long home bucket"
      (let [w' (schedule/register w (item 130))]
        (is (contains? (get-in w' [:schedule :long 130]) 130))))
    (testing ":rare entity lands in its rare home bucket (id mod 125)"
      (let [w' (schedule/register w (rare 130))]
        (is (contains? (get-in w' [:schedule :rare 5]) 130))))
    (testing ":never entity is not indexed"
      (is (= w (schedule/register w (never 7)))))))

(deftest unregister-removes
  (let [w (-> {:schedule (schedule/empty-index)}
              (schedule/register (item 130)))]
    (is (empty? (get-in (schedule/unregister w (item 130))
                        [:schedule :long 130])))))

(deftest register-no-op-without-schedule
  (testing "worlds lacking :schedule are untouched (backward compatible)"
    (let [w {:entities {}}]
      (is (= w (schedule/register w (item 5)))))))

(deftest reindex-rebuilds-and-is-idempotent
  ;; entities keyed by their own :id (register indexes by :id, not map key)
  (let [w  {:entities {130 (item 130) 7 (rare 7) 9 (never 9)}}
        w1 (schedule/reindex w)
        w2 (schedule/reindex w1)]
    (is (contains? (get-in w1 [:schedule :long 130]) 130))  ; item id 130 -> long bucket 130
    (is (contains? (get-in w1 [:schedule :rare 7]) 7))      ; rare id 7 -> rare bucket 7
    (is (= (:schedule w1) (:schedule w2)) "reindex is idempotent")))

(deftest run*-dispatch-order-and-bands
  (testing "normal systems run every tick with nil due; rare systems get the due bucket"
    (let [w0 (-> {:clock 0
                  :entities {1 (rare 1) 2 (rare 2)}}
                 schedule/reindex)
          sysmap {:normal [[:mark    (fn [w _due] (update w :trace (fnil conj []) :normal))]]
                  :rare   [[:collect (fn [w due]  (update w :seen (fnil into [])
                                                          (map :id) due))]]}
          ;; clock 1 -> rare bucket 1 -> entity id 1 due
          w1 (schedule/run* (assoc w0 :clock 1) sysmap)]
      (is (= [:normal] (:trace w1)) "normal system ran once")
      (is (= [1] (:seen w1)) "only the due rare entity (id 1) was processed"))))

(deftest run*-rare-coverage-exactly-once
  (testing "over one full rare interval, every rare entity is processed exactly once"
    (let [ids (range 1 30)
          w0  (-> {:clock 0
                   :entities (into {} (map (fn [i] [i (rare i)])) ids)}
                  schedule/reindex)
          sysmap {:rare [[:collect (fn [w due]
                                     (update w :seen (fnil into []) (map :id) due))]]}
          swept (reduce (fn [w c] (schedule/run* (assoc w :clock c) sysmap))
                        w0
                        (range (:rare schedule/bands)))
          freq  (frequencies (:seen swept))]
      (is (= (set ids) (set (keys freq))) "every id processed at least once")
      (is (every? #(= 1 %) (vals freq)) "...and exactly once"))))

(deftest upsert-system-replaces-by-name-preserving-order
  ;; Pure test — does NOT touch the global registry (which sim.simulation
  ;; populates at load), so it can't poison other namespaces' tests.
  (let [f1 (fn [w _] (update w :log (fnil conj []) :a1))
        f2 (fn [w _] (update w :log (fnil conj []) :b))
        f3 (fn [w _] (update w :log (fnil conj []) :a2))
        v  (-> [] (schedule/upsert-system :a f1)
                  (schedule/upsert-system :b f2)
                  (schedule/upsert-system :a f3))]
    (is (= [:a :b] (mapv first v)) "names in order; :a kept its slot")
    (is (= [:a2 :b] (:log (reduce (fn [w [_ f]] (f w nil)) {} v)))
        "the replacement fn (a2) is used; order preserved")))

(deftest reindex-equals-incremental-after-strip
  (testing "stripping :schedule then reindex reproduces the incremental index"
    (let [incremental (-> {:entities {} :schedule (schedule/empty-index)}
                          (as-> w (reduce (fn [w e] (assoc-in w [:entities (:id e)] e))
                                          w [(item 130) (rare 7) (never 9)]))
                          (as-> w (reduce schedule/register w (vals (:entities w)))))
          stripped    (dissoc incremental :schedule)
          rebuilt     (schedule/reindex stripped)]
      (is (= (:schedule incremental) (:schedule rebuilt))))))
