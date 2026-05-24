(ns gridnoise.image
  "Render a grid to a grayscale PNG — standalone 'see the noise' tooling.
   Game-agnostic; uses only java.awt/javax.imageio (no display needed,
   safe headless). Default cell->gray expects cells in [0,1]."
  (:require [gridnoise.grid :as grid])
  (:import (java.awt.image BufferedImage)
           (javax.imageio ImageIO)
           (java.io File)))

(set! *warn-on-reflection* true)

(defn- unit->gray ^long [^double v]
  (long (Math/round (* 255.0 (max 0.0 (min 1.0 v))))))

(defn field->image
  "Render grid `g` to a BufferedImage. `cell->gray` maps a cell to a 0..255
   gray level; defaults to treating cells as doubles in [0,1]."
  (^BufferedImage [g] (field->image g (fn [c] (unit->gray (double c)))))
  (^BufferedImage [{:keys [width height] :as g} cell->gray]
   (let [img (BufferedImage. (int width) (int height) BufferedImage/TYPE_INT_RGB)]
     (dotimes [y height]
       (dotimes [x width]
         (let [gr  (int (cell->gray (grid/cell-at g x y)))
               rgb (bit-or (bit-shift-left gr 16) (bit-shift-left gr 8) gr)]
           (.setRGB img (int x) (int y) (int rgb)))))
     img)))

(defn spit-png!
  "Write grid `g` to `path` as a PNG. Returns the path."
  ([path g] (spit-png! path g (fn [c] (unit->gray (double c)))))
  ([path g cell->gray]
   (ImageIO/write (field->image g cell->gray) "png" (File. (str path)))
   path))
