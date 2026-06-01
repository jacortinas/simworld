(ns sim.regions
  "Connected-component labeling of the tile grid — RimWorld's reachability cache.

   PURPOSE: let find-path reject a doomed search in O(1). When start and goal are
   both passable but lie in different connected components (separated by
   impassable terrain or player-built walls), there is provably no route, so we
   return nil WITHOUT running
   A* over the whole reachable component to rediscover that.

   THE LOAD-BEARING RULE: region connectivity MUST equal A* connectivity, or
   reachable? produces a FALSE NEGATIVE — skipping a path that exists, the one
   unacceptable bug. So labeling uses the SAME 8-connected adjacency A* steps
   with (sim.pathfinding's corner rule):
     - cardinal neighbor: connect iff both cells passable.
     - diagonal neighbor: connect iff both cells passable AND both flanking
       cardinals passable (the corner rule — no slipping past a wall corner).
   Then reachable?(a,b) <=> (= region(a) region(b)) is EXACTLY 'A* finds a path'.

   CACHING (the design decision): regions are a pure projection of the PathGrid
   (terrain + buildings), so we memoize by PATHGRID IDENTITY in a size-1 defonce
   atom (of-pathgrid). The PathGrid (sim.pathgrid) is itself memoized on [grid,
   building-set] identity, so its identity flips exactly when passability changes
   (a new grid, or a building add/remove). A new PathGrid is a new identity, so the
   next of-pathgrid rebuilds: the cache CANNOT go stale, which matters because the
   staleness failure mode here is precisely the false negative. NOT in the world;
   NOT saved. Depends on sim.pathgrid + sim.tile; pathfinding requires regions (and
   pathgrid), never the reverse, so the graph stays acyclic. of-grid is a
   terrain-only convenience (no buildings) for tests and terrain inspection."
  (:require
   [sim.pathgrid :as pathgrid]
   [sim.tile     :as tile]))

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
  "Label every cell with finite cost in `costs` with its connected-component id
   (an int-array keyed by tile/idx, -1 for blocked). Ids are canonicalized in
   linear-scan order so the labeling is DETERMINISTIC (same costs -> same ids),
   independent of which cell union-find happened to pick as a root."
  [width height ^doubles costs]
  (let [width    (long width)
        height   (long height)
        n        (* width height)
        passable (boolean-array n)
        parent   (int-array n)
        rank     (int-array n)]
    ;; snapshot passability once (finite cost = passable) + init each cell as its
    ;; own set
    (dotimes [i n]
      (aset passable i (< (aget costs i) Double/POSITIVE_INFINITY))
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
;; Cache: size-1 memo keyed by PathGrid identity. Regions are a pure projection
;; of the PathGrid (terrain + buildings), so a hit is byte-identical to a
;; rebuild; a miss just recomputes O(n). The PathGrid's identity flips exactly
;; when passability changes (a new grid, or a building add/remove), so the cache
;; CANNOT go stale: the staleness failure mode here is precisely the false
;; negative.
;; ---------------------------------------------------------------------------

(defonce ^:private cache (atom nil))   ; {:pathgrid <pg> :regions <index>}

(defn of-pathgrid
  "Region index for a PathGrid, memoized by the PathGrid's IDENTITY. A new
   PathGrid value (new terrain or building set) is a new identity, so this can
   never return a stale labeling for the wrong passability."
  [pg]
  (let [c @cache]
    (if (and c (identical? pg (:pathgrid c)))
      (:regions c)
      (let [index (build-index (:width pg) (:height pg) (:costs pg))]
        (reset! cache {:pathgrid pg :regions index})
        index))))

(defn of-grid
  "Region index for a terrain-only grid (no buildings). Convenience for tests and
   terrain inspection; production reachability goes through of-pathgrid/for-world."
  [grid]
  (of-pathgrid (pathgrid/build grid [])))

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
  "True iff a and b are both passable AND in the same connected component of
   `world`'s PathGrid (terrain + buildings): i.e. A* would find a path between
   them. Both impassable/oob -> false (their region ids are -1, but we require a
   non-negative id, so two blocked cells never 'match')."
  [world [ax ay] [bx by]]
  (let [index (of-pathgrid (pathgrid/for-world world))
        ra    (region-at index ax ay)]
    (and (>= ra 0)
         (== ra (region-at index bx by)))))
