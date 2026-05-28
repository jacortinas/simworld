(ns sim.pathfinding
  "8-directional A* pathfinding over the tile grid.

   This is the IDIOMATIC implementation — persistent maps for open/closed
   sets, allocation per neighbor expansion. It is correct and readable;
   it is also the place that will show up first in a profiler once we have
   a few pawns asking for paths on larger maps.

   Diagonals are allowed (neighbors-8). A diagonal step costs ×√2 so it is
   never a free shortcut, and the corner rule forbids slipping diagonally past
   an impassable cell. `traversal-cost` is the unified currency A* minimizes AND
   movement spends (sim.job multiplies it by the pawn's base move-ticks), so the
   route A* prefers is exactly the route that is fastest to walk.

   The optimization path documented in the architecture notes:
     1. Flatten cost / came-from to primitive arrays (long-array/int-array)
        indexed by (+ x (* y width)).
     2. Open set as java.util.PriorityQueue keyed by f-score.
     3. Type-hint everything, (set! *unchecked-math* true) in the hot ns.
     4. Hierarchical A* (HPA*) for large maps with frequent re-paths.

   We are not doing that yet. The public API (find-path / traversal-cost) stays
   stable so the optimization is a drop-in replacement."
  (:require
   [sim.tile :as tile]))

(set! *warn-on-reflection* true)

;; √2 — the cost multiplier for a diagonal step (it covers √2× the distance of
;; a cardinal step). ^:const, not a type hint, to avoid the ^double-on-def trap.
(def ^:const root2 1.4142135623730951)

(defn- diagonal?
  "True when the step from `from` to adjacent `to` changes both axes."
  [[^long fx ^long fy] [^long tx ^long ty]]
  (and (not= fx tx) (not= fy ty)))

(defn- octile
  "Octile distance heuristic — the diagonal-aware cousin of Manhattan:
   D·(dx+dy) + (D2−2D)·min(dx,dy), with cardinal cost D=1 and diagonal D2=√2.
   Admissible for 8-connected grids whose cheapest passable step is 1.0 (grass
   is the move-cost floor): plain Manhattan would over-estimate diagonal moves
   and make A* return non-optimal paths."
  ^double [[^long x1 ^long y1] [^long x2 ^long y2]]
  (let [dx (Math/abs (- x1 x2))
        dy (Math/abs (- y1 y2))]
    (+ (double (+ dx dy))
       (* (- root2 2.0) (double (min dx dy))))))

(defn traversal-cost
  "Cost of stepping from `from` to the adjacent cell `to`: the terrain move-cost
   of `to`, ×√2 for a diagonal step. Returns +Infinity if `to` is out of bounds
   or impassable. `from` is only read to detect diagonality.

   This is the shared currency: A* sums it to score a route, and sim.job
   multiplies it by a pawn's base move-ticks to time the actual walk."
  ^double [grid from to]
  (let [[tx ty] to
        t       (tile/tile-at grid tx ty)]
    (if (and t (tile/passable? t))
      (* (tile/move-cost t) (if (diagonal? from to) root2 1.0))
      Double/POSITIVE_INFINITY)))

(defn- corner-blocked?
  "True when a DIAGONAL step from `from` to `to` would cut past an impassable
   flanking cell. The two cells flanking the diagonal are the cardinals it
   slips between; if either is impassable (or off-grid), the cut is refused."
  [grid [^long fx ^long fy] [^long tx ^long ty]]
  (let [flank-a (tile/tile-at grid tx fy)
        flank-b (tile/tile-at grid fx ty)]
    (or (not (and flank-a (tile/passable? flank-a)))
        (not (and flank-b (tile/passable? flank-b))))))

(defn- reconstruct
  "Walk `came-from` from goal back to start and reverse."
  [came-from start goal]
  (loop [node goal acc (list goal)]
    (if (= node start)
      (vec acc)
      (let [prev (came-from node)]
        (if (nil? prev)
          nil
          (recur prev (conj acc prev)))))))

(defn- expand-neighbor
  "Reducing step: given the current A* state and one neighbor position,
   return possibly-updated [open g-score came-from]. Skips closed neighbors,
   impassable steps, and diagonal steps that would cut a wall corner."
  [grid closed' current goal [open g-score came-from] npos]
  (if (or (closed' npos)
          (and (diagonal? current npos) (corner-blocked? grid current npos)))
    [open g-score came-from]
    (let [step (traversal-cost grid current npos)]
      (if (Double/isInfinite step)
        [open g-score came-from]
        (let [g-cur     (double (g-score current))
              tentative (+ g-cur step)
              best      (double (g-score npos Double/POSITIVE_INFINITY))]
          (if (< tentative best)
            [(assoc open npos (+ tentative (octile npos goal)))
             (assoc g-score npos tentative)
             (assoc came-from npos current)]
            [open g-score came-from]))))))

(defn find-path
  "Find a path from start [x y] to goal [x y] over the world's grid, stepping in
   8 directions. Returns a vector of [x y] tiles inclusive of start and goal, or
   nil if no path exists.

   `world` is the live world map; we only read its :grid."
  [world start goal]
  (let [grid (:grid world)]
    (cond
      (= start goal) [start]

      (not (tile/passable? (tile/tile-at grid (first goal) (second goal))))
      nil

      :else
      (loop [open      {start 0.0}     ;; pos -> f-score
             g-score   {start 0.0}     ;; pos -> g-score
             came-from {}              ;; pos -> previous pos
             closed    #{}]
        (if (empty? open)
          nil
          (let [[current _] (apply min-key val open)]
            (if (= current goal)
              (reconstruct came-from start goal)
              (let [open'   (dissoc open current)
                    closed' (conj closed current)
                    [cx cy] current
                    [open'' g-score' came-from']
                    (reduce
                     (partial expand-neighbor grid closed' current goal)
                     [open' g-score came-from]
                     (tile/neighbors-8 grid cx cy))]
                (recur open'' g-score' came-from' closed')))))))))
