(ns sim.regions
  "Connected-component labeling of the tile grid — RimWorld's reachability cache.

   PURPOSE: let find-path reject a doomed search in O(1). When start and goal are
   both passable but lie in different connected components (separated by water,
   later by walls), there is provably no route, so we return nil WITHOUT running
   A* over the whole reachable component to rediscover that.

   THE LOAD-BEARING RULE: region connectivity MUST equal A* connectivity, or
   reachable? produces a FALSE NEGATIVE — skipping a path that exists, the one
   unacceptable bug. So labeling uses the SAME 8-connected adjacency A* steps
   with (sim.pathfinding's corner rule):
     - cardinal neighbor: connect iff both cells passable.
     - diagonal neighbor: connect iff both cells passable AND both flanking
       cardinals passable (the corner rule — no slipping past a wall corner).
   Then reachable?(a,b) <=> (= region(a) region(b)) is EXACTLY 'A* finds a path'.

   CACHING (the design decision): regions are a pure function of the IMMUTABLE
   grid, so we memoize by GRID IDENTITY in a size-1 defonce atom (like the sprite
   region cache / the defs DB). Unlike :kinds/:schedule — derived from the MUTABLE
   entity set and maintained at an add/remove chokepoint — the grid has no such
   chokepoint (set-tile is grid-level). A new grid value is a new identity, so the
   next of-grid rebuilds: the cache CANNOT go stale, which matters because the
   staleness failure mode here is precisely the false negative. NOT in the world;
   NOT saved. Depends only on sim.tile (pathfinding requires regions, never the
   reverse — the graph stays acyclic)."
  (:require
   [sim.tile :as tile]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Union-find (disjoint set) over passable cell indices — O(n·α).
;; ---------------------------------------------------------------------------

(defn- find-root
  "Representative of i's set, with path halving (points i at its grandparent as
   it climbs — keeps the tree flat without a second pass)."
  ^long [^ints parent ^long i]
  (loop [i i]
    (let [p (aget parent (int i))]
      (if (== p i)
        i
        (let [gp (aget parent (int p))]
          (aset parent (int i) gp)
          (recur (long gp)))))))

(defn- union!
  "Merge the sets of a and b, union by rank."
  [^ints parent ^ints rank ^long a ^long b]
  (let [ra (find-root parent a)
        rb (find-root parent b)]
    (when (not= ra rb)
      (let [hra (aget rank (int ra))
            hrb (aget rank (int rb))]
        (cond
          (< hra hrb) (aset parent (int ra) (int rb))
          (> hra hrb) (aset parent (int rb) (int ra))
          :else       (do (aset parent (int rb) (int ra))
                          (aset rank (int ra) (inc hra))))))))

(defn- build-index
  "Label every passable cell with its connected-component id (an int-array keyed
   by tile/idx, -1 for impassable). Ids are canonicalized in linear-scan order so
   the labeling is DETERMINISTIC (same grid -> same ids), independent of which
   cell union-find happened to pick as a root."
  [{:keys [width height tiles]}]
  (let [width    (long width)
        height   (long height)
        n        (* width height)
        passable (boolean-array n)
        parent   (int-array n)
        rank     (int-array n)]
    ;; snapshot passability once + init each cell as its own set
    (dotimes [i n]
      (aset passable i (boolean (tile/passable? (nth tiles i))))
      (aset parent i i))
    ;; union each passable cell with its FORWARD passable neighbors (right, down,
    ;; down-right, down-left). Those four cover every 8-adjacency edge exactly
    ;; once. Diagonals apply the corner rule: both flanking cardinals must pass.
    (dotimes [i n]
      (when (aget passable i)
        (let [x  (rem i width)
              y  (quot i width)
              rt (when (< (inc x) width)  (aget passable (int (+ (inc x) (* y width)))))
              dn (when (< (inc y) height) (aget passable (int (+ x (* (inc y) width)))))]
          ;; right (cardinal)
          (when rt
            (union! parent rank i (+ (inc x) (* y width))))
          ;; down (cardinal)
          (when dn
            (union! parent rank i (+ x (* (inc y) width))))
          ;; down-right (diagonal): corner rule = right AND down both passable
          (when (and (< (inc x) width) (< (inc y) height)
                     rt dn
                     (aget passable (int (+ (inc x) (* (inc y) width)))))
            (union! parent rank i (+ (inc x) (* (inc y) width))))
          ;; down-left (diagonal): corner rule = left AND down both passable
          (when (and (>= (dec x) 0) (< (inc y) height) dn
                     (aget passable (int (+ (dec x) (* y width))))
                     (aget passable (int (+ (dec x) (* (inc y) width)))))
            (union! parent rank i (+ (dec x) (* (inc y) width)))))))
    ;; canonicalize: dense scan-order ids. The first passable cell of each root
    ;; encountered fixes that component's id, so ids are 0..count-1 by scan order.
    (let [ids   (int-array n -1)
          remap (java.util.HashMap.)]
      (dotimes [i n]
        (when (aget passable i)
          (let [r        (int (find-root parent i))
                existing (.get remap r)]
            (if existing
              (aset ids i (int existing))
              (let [id (.size remap)]
                (.put remap r (int id))
                (aset ids i (int id)))))))
      {:width width :height height :ids ids :count (.size remap)})))

;; ---------------------------------------------------------------------------
;; Cache — size-1 memo keyed by grid identity. Pure projection of an immutable
;; grid, so a hit is byte-identical to a rebuild; a miss just recomputes O(n).
;; ---------------------------------------------------------------------------

(defonce ^:private cache (atom nil))   ; {:grid <grid> :regions <index>}

(defn of-grid
  "The region index for grid — built once and memoized by grid IDENTITY. A new
   grid value (any set-tile) is a new identity, so this can never return a stale
   labeling for the wrong grid."
  [grid]
  (let [c @cache]
    (if (and c (identical? grid (:grid c)))
      (:regions c)
      (let [index (build-index grid)]
        (reset! cache {:grid grid :regions index})
        index))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn region-at
  "Component id at (x, y), or -1 if impassable / out of bounds."
  ^long [{:keys [width height ids]} x y]
  (if (tile/in-bounds? width height x y)
    (aget ^ints ids (tile/idx width x y))
    -1))

(defn count-regions
  "Number of distinct connected components in the index."
  ^long [region-index]
  (:count region-index))

(defn reachable?
  "True iff a and b are both passable AND in the same connected component — i.e.
   A* would find a path between them. Both impassable/oob -> false (their region
   ids are -1, but we require a non-negative id, so two walls never 'match')."
  [grid [ax ay] [bx by]]
  (let [index (of-grid grid)
        ra    (region-at index ax ay)]
    (and (>= ra 0)
         (== ra (region-at index bx by)))))
