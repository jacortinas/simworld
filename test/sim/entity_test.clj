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

(deftest make-thing-copies-the-graphic-ref
  (testing "a constructed pawn carries its thing-def's :graphic id"
    (is (= :colonist (:graphic (entity/make-pawn "Riker" [0 0])))))
  (testing "items copy their own graphic, independent of material"
    (is (= :apple (:graphic (entity/make-item :food [1 1]))))
    (is (= :rock  (:graphic (entity/make-item :stone [1 1])))))
  (testing "a tree carries the tree graphic"
    (is (= :tree (:graphic (entity/make-tree [2 2]))))))

;; ---------------------------------------------------------------------------
;; :kinds index — a DERIVED per-kind id index maintained at the
;; add-entity/remove-entity chokepoint (mirrors :schedule). pawns/items/trees
;; read it, so they yield ids in ascending order (a sorted-set per kind).
;; ---------------------------------------------------------------------------

(deftest empty-kinds-shape
  (let [k (entity/empty-kinds)]
    (is (= #{:pawn :item :tree} (set (keys k))) "the three known kinds")
    (is (every? empty? (vals k)) "each starts empty")
    (is (every? sorted? (vals k)) "each is a sorted set, so queries iterate ascending by id")))

(deftest add-entity-maintains-kinds-index
  (testing "add-entity inserts the id into the matching kind set; remove-entity clears it"
    (let [pawn (entity/make-pawn "P" [0 0])
          tree (entity/make-tree [1 1])
          w    (-> {:entities {} :kinds (entity/empty-kinds)}
                   (entity/add-entity pawn)
                   (entity/add-entity tree))]
      (is (contains? (get-in w [:kinds :pawn]) (:id pawn)))
      (is (contains? (get-in w [:kinds :tree]) (:id tree)))
      (is (not (contains? (get-in w [:kinds :item]) (:id pawn))) "wrong-kind sets untouched")
      (let [w' (entity/remove-entity w (:id pawn))]
        (is (not (contains? (get-in w' [:kinds :pawn]) (:id pawn))) "remove clears the index")))))

(deftest add-entity-indexes-without-preexisting-kinds
  (testing "add-entity fnil-creates the kind set on a world lacking :kinds"
    (let [pawn (entity/make-pawn "P" [0 0])
          w    (entity/add-entity {:entities {}} pawn)]
      (is (contains? (get-in w [:kinds :pawn]) (:id pawn))))))

(deftest kind-queries-return-ascending-by-id
  (testing "pawns iterate ascending by id regardless of insertion order"
    (let [lo  (entity/make-pawn "lo" [0 0])    ; constructed first -> lower id
          hi  (entity/make-pawn "hi" [1 1])    ; -> higher id
          w   (-> {:entities {} :kinds (entity/empty-kinds)}
                  (entity/add-entity hi)        ; insert the higher id FIRST
                  (entity/add-entity lo))
          ids (map :id (entity/pawns w))]
      (is (= [(:id lo) (:id hi)] ids) "lowest id first, even though added last"))))

(deftest reindex-kinds-rebuilds-equals-incremental-and-idempotent
  (testing "reindex-kinds rebuilds :kinds from :entities, == incremental, idempotent"
    (let [incremental (-> {:entities {} :kinds (entity/empty-kinds)}
                          (entity/add-entity (entity/make-pawn "P" [0 0]))
                          (entity/add-entity (entity/make-item :wood [1 1]))
                          (entity/add-entity (entity/make-tree [2 2])))
          rebuilt     (entity/reindex-kinds (dissoc incremental :kinds))]
      (is (= (:kinds incremental) (:kinds rebuilt)) "rebuild reproduces the incremental index")
      (is (= (:kinds rebuilt) (:kinds (entity/reindex-kinds rebuilt))) "idempotent"))))

(deftest kind-queries-read-only-the-index
  (testing "an entity in :entities but absent from :kinds is invisible until reindex"
    (let [pawn (entity/make-pawn "ghost" [0 0])
          w    {:entities {(:id pawn) pawn} :kinds (entity/empty-kinds)}]
      (is (empty? (entity/pawns w)) "the index is authoritative for kind queries")
      (is (= [(:id pawn)] (map :id (entity/pawns (entity/reindex-kinds w))))
          "reindex-kinds repairs the index"))))

(deftest make-building-is-a-built-wall
  (let [b (entity/make-building [2 3])]
    (is (= :building (:kind b)))
    (is (= :wall (:def b)))
    (is (true? (:blocks-path? b)) "blocks-path? copied from the def")
    (is (= :built (:state b)))
    (is (= :stone (:material b)))
    (is (= [2 3] (:pos b)))))

(deftest buildings-query-reads-the-kind-index
  (let [w (-> {:entities {} :kinds (entity/empty-kinds)}
              (entity/add-entity (entity/make-building [0 0]))
              (entity/add-entity (entity/make-building [1 0])))]
    (is (= 2 (count (entity/buildings w))))
    (is (every? #(= :building (:kind %)) (entity/buildings w)))))
