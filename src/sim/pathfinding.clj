(ns sim.pathfinding
  "A* pathfinding over the tile grid.

   This is the IDIOMATIC implementation — persistent maps for open/closed
   sets, allocation per neighbor expansion. It is correct and readable;
   it is also the place that will show up first in a profiler once we have
   a few pawns asking for paths on larger maps.

   The optimization path documented in the architecture notes:
     1. Flatten cost / came-from to primitive arrays (long-array/int-array)
        indexed by (+ x (* y width)).
     2. Open set as java.util.PriorityQueue keyed by f-score.
     3. Type-hint everything, (set! *unchecked-math* true) in the hot ns.
     4. Hierarchical A* (HPA*) for large maps with frequent re-paths.

   We are not doing that yet. The public API (find-path) stays stable so the
   optimization is a drop-in replacement."
  (:require
   [sim.tile :as tile]))

(set! *warn-on-reflection* true)

(defn- manhattan
  ^double [[^long x1 ^long y1] [^long x2 ^long y2]]
  (double (+ (Math/abs (- x1 x2)) (Math/abs (- y1 y2)))))

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

(defn- step-cost
  "Cost of stepping into `to-pos` given the grid's terrain. Returns nil if
   the destination is impassable."
  ^double [grid to-pos]
  (let [[x y] to-pos
        t    (tile/tile-at grid x y)]
    (if (and t (tile/passable? t))
      (tile/move-cost t)
      ;; Sentinel — caller checks for nil terrain or impassable.
      Double/POSITIVE_INFINITY)))

(defn- expand-neighbor
  "Reducing step: given the current A* state and one neighbor position,
   return possibly-updated [open g-score came-from]."
  [grid closed' current goal [open g-score came-from] npos]
  (let [step (step-cost grid npos)]
    (if (or (closed' npos) (Double/isInfinite step))
      [open g-score came-from]
      (let [g-cur     (double (g-score current))
            tentative (+ g-cur step)
            best      (double (g-score npos Double/POSITIVE_INFINITY))]
        (if (< tentative best)
          [(assoc open npos (+ tentative (manhattan npos goal)))
           (assoc g-score npos tentative)
           (assoc came-from npos current)]
          [open g-score came-from])))))

(defn find-path
  "Find a path from start [x y] to goal [x y] over the world's grid.
   Returns a vector of [x y] tiles inclusive of start and goal, or nil if
   no path exists.

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
                     (tile/neighbors-4 grid cx cy))]
                (recur open'' g-score' came-from' closed')))))))))
