(ns sim.regions
  "Connected-component labeling of the tile grid: RimWorld's reachability cache.

   PURPOSE: let find-path reject a doomed search in O(1). When start and goal are
   both passable but lie in different connected components (separated by
   impassable terrain or player-built walls), there is provably no route, so we
   return nil WITHOUT running
   A* over the whole reachable component to rediscover that.

   THE LOAD-BEARING RULE: region connectivity MUST equal A* connectivity, or
   reachable? produces a FALSE NEGATIVE, skipping a path that exists, the one
   unacceptable bug. So labeling uses the SAME 8-connected adjacency A* steps
   with (sim.pathfinding's corner rule):
     - cardinal neighbor: connect iff both cells passable.
     - diagonal neighbor: connect iff both cells passable AND both flanking
       cardinals passable (the corner rule: no slipping past a wall corner).
   Then reachable?(a,b) <=> (= component(a) component(b)) is EXACTLY
   'A* finds a path'.

   CACHING (the design decision): regions are a pure projection of the PathGrid
   (terrain + buildings), so we memoize by PATHGRID IDENTITY in a size-1 defonce
   atom (of-pathgrid). The PathGrid (sim.pathgrid) is itself memoized on [grid,
   building-set] identity, so its identity flips exactly when passability changes
   (a new grid, or a building add/remove). A new PathGrid is a new identity, so the
   next of-pathgrid rebuilds: the cache CANNOT go stale, which matters because the
   staleness failure mode here is precisely the false negative. NOT in the world;
   NOT saved. Depends on sim.pathgrid + sim.tile; pathfinding requires regions (and
   pathgrid), never the reverse, so the graph stays acyclic. of-grid is a
   terrain-only convenience (no buildings) for tests and terrain inspection.

   CHUNKED REGION GRAPH (Spec 2): regions are now bounded to CHUNK-SIZE x
   CHUNK-SIZE cells (RimWorld's Region/GridSize = 12). Each chunk flood produces
   one or more region NODES; cross-chunk adjacency edges link them into a graph.
   A second union-find pass over the graph assigns region NODES to connected
   COMPONENTS (Districts). reachable? compares component ids: still O(1). A full
   open map spanning multiple chunks has multiple region nodes but one component."
  (:require
   [sim.pathgrid :as pathgrid]
   [sim.tile     :as tile]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Union-find (disjoint set) over passable cell indices, O(n·α).
;; ---------------------------------------------------------------------------

(defn- find-root
  "Representative of i's set, with path halving (points i at its grandparent as
   it climbs, keeps the tree flat without a second pass)."
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

;; ---------------------------------------------------------------------------
;; Chunking: regions are bounded to CHUNK_SIZE x CHUNK_SIZE cells (RimWorld's
;; Region.GridSize). A region id is chunk-canonical: chunk-index * MAX_PER_CHUNK
;; + a within-chunk scan-order local index. So an id is a pure function of its
;; chunk's cells (stable for untouched chunks, history-independent).
;; ---------------------------------------------------------------------------

(def ^:const chunk-size 12)
(def ^:const max-per-chunk 144)          ; chunk-size * chunk-size (worst-case bound)

(defn- n-chunks
  "Number of chunks spanning `len` cells (ceil division)."
  ^long [^long len]
  (quot (+ len (dec chunk-size)) chunk-size))

(defn- chunk-index
  "Linear index of chunk (cx, cy) given `nxc` chunks per row."
  ^long [^long cx ^long cy ^long nxc]
  (+ cx (* cy nxc)))

(defn- rid->chunk
  "Decode a region id back to its [cx cy] chunk coords."
  [^long rid ^long nxc]
  (let [ci (quot rid max-per-chunk)]
    [(rem ci nxc) (quot ci nxc)]))

(defn- legal-step?
  "Can A* step from (x,y) to adjacent (nx,ny)? Both passable; a DIAGONAL also
   needs both flanking cardinals passable (the corner rule). Reads passability
   from `costs`. Mirrors sim.pathfinding's rule EXACTLY; the oracle test guards
   the equivalence. Flanks are in-bounds whenever both endpoints are."
  [costs width height x y nx ny]
  (let [^doubles costs costs
        width  (long width)
        height (long height)
        x      (long x)
        y      (long y)
        nx     (long nx)
        ny     (long ny)]
    (and (tile/in-bounds? width height nx ny)
         (< (aget costs (tile/idx width nx ny)) Double/POSITIVE_INFINITY)
         (let [dx (- nx x) dy (- ny y)]
           (or (zero? dx) (zero? dy)
               (and (< (aget costs (tile/idx width nx y)) Double/POSITIVE_INFINITY)
                    (< (aget costs (tile/idx width x ny)) Double/POSITIVE_INFINITY)))))))

(defn- flood-chunk!
  "Reset chunk [x0..x1]x[y0..y1] from `costs` (blocked -> -1), then flood its
   passable cells into region ids (base + local scan-order index), 8-connected
   with the corner rule, never leaving the chunk. Mutates `cell->region`."
  [cell->region costs width height x0 y0 x1 y1 base]
  (let [^ints  cell->region cell->region
        ^doubles costs costs
        width  (long width)
        height (long height)
        x0     (long x0)
        y0     (long y0)
        x1     (long x1)
        y1     (long y1)
        base   (long base)]
    ;; reset: passable -> -2 (unassigned), blocked -> -1
    (loop [y y0]
      (when (<= y y1)
        (loop [x x0]
          (when (<= x x1)
            (let [i (tile/idx width x y)]
              (aset cell->region i (int (if (< (aget costs i) Double/POSITIVE_INFINITY) -2 -1))))
            (recur (inc x))))
        (recur (inc y))))
    ;; flood each still-unassigned passable cell in scan order
    (let [stack (java.util.ArrayDeque.)]
      (loop [y y0 local (long 0)]
        (when (<= y y1)
          (let [next-local
                (loop [x x0 local (long local)]
                  (if (<= x x1)
                    (let [i (tile/idx width x y)]
                      (if (== (aget cell->region i) -2)
                        (let [rid (+ base local)]
                          (aset cell->region i (int rid))
                          (.push stack (int i))
                          (while (not (.isEmpty stack))
                            (let [ci (int (.pop stack))
                                  cx (rem ci width) cy (quot ci width)]
                              (doseq [[dx dy] [[-1 -1] [0 -1] [1 -1] [-1 0]
                                               [1 0] [-1 1] [0 1] [1 1]]]
                                (let [nx (+ cx dx) ny (+ cy dy)]
                                  (when (and (>= nx x0) (<= nx x1) (>= ny y0) (<= ny y1)
                                             (== (aget cell->region (tile/idx width nx ny)) -2)
                                             (legal-step? costs width height cx cy nx ny))
                                    (aset cell->region (tile/idx width nx ny) (int rid))
                                    (.push stack (int (tile/idx width nx ny))))))))
                          (recur (inc x) (inc local)))
                        (recur (inc x) local)))
                    local))]
            (recur (inc y) (long next-local))))))))

(defn- build-cell->region
  "Fresh int-array (-1 everywhere), then flood every chunk."
  ^ints [^long width ^long height ^doubles costs]
  (let [arr (int-array (* width height) -1)
        nxc (n-chunks width)
        nyc (n-chunks height)]
    (dotimes [cy nyc]
      (dotimes [cx nxc]
        (let [x0 (* cx chunk-size) y0 (* cy chunk-size)
              x1 (min (dec (+ x0 chunk-size)) (dec width))
              y1 (min (dec (+ y0 chunk-size)) (dec height))
              base (* (chunk-index cx cy nxc) max-per-chunk)]
          (flood-chunk! arr costs width height x0 y0 x1 y1 base))))
    arr))

(defn- build-graph
  "From `cell->region` + `costs`, build {rid {:chunk [cx cy] :neighbors #{rid...}}}.
   Pass 1 seeds every region node (with its chunk, empty neighbor set). Pass 2
   adds an edge between adjacent cells in DIFFERENT regions when the step is legal
   (corner rule). All such edges are cross-chunk by construction (intra-chunk
   connected cells already share one region)."
  [^ints cell->region ^doubles costs ^long width ^long height]
  (let [nxc (n-chunks width)
        n   (* width height)
        base (persistent!
              (reduce (fn [m i]
                        (let [r (aget cell->region (int i))]
                          (if (or (neg? r) (contains? m r))
                            m
                            (assoc! m r {:chunk (rid->chunk r nxc) :neighbors #{}}))))
                      (transient {})
                      (range n)))
        add-edge (fn [m a b]
                   (-> m
                       (update-in [a :neighbors] conj b)
                       (update-in [b :neighbors] conj a)))]
    (loop [i 0 m base]
      (if (< i n)
        (let [a (aget cell->region (int i))]
          (if (neg? a)
            (recur (inc i) m)
            (let [x (rem i width) y (quot i width)
                  m (reduce
                     (fn [m [nx ny]]
                       (if (legal-step? costs width height x y nx ny)
                         (let [b (aget cell->region (tile/idx width nx ny))]
                           (if (and (>= b 0) (not= a b)) (add-edge m a b) m))
                         m))
                     m
                     [[(inc x) y] [x (inc y)] [(inc x) (inc y)] [(dec x) (inc y)]])]
              (recur (inc i) m))))
        m))))

(defn- build-components
  "region->component map via union-find over the region graph. Component ids are
   scan-order canonical over SORTED region ids, so the assignment is deterministic
   (independent of union order). reachable? only compares cids for equality."
  [regions]
  (let [rids   (vec (sort (keys regions)))
        n      (count rids)
        idx-of (zipmap rids (range))
        parent (int-array n)
        rank   (int-array n)]
    (dotimes [i n] (aset parent i i))
    (doseq [r rids, nb (:neighbors (regions r))]
      (when (< (long (idx-of r)) (long (idx-of nb)))      ; each undirected edge once
        (union! parent rank (idx-of r) (idx-of nb))))
    (let [remap (java.util.HashMap.)]
      (persistent!
       (reduce (fn [m i]
                 (let [root (find-root parent i)
                       cid  (or (.get remap root)
                                (let [c (.size remap)] (.put remap root (int c)) c))]
                   (assoc! m (rids i) cid)))
               (transient {})
               (range n))))))

(defn- finalize
  "Assemble an index from a finished cell->region array."
  [^long width ^long height ^ints cell->region ^doubles costs]
  (let [graph (build-graph cell->region costs width height)]
    {:width width :height height :chunk-size chunk-size
     :cell->region cell->region
     :regions graph
     :region->component (build-components graph)
     :count (count graph)}))

(defn- index
  "Full chunked region index from a PathGrid (NO cache). Used for the cold full
   build and as the from-scratch oracle in tests."
  [pg]
  (let [width  (long (:width pg))
        height (long (:height pg))
        costs  ^doubles (:costs pg)]
    (finalize width height (build-cell->region width height costs) costs)))

(defn- dirty-chunks
  "Set of [cx cy] chunks containing at least one cell whose cost differs between
   `prev` and `costs`. O(cells) linear compare (cold path only)."
  [^doubles prev ^doubles costs ^long width]
  (let [n (alength costs)]
    (loop [i 0 acc (transient #{})]
      (if (< i n)
        (recur (inc i)
               (if (== (aget prev i) (aget costs i))
                 acc
                 (conj! acc [(quot (rem i width) chunk-size)
                             (quot (quot i width) chunk-size)])))
        (persistent! acc)))))

(defn- update-cell->region
  "Clone `prev` and re-flood ONLY the dirty chunks. Untouched chunks keep their
   (stable, chunk-canonical) ids unchanged."
  [prev width height costs dirty]
  (let [^ints   arr    (aclone ^ints prev)
        ^doubles costs costs
        width  (long width)
        height (long height)
        nxc    (n-chunks width)]
    (doseq [[cx cy] dirty]
      (let [cx (long cx) cy (long cy)
            x0 (* cx chunk-size) y0 (* cy chunk-size)
            x1 (min (dec (+ x0 chunk-size)) (dec width))
            y1 (min (dec (+ y0 chunk-size)) (dec height))
            base (* (chunk-index cx cy nxc) max-per-chunk)]
        (flood-chunk! arr costs width height x0 y0 x1 y1 base)))
    arr))

;; Size-1 memo keyed by PathGrid identity. Holds the cost array too, so the next
;; change can diff against it (the incremental path). A new PathGrid identity
;; is a new key -> recompute, so the cache CANNOT go stale (no false negatives).
(defonce ^:private cache (atom nil))     ; {:pathgrid pg :costs ^doubles :index index}

(defn of-pathgrid
  "Region index for a PathGrid, memoized by IDENTITY. On a miss with a
   dimension-compatible cached predecessor, re-floods only the chunks whose costs
   changed (found by diffing the cached cost array) and rebuilds the cheap graph +
   components; otherwise a full build. A new identity always recomputes, so the
   cache cannot go stale."
  [pg]
  (let [c @cache]
    (if (and c (identical? pg (:pathgrid c)))
      (:index c)
      (let [w     (long (:width pg))
            h     (long (:height pg))
            costs ^doubles (:costs pg)
            ci    (:index c)
            new-index
            (if (and c (= (:width ci) w) (= (:height ci) h))
              (let [dirty (dirty-chunks (:costs c) costs w)
                    c->r  (update-cell->region (:cell->region ci) w h costs dirty)]
                (finalize w h c->r costs))
              (index pg))]
        (reset! cache {:pathgrid pg :costs costs :index new-index})
        new-index))))

(defn of-grid
  "Region index for a terrain-only grid (no buildings). Convenience for tests and
   terrain inspection; production reachability goes through of-pathgrid/for-world."
  [grid]
  (of-pathgrid (pathgrid/build grid [])))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn region-at
  "Region node id at (x, y), or -1 if impassable / out of bounds."
  ^long [{:keys [width height cell->region]} x y]
  (if (tile/in-bounds? width height x y)
    (aget ^ints cell->region (tile/idx width x y))
    -1))

(defn count-regions
  "Number of region NODES (chunk-bounded pieces) in the index. NOTE: not the
   number of connected components; on a map <= one chunk the two coincide."
  ^long [index]
  (:count index))

(defn reachable?
  "True iff a and b are both passable AND in the same connected COMPONENT of
   `world`'s PathGrid (terrain + buildings): i.e. A* would find a path. O(1):
   two region lookups + a component-id compare."
  [world [ax ay] [bx by]]
  (let [idx (of-pathgrid (pathgrid/for-world world))
        ra  (region-at idx ax ay)
        rb  (region-at idx bx by)]
    ;; INVARIANT: any rid >= 0 in :cell->region is seeded into :regions by
    ;; build-graph pass 1, and build-components maps every (keys regions). So a
    ;; non-negative ra/rb is always present in :region->component, and the get
    ;; never returns nil (no NPE on the long coercion). The >= 0 guards below are
    ;; the only gate needed.
    (and (>= ra 0) (>= rb 0)
         (== (long (get (:region->component idx) ra))
             (long (get (:region->component idx) rb))))))
