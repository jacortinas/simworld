(ns sim.pathfinding
  "8-directional A* pathfinding over the tile grid.

   The search state is flattened onto per-call PRIMITIVE ARRAYS keyed by the
   linear tile index (+ x (* y width)): g-score (double-array), came-from
   predecessor (int-array, -1 = none), and a closed boolean-array. The open set
   is a java.util.PriorityQueue of Entry records ordered by (f-score, then
   index). That secondary key makes the order TOTAL, so the returned path is a
   function of (grid, start, goal) alone — never of heap shape — which is the
   determinism guarantee, and is what made the swap off the old persistent-map
   open set safe (see the (f, idx) tie-break that landed first).

   Diagonals are allowed. A diagonal step costs ×√2 so it is never a free
   shortcut, and the corner rule forbids slipping diagonally past an impassable
   cell. `traversal-cost` is the unified currency A* minimizes AND movement
   spends (sim.job multiplies it by the pawn's base move-ticks), so the route A*
   prefers is exactly the route that is fastest to walk. The hot loop computes
   the same cost from a per-search terrain snapshot (a cost/passable array pair),
   so it never deref's the defs registry per neighbor.

   The octile heuristic is admissible AND consistent for this grid ONLY because
   the cheapest passable step is >= 1.0 (sim.defs enforces ::move-cost >= 1.0).
   Consistency is what lets A* finalize a node at its first non-closed poll: no
   decrease-key and no reopening — stale queue entries are simply skipped (lazy
   deletion). A sub-1.0 terrain would break this; that is why it is forbidden at
   the content layer rather than guarded here.

   Remaining endgame (architecture notes): per-worker scratch buffers + a
   generation stamp to skip the per-call array fills, then hierarchical A* (HPA*)
   with a region/reachability cache for large maps with frequent re-paths.

   The public API (find-path / traversal-cost) is stable."
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

(defn- octile*
  "Octile distance heuristic between (x1,y1) and (x2,y2) — the diagonal-aware
   cousin of Manhattan: (dx+dy) + (√2−2)·min(dx,dy), cardinal cost 1, diagonal
   √2. Admissible AND consistent for this 8-grid IFF the cheapest passable step
   is >= 1.0 (enforced by sim.defs ::move-cost); plain Manhattan would
   over-estimate diagonals and make A* return non-optimal paths. Primitive args
   so the A* hot loop allocates nothing to score a node."
  ^double [^long x1 ^long y1 ^long x2 ^long y2]
  (let [dx (Math/abs (- x1 x2))
        dy (Math/abs (- y1 y2))]
    (+ (double (+ dx dy))
       (* (- root2 2.0) (double (min dx dy))))))

(defn traversal-cost
  "Cost of stepping from `from` to the adjacent cell `to`: the terrain move-cost
   of `to`, ×√2 for a diagonal step. Returns +Infinity if `to` is out of bounds
   or impassable. `from` is only read to detect diagonality.

   This is the shared currency: A* sums it to score a route, and sim.job
   multiplies it by a pawn's base move-ticks to time the actual walk. The A* hot
   loop computes the same value from its terrain snapshot — keep them in agreement
   (the oracle property test checks optimal cost under THIS fn)."
  ^double [grid from to]
  (let [[tx ty] to
        t       (tile/tile-at grid tx ty)]
    (if (and t (tile/passable? t))
      (* (tile/move-cost t) (if (diagonal? from to) root2 1.0))
      Double/POSITIVE_INFINITY)))

;; The 8 neighbor offsets, as parallel index arrays (no per-node seq/vector
;; allocation). Order is irrelevant to the result — the (f, idx) tie-break
;; decides between equal-cost routes, not emission order.
(def ^:private ^"[I" n-dx (int-array [-1 -1 -1  0 0  1 1 1]))
(def ^:private ^"[I" n-dy (int-array [-1  0  1 -1 1 -1 0 1]))

;; Open-set entry: a node index with its f-score, frozen at push time. Ordered
;; by (f asc, idx asc) — a TOTAL order, so heap shape can't change the result.
(deftype Entry [^double f ^int idx]
  Comparable
  (compareTo [_ other]
    (let [o ^Entry other
          c (Double/compare f (.f o))]
      (if (zero? c) (Integer/compare idx (.idx o)) c))))

(defn- reconstruct-idx
  "Walk the came-from int-array from goal-idx back to start-idx, decoding each
   linear index to [x y]; returns the start->goal vector. The -1 sentinel is a
   defensive corrupt-state guard (a consistent heuristic always links goal back
   to start) — hitting it returns nil, mirroring the old reconstruct."
  [^ints came-from ^long width ^long start-idx ^long goal-idx]
  (loop [i goal-idx acc '()]
    (let [acc (conj acc [(rem i width) (quot i width)])]
      (if (== i start-idx)
        (vec acc)
        (let [p (aget came-from (int i))]
          (if (neg? p) nil (recur (long p) acc)))))))

(defn find-path
  "Find a path from start [x y] to goal [x y] over the world's grid, stepping in
   8 directions. Returns a vector of [x y] tiles inclusive of start and goal, or
   nil if no path exists. `world` is the live world map; we only read its :grid."
  [world start goal]
  (let [grid   (:grid world)
        width  (long (:width grid))
        height (long (:height grid))
        sx     (long (first start))
        sy     (long (second start))
        gx     (long (first goal))
        gy     (long (second goal))]
    (cond
      (and (== sx gx) (== sy gy)) [start]

      ;; Guard start AND goal: the sized arrays would throw on an out-of-bounds
      ;; goal that the old map-based search merely failed to reach.
      (or (not (tile/in-bounds? width height sx sy))
          (not (tile/in-bounds? width height gx gy))
          (not (tile/passable? (tile/tile-at grid gx gy))))
      nil

      :else
      (let [n         (* width height)
            tiles     (:tiles grid)
            cost      (double-array n)
            passable  (boolean-array n)
            g-score   (double-array n Double/POSITIVE_INFINITY)
            came-from (int-array n -1)
            closed    (boolean-array n)
            ^java.util.PriorityQueue pq (java.util.PriorityQueue.)
            start-idx (int (+ sx (* sy width)))
            goal-idx  (int (+ gx (* gy width)))]
        ;; Per-search terrain snapshot: resolve each cell's cost/passability once
        ;; so the hot loop reads arrays, never the defs registry.
        (dotimes [i n]
          (let [t (nth tiles i)
                p (boolean (tile/passable? t))]
            (aset passable i p)
            (aset cost i (if p (double (tile/move-cost t)) Double/POSITIVE_INFINITY))))
        (aset g-score start-idx 0.0)
        (.add pq (Entry. (octile* sx sy gx gy) start-idx))
        (loop []
          (if (.isEmpty pq)
            nil
            (let [e   ^Entry (.poll pq)
                  cur (.idx e)]
              (cond
                (== cur goal-idx) (reconstruct-idx came-from width start-idx goal-idx)
                (aget closed cur) (recur)               ; stale entry, node already final
                :else
                (do
                  (aset closed cur true)
                  (let [cx (rem cur width)
                        cy (quot cur width)
                        gc (aget g-score cur)]
                    (dotimes [k 8]
                      (let [dx (aget n-dx k)
                            dy (aget n-dy k)
                            nx (+ cx dx)
                            ny (+ cy dy)]
                        (when (and (>= nx 0) (< nx width) (>= ny 0) (< ny height))
                          (let [nidx (int (+ nx (* ny width)))]
                            (when (and (not (aget closed nidx))
                                       (aget passable nidx))
                              (let [diag (and (not (zero? dx)) (not (zero? dy)))]
                                ;; corner rule: a diagonal is refused if either
                                ;; flanking cardinal is impassable. Both flanks are
                                ;; in-bounds whenever the diagonal neighbor is.
                                (when-not (and diag
                                               (or (not (aget passable (int (+ nx (* cy width)))))
                                                   (not (aget passable (int (+ cx (* ny width)))))))
                                  (let [step      (* (aget cost nidx) (if diag root2 1.0))
                                        tentative (+ gc step)]
                                    (when (< tentative (aget g-score nidx))
                                      (aset g-score nidx tentative)
                                      (aset came-from nidx cur)
                                      (.add pq (Entry. (+ tentative (octile* nx ny gx gy))
                                                       nidx)))))))))))
                    (recur)))))))))))
