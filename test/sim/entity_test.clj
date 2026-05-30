(ns sim.entity-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs     :as defs]
   [sim.entity   :as entity]
   [sim.schedule :as schedule]))

;; make-* now reads thing-defs from the shared global registry; reload the
;; bundled defs before each test so a sibling ns swapping in alternate sources
;; can't leave the registry partial. Mirrors sim.defs-test's fixture.
(use-fixtures :each (fn [t] (defs/load!) (t)))

(deftest make-tree-shape
  (testing "a tree is a :tree kind entity at a position"
    (let [t (entity/make-tree [4 5])]
      (is (= :tree (:kind t)))
      (is (= [4 5] (:pos t)))
      (is (some? (:id t))))))

(deftest trees-query-filters-by-kind
  (testing "trees returns only :tree entities"
    (let [w (-> {:entities {}}
                (entity/add-entity (entity/make-pawn "P" [0 0]))
                (entity/add-entity (entity/make-tree [1 1]))
                (entity/add-entity (entity/make-tree [2 2])))]
      (is (= 2 (count (entity/trees w))))
      (is (every? #(= :tree (:kind %)) (entity/trees w))))))

(deftest ticker-type-defaults
  (is (= :never (:ticker-type (entity/make-pawn "P" [0 0]))))
  (is (= :long  (:ticker-type (entity/make-item :stone [0 0]))))
  (is (= :never (:ticker-type (entity/make-tree [0 0])))))

(deftest add-entity-maintains-schedule-index
  (testing "adding a :long item registers it in its long home bucket"
    (let [item (entity/make-item :wood [3 3])
          w    (-> {:entities {} :schedule (schedule/empty-index)}
                   (entity/add-entity item))]
      (is (contains? (get-in w [:schedule :long (schedule/home-bucket (:id item) 1000)])
                     (:id item)))))
  (testing "removing it clears the bucket"
    (let [item (entity/make-item :wood [3 3])
          w    (-> {:entities {} :schedule (schedule/empty-index)}
                   (entity/add-entity item))
          w'   (entity/remove-entity w (:id item))]
      (is (empty? (get-in w' [:schedule :long (schedule/home-bucket (:id item) 1000)]))))))

(deftest make-thing-stamps-def-backref-and-template
  (testing "a constructed pawn carries its :def back-ref and the template content"
    (let [p (entity/make-thing :colonist [3 4])]
      (is (= :colonist (:def p)))
      (is (= :pawn (:kind p)))
      (is (= :never (:ticker-type p)))
      (is (= 15 (:move-ticks p)))
      (is (= {:food 1.0 :rest 1.0 :recreation 1.0} (:needs p)))
      (is (= [3 4] (:pos p)))
      (is (some? (:id p)))))
  (testing "an item thing copies its material from the def"
    (let [w (entity/make-thing :wood [1 1])]
      (is (= :item (:kind w)))
      (is (= :long (:ticker-type w)))
      (is (= :wood (:material w)))
      (is (= :wood (:def w))))))

(deftest make-thing-throws-on-unknown-def
  (testing "constructing an undefined type fails fast (no silent fallback)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown thing-def"
                          (entity/make-thing :no-such-type [0 0])))))

(deftest wrappers-preserve-entity-shape
  (testing "make-pawn yields a named pawn with runtime scaffolding"
    (let [p (entity/make-pawn "Riker" [2 2])]
      (is (= :pawn (:kind p)))
      (is (= "Riker" (:name p)))
      (is (= :colonist (:def p)))
      (is (contains? p :job))
      (is (contains? p :carrying))))
  (testing "make-item yields an item with :carried-by scaffolding"
    (is (contains? (entity/make-item :stone [0 0]) :carried-by))))
