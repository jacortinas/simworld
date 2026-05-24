(ns gridnoise.grid
  "Generic 2D grid — game-agnostic. A grid is {:width :height :cells [...]},
   linearized as (+ x (* y width)). Cells are any value (doubles for a noise
   field, booleans for a CA mask, keywords for terrain). All ops take the grid
   map (so they read :width/:height), unlike sim.tile's raw-width helpers.")

(set! *warn-on-reflection* true)

(defn idx
  "Linear index of (x,y)."
  ^long [{:keys [width]} ^long x ^long y]
  (+ x (* y (long width))))

(defn in-bounds?
  [{:keys [width height]} x y]
  (and (>= x 0) (< x width) (>= y 0) (< y height)))

(defn cell-at
  "Cell value at (x,y), or nil if out of bounds."
  [{:keys [cells] :as g} x y]
  (when (in-bounds? g x y)
    (nth cells (idx g x y))))

(defn neighbors-8
  "In-bounds 8-connected neighbor coords of (x,y) as [[x y] ...]."
  [g x y]
  (vec (for [dx [-1 0 1] dy [-1 0 1]
             :when (not (and (zero? dx) (zero? dy)))
             :let  [nx (+ x dx) ny (+ y dy)]
             :when (in-bounds? g nx ny)]
         [nx ny])))

(defn generate
  "Build a grid by calling (f x y) for each cell, row-major."
  [width height f]
  {:width  width
   :height height
   :cells  (vec (for [y (range height) x (range width)] (f x y)))})

(defn map-cells
  "Return the grid with f applied to every cell value."
  [g f]
  (assoc g :cells (mapv f (:cells g))))
