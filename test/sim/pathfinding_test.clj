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

;; ---------------------------------------------------------------------------
;; Determinism — the tie-break is a TOTAL order (f, then linear index), so the
;; returned path is a total function of (grid, start, goal), independent of the
;; open-set container (this is what makes the future PriorityQueue swap safe).
;; ---------------------------------------------------------------------------

(deftest tie-break-prefers-lower-linear-index
  (testing "among equal-cost routes the lower linear-index intermediate wins:
            [0 0]->[2 1] ties via [1 0] (idx 1) or [1 1] (idx 5) at cost 1+√2"
    (let [w (world-of (tile/make-grid 4 4))]
      (is (= [[0 0] [1 0] [2 1]] (pathfinding/find-path w [0 0] [2 1]))))))

;; ---------------------------------------------------------------------------
;; Oracle property test — an independent, exhaustive Dijkstra (no heuristic)
;; over the SAME cost model (pathfinding/traversal-cost) and the SAME corner
;; rule. find-path must return an optimal-cost, valid path (or nil iff
;; unreachable). We assert COST-equality, not path-equality: two distinct
;; min-cost routes are both correct, so the oracle's route need not match A*'s.
;; This validates against the current impl AND guards the array/PQ rewrite.
;; ---------------------------------------------------------------------------

(defn- passable-at?
  [{:keys [width height] :as grid} x y]
  (and (tile/in-bounds? width height x y)
       (tile/passable? (tile/tile-at grid x y))))

(defn- corner-cut?
  "A diagonal step (fx,fy)->(tx,ty) cuts a corner if either flanking cardinal is
   impassable/off-grid — mirrors sim.pathfinding's private corner rule."
  [grid fx fy tx ty]
  (or (not (passable-at? grid tx fy))
      (not (passable-at? grid fx ty))))

(defn- legal-step?
  [grid [fx fy] [tx ty]]
  (let [dx (- (long tx) (long fx)) dy (- (long ty) (long fy))]
    (and (<= (Math/abs dx) 1) (<= (Math/abs dy) 1)
         (not (and (zero? dx) (zero? dy)))
         (passable-at? grid tx ty)
         (not (and (not= 0 dx) (not= 0 dy) (corner-cut? grid fx fy tx ty))))))

(defn- legal-steps
  "The legal 8-connected neighbors of `node` (in-bounds, passable, no corner cut)."
  [grid [x y]]
  (for [dx [-1 0 1] dy [-1 0 1]
        :when (not (and (zero? dx) (zero? dy)))
        :let  [nx (+ (long x) dx) ny (+ (long y) dy)]
        :when (legal-step? grid [x y] [nx ny])]
    [nx ny]))

(defn- oracle-cost
  "Optimal 8-connected path cost start->goal under traversal-cost + the corner
   rule, or nil if unreachable. Plain Dijkstra (a sorted-set frontier keyed by
   [dist idx node] for a total order); independent of sim.pathfinding's A*."
  [grid start goal]
  (let [width  (:width grid)
        idx-of (fn [[x y]] (tile/idx width x y))]
    (loop [dist {start 0.0}
           pq   (sorted-set [0.0 (idx-of start) start])]
      (if-let [[d _ node :as top] (first pq)]
        (cond
          (= node goal) d
          (> (double d) (double (get dist node Double/POSITIVE_INFINITY)))
          (recur dist (disj pq top))                       ; stale entry
          :else
          (let [[dist' pq']
                (reduce (fn [[dm q] nb]
                          (let [nd (+ (double d) (pathfinding/traversal-cost grid node nb))]
                            (if (< nd (double (get dm nb Double/POSITIVE_INFINITY)))
                              [(assoc dm nb nd) (conj q [nd (idx-of nb) nb])]
                              [dm q])))
                        [dist (disj pq top)]
                        (legal-steps grid node))]
            (recur dist' pq')))
        (get dist goal)))))                                ; frontier empty -> nil if unreached

(defn- path-cost
  [grid path]
  (reduce + 0.0 (map #(pathfinding/traversal-cost grid %1 %2) path (rest path))))

(defn- path-valid?
  [grid start goal path]
  (and (vector? path)
       (= start (first path))
       (= goal (peek path))
       (every? (fn [[x y]] (passable-at? grid x y)) path)
       (every? (fn [[a b]] (legal-step? grid a b)) (map vector path (rest path)))))

(deftest find-path-matches-oracle-on-random-grids
  (testing "find-path returns an optimal-cost, valid path, or nil iff unreachable —
            checked against an independent Dijkstra oracle over random grids"
    (let [rng      (java.util.Random. 1234567)
          ri       (fn [n] (.nextInt rng (int n)))
          ;; weighted toward passable terrain, with walls/stone as obstacles
          terrains [:grass :grass :grass :dirt :gravel :water :wall :stone]]
      (dotimes [_ 300]
        (let [w     (+ 4 (ri 7))                            ; 4..10
              h     (+ 4 (ri 7))
              cells (vec (repeatedly (* w h) #(nth terrains (ri (count terrains)))))
              grid  {:width w :height h :tiles cells}
              passable-idxs (filterv #(passable-at? grid (mod % w) (quot % w))
                                     (range (* w h)))]
          (when (seq passable-idxs)
            (let [pick  #(let [i (nth passable-idxs (ri (count passable-idxs)))]
                           [(mod i w) (quot i w)])
                  start (pick)
                  goal  (pick)
                  oc    (oracle-cost grid start goal)
                  path  (pathfinding/find-path {:grid grid} start goal)
                  label (str start "->" goal " on " w "x" h)]
              (if (nil? oc)
                (is (nil? path) (str "unreachable " label " must be nil"))
                (do
                  (is (some? path) (str "reachable " label " must find a path"))
                  (when path
                    (is (> 1e-9 (Math/abs (- (double oc) (path-cost grid path))))
                        (str "cost-optimal " label))
                    (is (path-valid? grid start goal path)
                        (str "valid path " label))))))))))))
