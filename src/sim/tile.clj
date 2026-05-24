(ns sim.tile
  "Tile types, terrain definitions, and grid access helpers.

   The grid is currently a persistent vector of tile-type keywords, indexed
   linearly as (+ x (* y width)). This is deliberate: when we later migrate to
   a primitive `long-array`, the indexing scheme is identical and the call sites
   in pathfinding / render stay the same.")

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Terrain definitions
;;
;; :move-cost is a multiplier on traversal cost — 1.0 = baseline, higher = slower.
;; :passable? false means pathfinding will refuse to route through this tile.
;;
;; The values below are placeholders modeled loosely on RimWorld's terrain
;; modifiers (gravel ~87%, marsh ~36%, paved 100%). Tune at will.
;; ---------------------------------------------------------------------------

(def terrain
  {:grass    {:char \. :move-cost 1.0  :passable? true}
   :dirt     {:char \, :move-cost 1.15 :passable? true}
   :gravel   {:char \: :move-cost 1.30 :passable? true}
   :stone    {:char \# :move-cost 1.0  :passable? false}
   :water    {:char \~ :move-cost 2.5  :passable? true}
   :wall     {:char \# :move-cost 1.0  :passable? false}})

(defn terrain-info
  "Look up the static properties of a terrain type."
  [terrain-key]
  (get terrain terrain-key (terrain :grass)))

(defn passable?
  [terrain-key]
  (:passable? (terrain-info terrain-key)))

(defn move-cost
  ^double [terrain-key]
  (double (:move-cost (terrain-info terrain-key))))

;; ---------------------------------------------------------------------------
;; Grid access — linearized index lets us swap in long-array later
;; ---------------------------------------------------------------------------

(defn idx
  "Convert (x, y) to a linear index into the grid."
  ^long [^long width ^long x ^long y]
  (+ x (* y width)))

(defn in-bounds?
  [^long width ^long height ^long x ^long y]
  (and (>= x 0) (< x width)
       (>= y 0) (< y height)))

(defn tile-at
  "Get the terrain keyword at (x, y). Returns nil if out of bounds."
  [{:keys [width height tiles] :as _grid} x y]
  (when (in-bounds? width height x y)
    (nth tiles (idx width x y))))

(defn set-tile
  "Return a new grid with (x, y) set to terrain-key. Out-of-bounds writes are
   silently ignored — callers should check bounds if it matters."
  [{:keys [width height tiles] :as grid} x y terrain-key]
  (if (in-bounds? width height x y)
    (assoc grid :tiles (assoc tiles (idx width x y) terrain-key))
    grid))

;; ---------------------------------------------------------------------------
;; Grid construction
;; ---------------------------------------------------------------------------

(defn make-grid
  "Build a grid of the given dimensions, filled with terrain-key (default :grass)."
  ([width height]
   (make-grid width height :grass))
  ([^long width ^long height terrain-key]
   {:width  width
    :height height
    :tiles  (vec (repeat (* width height) terrain-key))}))

(defn neighbors-4
  "Return the in-bounds 4-connected neighbors of (x, y) as [[x y] ...]."
  [{:keys [width height]} ^long x ^long y]
  (into []
        (filter (fn [[nx ny]] (in-bounds? width height nx ny)))
        [[(inc x) y] [(dec x) y] [x (inc y)] [x (dec y)]]))

(defn neighbors-8
  "Return the in-bounds 8-connected neighbors of (x, y) as [[x y] ...]."
  [{:keys [width height]} ^long x ^long y]
  (into []
        (filter (fn [[nx ny]] (in-bounds? width height nx ny)))
        (for [dx [-1 0 1] dy [-1 0 1]
              :when (not (and (zero? dx) (zero? dy)))]
          [(+ x dx) (+ y dy)])))
