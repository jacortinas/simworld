(ns sim.pathgrid-test
  "PathGrid: a per-cell traversal-cost double-array (INFINITY = blocked), built
   from terrain costs with blocking buildings stamped INFINITY. Memoized by
   [grid identity, building-set identity] in for-world."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.tile     :as tile]
   [sim.pathgrid :as pathgrid]))

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
