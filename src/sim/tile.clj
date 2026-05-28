(ns sim.tile
  "Tile types and grid access helpers.

   Terrain DEFS (move-cost / passable? / char) are content — they live in
   sim.defs (resources/defs/terrain.edn), not here. This namespace owns the
   grid representation and thin lookup wrappers that delegate to sim.defs.

   The grid is currently a persistent vector of tile-type keywords, indexed
   linearly as (+ x (* y width)). This is deliberate: when we later migrate to
   a primitive `long-array`, the indexing scheme is identical and the call sites
   in pathfinding / render stay the same."
  (:require
   [sim.defs :as defs]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Terrain lookups — delegate to the Def DB (sim.defs). The defs themselves
;; (move-cost / passable? / char) are content in resources/defs/terrain.edn.
;; ---------------------------------------------------------------------------

(defn terrain-info
  "Look up the static properties of a terrain type. Unknown keys fall back to
   the :grass def (the fallback lives in sim.defs/terrain)."
  [terrain-key]
  (defs/terrain terrain-key))

(defn passable?
  [terrain-key]
  (:passable? (terrain-info terrain-key)))

(defn move-cost
  ^double [terrain-key]
  (double (:move-cost (terrain-info terrain-key))))

(defn terrain-color
  "Base [r g b] render color for a terrain type (delegates to sim.defs)."
  [terrain-key]
  (defs/terrain-color terrain-key))

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
