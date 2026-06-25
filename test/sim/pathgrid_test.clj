(ns sim.pathgrid-test
  "PathGrid: a per-cell traversal-cost double-array (INFINITY = blocked), built
   from terrain costs with blocking buildings stamped INFINITY. Memoized by
   [grid identity, building-set identity] in for-world."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs     :as defs]
   [sim.entity   :as entity]
   [sim.tile     :as tile]
   [sim.pathgrid :as pathgrid]))

;; make-blueprint reads thing-defs; reload the bundled defs so a sibling ns
;; swapping in alternate sources can't leave the registry partial.
(use-fixtures :each (fn [t] (defs/load!) (t)))

(deftest build-costs-mirror-terrain
  (let [pg (pathgrid/build (tile/make-grid 3 3) [])]   ; all grass, cost 1.0
    (is (= 1.0 (pathgrid/cost pg 0 0)))
    (is (pathgrid/passable? pg 1 1))))

(deftest impassable-terrain-is-infinite
  (let [g  (tile/set-tile (tile/make-grid 3 3) 2 2 :wall)] ; :wall terrain impassable
    (is (Double/isInfinite (pathgrid/cost (pathgrid/build g []) 2 2)))))

(deftest building-cell-is-blocked
  (let [b  {:kind :building :blocks-path? true :pos [1 1]}
        pg (pathgrid/build (tile/make-grid 3 3) [b])]
    (is (not (pathgrid/passable? pg 1 1)) "building cell blocked")
    (is (pathgrid/passable? pg 0 0) "other cells unaffected")))

(deftest blueprint-cell-stays-passable
  (testing "an unbuilt ghost has no :blocks-path?, so pawns route through it"
    (let [bp (entity/make-blueprint :wall [1 1])
          pg (pathgrid/build (tile/make-grid 3 3) [bp])]
      (is (pathgrid/passable? pg 1 1) "ghost wall cell is walkable until built")
      (is (pathgrid/passable? pg 0 0)))))

(deftest door-cell-is-passable-but-a-portal
  (testing "a :portal? door leaves its cell passable (no INFINITY) yet flags it
            a portal, so A* walks through while regions treat it as a boundary"
    (let [door {:kind :building :blocks-path? false :portal? true :pos [1 1]}
          pg   (pathgrid/build (tile/make-grid 3 3) [door])]
      (is (pathgrid/passable? pg 1 1) "door cell stays passable")
      (is (= 1.0 (pathgrid/cost pg 1 1)) "door keeps the underlying terrain cost")
      (is (pathgrid/portal? pg 1 1) "door cell is a portal")
      (is (not (pathgrid/portal? pg 0 0)) "a plain floor cell is not a portal")
      (is (not (pathgrid/portal? pg -1 0)) "oob is not a portal"))))

(deftest a-grid-with-no-doors-has-no-portals
  (let [pg (pathgrid/build (tile/make-grid 3 3) [])]
    (is (not (pathgrid/portal? pg 1 1)) "no buildings -> no portal cells")))

(deftest multicell-building-blocks-its-whole-footprint
  (let [b  {:kind :building :blocks-path? true :pos [1 1] :size [2 1]}
        pg (pathgrid/build (tile/make-grid 4 4) [b])]
    (is (not (pathgrid/passable? pg 1 1)) "origin blocked")
    (is (not (pathgrid/passable? pg 2 1)) "second footprint cell blocked")
    (is (pathgrid/passable? pg 3 1) "cell past the footprint stays open")
    (is (pathgrid/passable? pg 1 2) "cell below the footprint stays open")))

(deftest multicell-door-portals-its-whole-footprint
  (let [d  {:kind :building :blocks-path? false :portal? true :pos [1 1] :size [2 1]}
        pg (pathgrid/build (tile/make-grid 4 4) [d])]
    (is (pathgrid/passable? pg 1 1) "door cells stay passable")
    (is (pathgrid/passable? pg 2 1))
    (is (pathgrid/portal? pg 1 1) "both footprint cells are portals")
    (is (pathgrid/portal? pg 2 1))
    (is (not (pathgrid/portal? pg 3 1)) "past the footprint is not a portal")))

(deftest oob-cost-is-infinite
  (let [pg (pathgrid/build (tile/make-grid 3 3) [])]
    (is (Double/isInfinite (pathgrid/cost pg -1 0)))
    (is (Double/isInfinite (pathgrid/cost pg 0 9)))))

(deftest for-world-rebuilds-when-building-set-changes
  (let [g  (tile/make-grid 3 3)
        w0 {:grid g :entities {} :kinds {:building (sorted-set)}}
        w1 {:grid g
            :entities {7 {:id 7 :kind :building :blocks-path? true :pos [1 1]}}
            :kinds {:building (sorted-set 7)}}]
    (is (pathgrid/passable? (pathgrid/for-world w0) 1 1) "no building -> passable")
    (is (not (pathgrid/passable? (pathgrid/for-world w1) 1 1)) "building -> blocked")))
