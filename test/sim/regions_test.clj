(ns sim.regions-test
  "Tests for connected-component region labeling. The load-bearing guarantee is
   the ORACLE PROPERTY: regions/reachable? must agree with find-path on EVERY
   start/goal pair: a false negative (reachable? false where a path exists) is
   the one unacceptable bug, because it would silently skip a real route. The
   other tests pin labeling, the corner rule, and determinism."
  (:require
   [clojure.test    :refer [deftest is testing]]
   [sim.tile        :as tile]
   [sim.entity      :as entity]
   [sim.pathgrid    :as pathgrid]
   [sim.regions     :as regions]
   [sim.pathfinding :as pathfinding]))

(defn- passable-at?
  [{:keys [width height] :as grid} x y]
  (and (tile/in-bounds? width height x y)
       (tile/passable? (tile/tile-at grid x y))))

;; ---------------------------------------------------------------------------
;; Labeling: a wall-split grid is two regions; cells within a room share an id,
;; cross-room ids differ. Ids are scan-order canonical, so the left room (first
;; passable cell at idx 0) is region 0. (:water is PASSABLE here, cost 2.5, so
;; the divider must be a genuinely impassable terrain like :wall.)
;; ---------------------------------------------------------------------------

(deftest two-rooms-split-by-a-wall-are-two-regions
  (testing "a full-height impassable column cleaves the grid into two components"
    (let [g   (reduce (fn [g y] (tile/set-tile g 2 y :wall))
                      (tile/make-grid 5 4)            ; column x=2 impassable
                      (range 4))
          idx (regions/of-grid g)]
      (is (= 2 (regions/count-regions idx)) "exactly two rooms")
      (is (= 0 (regions/region-at idx 0 0)) "scan-order canonical: left room = 0")
      (is (= (regions/region-at idx 0 0) (regions/region-at idx 1 3))
          "all left-room cells share an id")
      (is (= (regions/region-at idx 3 0) (regions/region-at idx 4 3))
          "all right-room cells share an id")
      (is (not= (regions/region-at idx 1 1) (regions/region-at idx 3 1))
          "across the wall, ids differ")
      (is (= -1 (regions/region-at idx 2 1)) "the wall cell itself is -1"))))

(deftest impassable-and-oob-cells-are-minus-one
  (let [idx (regions/of-grid (tile/set-tile (tile/make-grid 3 3) 1 1 :wall))]
    (is (= -1 (regions/region-at idx 1 1)) "wall cell")
    (is (= -1 (regions/region-at idx -1 0)) "out of bounds")
    (is (= -1 (regions/region-at idx 0 99)) "out of bounds")))

;; ---------------------------------------------------------------------------
;; Corner rule: connectivity must refuse a diagonal whose flanking cardinals
;; are both impassable, EXACTLY as find-path does (lines 173-175). Otherwise
;; regions would connect cells A* cannot step between → could mask a path.
;; ---------------------------------------------------------------------------

(deftest diagonal-pinch-between-two-walls-does-not-connect
  (testing "(0,0) is sealed off: both its cardinal exits are walls and the
            diagonal to (1,1) is a corner cut, so it is its own region"
    (let [g   (-> (tile/make-grid 3 3)
                  (tile/set-tile 1 0 :wall)
                  (tile/set-tile 0 1 :wall))
          idx (regions/of-grid g)]
      (is (not= (regions/region-at idx 0 0) (regions/region-at idx 1 1))
          "the corner-cut diagonal must NOT join the two cells")
      (is (nil? (pathfinding/find-path {:grid g} [0 0] [1 1]))
          "and find-path agrees there is no route"))))

;; ---------------------------------------------------------------------------
;; Determinism: of-grid is a pure function of the grid: same grid twice yields
;; identical labelings, and ids are dense scan-order canonical (0..n-1).
;; ---------------------------------------------------------------------------

(deftest labeling-is-deterministic-and-dense
  (let [g (reduce (fn [g [x y]] (tile/set-tile g x y :wall))
                  (tile/make-grid 6 6)
                  [[2 0] [2 1] [2 2] [2 3]])           ; partial wall, several regions
        a (regions/of-grid g)
        b (regions/of-grid g)]
    (is (= (vec (:cell->region a)) (vec (:cell->region b))) "same grid -> identical labeling")
    ;; NOTE: dense 0..count-1 is a SINGLE-CHUNK property only (6x6 < 12, one
    ;; chunk, so rid = 0*144 + local). A multi-chunk grid has id gaps between
    ;; per-chunk ranges; this assertion would not hold there.
    (let [ids (remove neg? (:cell->region a))]
      (is (= (set ids) (set (range (regions/count-regions a))))
          "region ids are dense 0..count-1 within a single chunk (scan-order canonical)"))))

;; ---------------------------------------------------------------------------
;; ORACLE PROPERTY: the key correctness guarantee. Over many random grids and
;; start/goal pairs, reachable? must equal (some? (find-path ...)). find-path is
;; the oracle (already validated against an independent Dijkstra in
;; pathfinding-test). A false negative here would be the unacceptable bug.
;; ---------------------------------------------------------------------------

(deftest reachable-matches-find-path-on-random-grids
  (testing "regions/reachable? <=> find-path succeeds, for every passable pair"
    (let [rng      (java.util.Random. 987654321)
          ri       (fn [n] (.nextInt rng (int n)))
          terrains [:grass :grass :grass :dirt :gravel :water :wall :stone]]
      (dotimes [_ 300]
        (let [w     (+ 4 (ri 7))                       ; 4..10
              h     (+ 4 (ri 7))
              cells (vec (repeatedly (* w h) #(nth terrains (ri (count terrains)))))
              grid  {:width w :height h :tiles cells}
              p-idx (filterv #(passable-at? grid (mod % w) (quot % w)) (range (* w h)))]
          (when (seq p-idx)
            (let [pick  #(let [i (nth p-idx (ri (count p-idx)))] [(mod i w) (quot i w)])
                  s     (pick)
                  g     (pick)
                  label (str s "->" g " on " w "x" h)]
              (is (= (regions/reachable? {:grid grid} s g)
                     (some? (pathfinding/find-path {:grid grid} s g)))
                  (str "reachable? must match find-path for " label)))))))))

;; ---------------------------------------------------------------------------
;; ORACLE PROPERTY WITH BUILDINGS: the same guard, now with runtime walls.
;; Walls are :building entities added through the chokepoint, so the PathGrid
;; (and thus both regions/reachable? and find-path) reflect them. A false
;; negative once walls block routes is the unacceptable bug this fuzzes against.
;; ---------------------------------------------------------------------------

(deftest reachable-matches-find-path-with-buildings
  (testing "with random wall buildings added through the chokepoint, reachable?
            still <=> find-path succeeds, for every passable pair"
    (let [rng      (java.util.Random. 424242)
          ri       (fn [n] (.nextInt rng (int n)))
          terrains [:grass :grass :dirt :gravel :water :stone]]
      (dotimes [_ 200]
        (let [w     (+ 4 (ri 6))
              h     (+ 4 (ri 6))
              cells (vec (repeatedly (* w h) #(nth terrains (ri (count terrains)))))
              grid  {:width w :height h :tiles cells}
              base  {:grid grid :entities {} :kinds (entity/empty-kinds)}
              ;; add a handful of wall buildings on passable, distinct cells
              world (reduce (fn [wd _]
                              (let [x (ri w) y (ri h)]
                                (if (passable-at? (:grid wd) x y)
                                  (entity/add-entity wd (entity/make-building [x y]))
                                  wd)))
                            base
                            (range (ri 6)))
              p-idx (filterv #(pathgrid/passable? (pathgrid/for-world world) (mod % w) (quot % w))
                             (range (* w h)))]
          (when (seq p-idx)
            (let [pick #(let [i (nth p-idx (ri (count p-idx)))] [(mod i w) (quot i w)])
                  s    (pick) g (pick)
                  label (str s "->" g " on " w "x" h)]
              (is (= (regions/reachable? world s g)
                     (some? (pathfinding/find-path world s g)))
                  (str "reachable? must match find-path for " label)))))))))

;; ---------------------------------------------------------------------------
;; PORTAL REGIONS (doors): a door is its OWN 1-cell region (a flood barrier), yet
;; the two sides it separates stay REACHABLE through it (graph-linked, one
;; component). Contrast a wall in the same doorway, which disconnects them. This
;; is the substrate rooms (Spec 3) read: the portal region is the flood-stop.
;; ---------------------------------------------------------------------------

(defn- doorway-world
  "A 3x3 cell with walls top and bottom, leaving floor(0,1)-X-floor(2,1) where X
   is the building placed by `make` at (1,1)."
  [make]
  (let [grid (reduce (fn [g x] (-> g (tile/set-tile x 0 :wall) (tile/set-tile x 2 :wall)))
                     (tile/make-grid 3 3)
                     (range 3))]
    (-> {:grid grid :entities {} :kinds (entity/empty-kinds)}
        (entity/add-entity (make [1 1])))))

(deftest door-is-its-own-region-but-keeps-both-sides-reachable
  (testing "a door at (1,1) is a distinct region from the floors it joins, yet
            left and right stay reachable through it (one component)"
    (reset! @#'regions/cache nil)
    (let [world (doorway-world entity/make-door)
          idx   (regions/of-pathgrid (pathgrid/for-world world))
          left  (regions/region-at idx 0 1)
          door  (regions/region-at idx 1 1)
          right (regions/region-at idx 2 1)]
      (is (>= door 0) "the door cell is passable, so it has a real region id")
      (is (not= left door)  "the door is its own region, not part of the left floor")
      (is (not= right door) "the door is its own region, not part of the right floor")
      (is (not= left right) "barriered flood: the two floors are separate regions")
      (is (regions/reachable? world [0 1] [2 1])
          "but you can still path through the door: same component"))))

(deftest wall-in-the-same-doorway-disconnects-the-sides
  (testing "swapping the door for a wall (the contrast case) DOES cut reachability"
    (reset! @#'regions/cache nil)
    (let [world (doorway-world entity/make-building)]
      (is (not (regions/reachable? world [0 1] [2 1]))
          "a wall in the doorway leaves the two floors in different components"))))

;; ---------------------------------------------------------------------------
;; ORACLE PROPERTY WITH DOORS: doors are passable, so find-path routes THROUGH
;; them; reachable? must agree. Scatter random walls AND doors on multi-chunk
;; grids (portals at chunk seams included) and assert reachable? <=> find-path
;; for every passable pair, INCLUDING pairs whose endpoints land on a door cell.
;; A portal that wrongly split a component would surface here as a false negative.
;; ---------------------------------------------------------------------------

(deftest reachable-matches-find-path-with-doors
  (testing "with random walls + doors, reachable? still <=> find-path"
    (reset! @#'regions/cache nil)
    (let [rng      (java.util.Random. 13371337)
          ri       (fn [n] (.nextInt rng (int n)))
          terrains [:grass :grass :dirt :gravel :stone]]
      (dotimes [_ 150]
        (let [w     (+ 12 (ri 14))                    ; 12..25 -> 1..3 chunks
              h     (+ 12 (ri 14))
              cells (vec (repeatedly (* w h) #(nth terrains (ri (count terrains)))))
              grid  {:width w :height h :tiles cells}
              base  {:grid grid :entities {} :kinds (entity/empty-kinds)}
              ;; scatter walls and doors on passable, distinct cells; each cell
              ;; gets at most one building (can-build? equivalent: skip occupied)
              world (reduce (fn [wd _]
                              (let [x (ri w) y (ri h)]
                                (if (and (passable-at? (:grid wd) x y)
                                         (not (pathgrid/portal? (pathgrid/for-world wd) x y))
                                         (pathgrid/passable? (pathgrid/for-world wd) x y))
                                  (entity/add-entity wd (if (zero? (ri 2))
                                                          (entity/make-building [x y])
                                                          (entity/make-door [x y])))
                                  wd)))
                            base
                            (range (ri 50)))           ; up to ~50 buildings
              p-idx (filterv #(pathgrid/passable? (pathgrid/for-world world) (mod % w) (quot % w))
                             (range (* w h)))]
          (when (seq p-idx)
            (dotimes [_ 6]
              (let [pick #(let [i (nth p-idx (ri (count p-idx)))] [(mod i w) (quot i w)])
                    s    (pick) g (pick)
                    label (str s "->" g " on " w "x" h)]
                (is (= (regions/reachable? world s g)
                       (some? (pathfinding/find-path world s g)))
                    (str "reachable? must match find-path for " label))))))))))

;; ---------------------------------------------------------------------------
;; CHUNKED MODEL: a region is bounded to a 12x12 chunk, so a fully-open map
;; larger than one chunk has MULTIPLE region nodes but ONE component. count-regions
;; counts NODES; reachable? compares COMPONENTS.
;; ---------------------------------------------------------------------------

(deftest open-map-splits-into-chunk-bounded-region-nodes
  (testing "a 24x24 open grid = 4 chunk region nodes, 1 component"
    (let [idx (regions/of-grid (tile/make-grid 24 24))]
      (is (= 4 (regions/count-regions idx)) "2x2 chunks -> 4 region nodes")
      (is (not= (regions/region-at idx 11 0) (regions/region-at idx 12 0))
          "cells on opposite sides of the x=12 chunk seam are different region nodes")
      (is (regions/reachable? {:grid (tile/make-grid 24 24)} [11 0] [12 0])
          "but they are reachable (same component)")
      (is (regions/reachable? {:grid (tile/make-grid 24 24)} [0 0] [23 23])
          "the whole open map is one connected component"))))

;; ---------------------------------------------------------------------------
;; ORACLE PROPERTY, MULTI-CHUNK: the chunked graph + component model must STILL
;; agree with find-path, now on grids large enough to span several chunks with
;; runtime walls. A chunk-seam or corner-rule mismatch would surface as a false
;; negative here. THE load-bearing guard for the swap.
;; ---------------------------------------------------------------------------

(deftest reachable-matches-find-path-multichunk
  (testing "reachable? <=> find-path on multi-chunk grids with random walls"
    (let [rng      (java.util.Random. 20260602)
          ri       (fn [n] (.nextInt rng (int n)))
          terrains [:grass :grass :dirt :gravel :water :stone]]
      (dotimes [_ 120]
        (let [w     (+ 14 (ri 17))                    ; 14..30 -> 2..3 chunks
              h     (+ 14 (ri 17))
              cells (vec (repeatedly (* w h) #(nth terrains (ri (count terrains)))))
              grid  {:width w :height h :tiles cells}
              base  {:grid grid :entities {} :kinds (entity/empty-kinds)}
              world (reduce (fn [wd _]
                              (let [x (ri w) y (ri h)]
                                (if (passable-at? (:grid wd) x y)
                                  (entity/add-entity wd (entity/make-building [x y]))
                                  wd)))
                            base
                            (range (ri 40)))           ; up to ~40 walls
              p-idx (filterv #(pathgrid/passable? (pathgrid/for-world world) (mod % w) (quot % w))
                             (range (* w h)))]
          (when (seq p-idx)
            (dotimes [_ 6]                             ; several pairs per world
              (let [pick #(let [i (nth p-idx (ri (count p-idx)))] [(mod i w) (quot i w)])
                    s    (pick) g (pick)
                    label (str s "->" g " on " w "x" h)]
                (is (= (regions/reachable? world s g)
                       (some? (pathfinding/find-path world s g)))
                    (str "reachable? must match find-path for " label))))))))))

;; ---------------------------------------------------------------------------
;; INCREMENTAL == FROM-SCRATCH: applying a sequence of wall add/remove ops and
;; reading the (incrementally-updated) cache must produce the IDENTICAL index a
;; fresh full build would, and identical reachable? everywhere. This is the guard
;; that the incremental path never diverges from a clean rebuild.
;; ---------------------------------------------------------------------------

(defn- fresh-index
  "Bypass the cache: full chunked build straight from a world's PathGrid."
  [world]
  (#'regions/index (pathgrid/for-world world)))

(deftest incremental-matches-from-scratch
  (testing "the cached/incremental index equals a fresh full build after edits"
    (reset! @#'regions/cache nil)                      ; isolate from other tests
    (let [rng   (java.util.Random. 777)
          ri    (fn [n] (.nextInt rng (int n)))
          w 20 h 20
          grid  (tile/make-grid w h)
          base  {:grid grid :entities {} :kinds (entity/empty-kinds)}]
      (loop [world base, n 0]
        (when (< n 25)
          ;; toggle a random wall (place if buildable, else try deconstruct)
          (let [x (ri w) y (ri h)
                existing (first (filter #(= [x y] (:pos %))
                                        (vals (:entities world))))
                world' (if existing
                         (entity/remove-entity world (:id existing))
                         (if (pathgrid/passable? (pathgrid/for-world world) x y)
                           (entity/add-entity world (entity/make-building [x y]))
                           world))
                inc-idx   (regions/of-pathgrid (pathgrid/for-world world'))   ; incremental
                full-idx  (fresh-index world')]                               ; from scratch
            (is (= (vec (:cell->region inc-idx)) (vec (:cell->region full-idx)))
                (str "cell->region must match a fresh build after edit " n))
            (is (= (:region->component inc-idx) (:region->component full-idx))
                (str "components must match a fresh build after edit " n))
            (recur world' (inc n))))))))

(deftest incremental-matches-from-scratch-with-doors
  (testing "the incremental index equals a fresh build when toggling walls AND
            doors. A door changes only the portal bit (cost is unchanged), so this
            specifically guards that dirty-chunks diffs portals, not just costs."
    (reset! @#'regions/cache nil)
    (let [rng   (java.util.Random. 909090)
          ri    (fn [n] (.nextInt rng (int n)))
          w 20 h 20
          grid  (tile/make-grid w h)
          base  {:grid grid :entities {} :kinds (entity/empty-kinds)}]
      (loop [world base, n 0]
        (when (< n 30)
          (let [x (ri w) y (ri h)
                existing (first (filter #(= [x y] (:pos %)) (vals (:entities world))))
                world' (if existing
                         (entity/remove-entity world (:id existing))
                         (if (pathgrid/passable? (pathgrid/for-world world) x y)
                           (entity/add-entity world (if (zero? (ri 2))
                                                      (entity/make-building [x y])
                                                      (entity/make-door [x y])))
                           world))
                inc-idx  (regions/of-pathgrid (pathgrid/for-world world'))   ; incremental
                full-idx (fresh-index world')]                               ; from scratch
            (is (= (vec (:cell->region inc-idx)) (vec (:cell->region full-idx)))
                (str "cell->region must match a fresh build after edit " n))
            (is (= (:region->component inc-idx) (:region->component full-idx))
                (str "components must match a fresh build after edit " n))
            (recur world' (inc n))))))))

;; ---------------------------------------------------------------------------
;; HISTORY-INDEPENDENCE: the same final PathGrid reached two different ways (one
;; set of walls placed in different orders) yields identical reachability. Region
;; labeling is a pure function of the PathGrid, not of edit history.
;; ---------------------------------------------------------------------------

(deftest labeling-is-history-independent
  (testing "two edit orders to the same wall set give identical reachable?"
    (reset! @#'regions/cache nil)
    (let [w 26 h 18
          grid  (tile/make-grid w h)
          base  {:grid grid :entities {} :kinds (entity/empty-kinds)}
          walls [[5 5] [6 5] [7 5] [7 6] [7 7] [13 0] [13 1] [13 2] [20 10] [20 11]]
          build-all (fn [order]
                      (reduce (fn [wd p] (entity/add-entity wd (entity/make-building p)))
                              base order))
          wa (build-all walls)
          wb (build-all (reverse walls))
          pairs (for [x [0 6 8 14 21 25] y [0 6 8 12 17]] [x y])]
      (doseq [s pairs g pairs]
        (is (= (regions/reachable? wa s g) (regions/reachable? wb s g))
            (str "reachable? must be order-independent for " s "->" g))))))

;; ---------------------------------------------------------------------------
;; SPLIT / MERGE across a chunk boundary, exercised through the incremental path:
;; a wall column that seals a 1-wide corridor at the chunk seam disconnects two
;; ends; removing it reconnects them.
;; ---------------------------------------------------------------------------

(deftest wall-across-corridor-splits-then-rejoins
  (testing "sealing a corridor at a chunk seam toggles reachability"
    (reset! @#'regions/cache nil)
    (let [w 26 h 3
          ;; a 26x3 grid: walls on rows y=0 and y=2 leave a single open corridor
          ;; at y=1 that crosses the x=12 chunk seam.
          grid (reduce (fn [g x] (-> g (tile/set-tile x 0 :wall) (tile/set-tile x 2 :wall)))
                       (tile/make-grid w h)
                       (range w))
          base {:grid grid :entities {} :kinds (entity/empty-kinds)}
          left  [0 1]
          right [25 1]]
      (is (regions/reachable? base left right) "corridor open: ends reachable")
      ;; drop a wall mid-corridor (at the chunk seam x=12)
      (let [sealed (entity/add-entity base (entity/make-building [12 1]))]
        (is (not (regions/reachable? sealed left right))
            "wall in the corridor disconnects the two ends")
        ;; remove it -> reconnect
        (let [wall   (first (filter #(= [12 1] (:pos %)) (vals (:entities sealed))))
              opened (entity/remove-entity sealed (:id wall))]
          (is (regions/reachable? opened left right)
              "removing the wall rejoins the corridor"))))))
