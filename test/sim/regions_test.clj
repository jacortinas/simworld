(ns sim.regions-test
  "Tests for connected-component region labeling. The load-bearing guarantee is
   the ORACLE PROPERTY: regions/reachable? must agree with find-path on EVERY
   start/goal pair — a false negative (reachable? false where a path exists) is
   the one unacceptable bug, because it would silently skip a real route. The
   other tests pin labeling, the corner rule, and determinism."
  (:require
   [clojure.test    :refer [deftest is testing]]
   [sim.tile        :as tile]
   [sim.regions     :as regions]
   [sim.pathfinding :as pathfinding]))

(defn- passable-at?
  [{:keys [width height] :as grid} x y]
  (and (tile/in-bounds? width height x y)
       (tile/passable? (tile/tile-at grid x y))))

;; ---------------------------------------------------------------------------
;; Labeling — a wall-split grid is two regions; cells within a room share an id,
;; cross-room ids differ. Ids are scan-order canonical, so the left room (first
;; passable cell at idx 0) is region 0. (:water is PASSABLE here — cost 2.5 — so
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
;; Corner rule — connectivity must refuse a diagonal whose flanking cardinals
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
;; Determinism — of-grid is a pure function of the grid: same grid twice yields
;; identical labelings, and ids are dense scan-order canonical (0..n-1).
;; ---------------------------------------------------------------------------

(deftest labeling-is-deterministic-and-dense
  (let [g (reduce (fn [g [x y]] (tile/set-tile g x y :wall))
                  (tile/make-grid 6 6)
                  [[2 0] [2 1] [2 2] [2 3]])           ; partial wall, several regions
        a (regions/of-grid g)
        b (regions/of-grid g)]
    (is (= (vec (:ids a)) (vec (:ids b))) "same grid -> identical labeling")
    (let [ids (remove neg? (:ids a))]
      (is (= (set ids) (set (range (regions/count-regions a))))
          "region ids are dense 0..count-1 (scan-order canonical)"))))

;; ---------------------------------------------------------------------------
;; ORACLE PROPERTY — the key correctness guarantee. Over many random grids and
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
              (is (= (regions/reachable? grid s g)
                     (some? (pathfinding/find-path {:grid grid} s g)))
                  (str "reachable? must match find-path for " label)))))))))
