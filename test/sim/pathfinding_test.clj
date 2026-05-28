(ns sim.pathfinding-test
  "Tests for 8-directional A* over the tile grid. All headless — find-path is
   pure (world,start,goal)->path. We assert exact optimal paths because, with
   uniform grass and the octile heuristic, the minimum-cost route is unique for
   these fixtures."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.tile        :as tile]
   [sim.pathfinding :as pathfinding]))

(defn- world-of
  "A bare world holding just a grid (find-path reads only :grid)."
  [grid]
  {:grid grid})

(def ^:private root2 (Math/sqrt 2.0))

;; ---------------------------------------------------------------------------
;; traversal-cost — the shared cost currency A* and movement both spend.
;; ---------------------------------------------------------------------------

(deftest traversal-cost-cardinal-is-terrain-move-cost
  (let [g (tile/make-grid 5 5)]
    (is (= 1.0 (pathfinding/traversal-cost g [0 0] [1 0])) "grass cardinal = 1.0")
    (is (= 1.0 (pathfinding/traversal-cost g [0 0] [0 1])))))

(deftest traversal-cost-diagonal-is-root2-scaled
  (let [g (tile/make-grid 5 5)]
    (is (> 1e-9 (Math/abs (- root2 (pathfinding/traversal-cost g [0 0] [1 1]))))
        "grass diagonal = √2 × move-cost")))

(deftest traversal-cost-honors-terrain-multiplier
  (let [g (tile/set-tile (tile/make-grid 5 5) 1 0 :water)] ; water move-cost 2.5
    (is (= 2.5 (pathfinding/traversal-cost g [0 0] [1 0]))
        "water cardinal = 2.5")))

(deftest traversal-cost-is-infinite-into-impassable
  (let [g (tile/set-tile (tile/make-grid 5 5) 1 0 :wall)]
    (is (Double/isInfinite (pathfinding/traversal-cost g [0 0] [1 0]))
        "stepping into a wall costs ∞")))

;; ---------------------------------------------------------------------------
;; find-path — diagonals are allowed and preferred when cheaper.
;; ---------------------------------------------------------------------------

(deftest diagonal-beats-the-manhattan-route
  (testing "a clear diagonal goal is reached by stepping diagonally, not in an L"
    (let [w    (world-of (tile/make-grid 4 4))
          path (pathfinding/find-path w [0 0] [3 3])]
      (is (= [[0 0] [1 1] [2 2] [3 3]] path)
          "straight diagonal — 4 tiles, not the 7-tile Manhattan path"))))

(deftest diagonal-is-strictly-shorter-than-four-connected
  (testing "the 8-connected route uses fewer tiles than a 4-connected one would"
    (let [w    (world-of (tile/make-grid 6 6))
          path (pathfinding/find-path w [0 0] [4 4])]
      (is (= 5 (count path)) "5 tiles (4 diagonal steps) vs 9 for Manhattan"))))

;; ---------------------------------------------------------------------------
;; Corner rule — a diagonal step is blocked when EITHER flanking cardinal cell
;; is impassable (strict: no clipping past a wall corner).
;; ---------------------------------------------------------------------------

(deftest diagonal-cannot-cut-a-wall-corner
  (testing "with a wall flanking the diagonal, the pawn routes around it"
    (let [g    (tile/set-tile (tile/make-grid 4 4) 1 0 :wall) ; flanks [0 0]->[1 1]
          path (pathfinding/find-path (world-of g) [0 0] [1 1])]
      (is (= [[0 0] [0 1] [1 1]] path)
          "goes around via the open cardinal, never the cut diagonal")
      (is (not= [[0 0] [1 1]] path) "the corner-cutting diagonal is refused"))))

(deftest boxed-in-by-corner-walls-has-no-path
  (testing "both cardinals walled means the only exit is a forbidden corner cut"
    (let [g (-> (tile/make-grid 4 4)
                (tile/set-tile 1 0 :wall)
                (tile/set-tile 0 1 :wall))
          path (pathfinding/find-path (world-of g) [0 0] [2 2])]
      (is (nil? path) "no legal route out — the diagonal would cut the corner"))))

;; ---------------------------------------------------------------------------
;; Unchanged invariants.
;; ---------------------------------------------------------------------------

(deftest impassable-goal-returns-nil
  (let [g (tile/set-tile (tile/make-grid 5 5) 2 2 :wall)]
    (is (nil? (pathfinding/find-path (world-of g) [0 0] [2 2])))))

(deftest same-start-and-goal-is-a-single-tile
  (let [w (world-of (tile/make-grid 5 5))]
    (is (= [[2 2]] (pathfinding/find-path w [2 2] [2 2])))))
