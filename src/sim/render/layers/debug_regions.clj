(ns sim.render.layers.debug-regions
  "Translucent per-region tint (gated :debug-regions?). region->color maps a
   region id to a stable hue; the GL fill mirrors layers/zones.clj and is not
   unit-tested."
  (:require
   [sim.pathgrid :as pathgrid]
   [sim.regions  :as regions]
   [sim.tile     :as tile]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn region->color
  "Stable low-alpha [r g b a] for region id `n` (golden-ratio hue hash).
   Every channel is in [0.0, 1.0]."
  [n]
  (let [h  (mod (* (long n) 0.61803398875) 1.0)
        c1 (Math/abs (- (* 6.0 (mod h 1.0)) 3.0))
        c2 (Math/abs (- (* 6.0 (mod (+ h 0.33) 1.0)) 3.0))
        c3 (Math/abs (- (* 6.0 (mod (+ h 0.66) 1.0)) 3.0))]
    [(min 1.0 (max 0.0 (- c1 1.0)))
     (min 1.0 (max 0.0 (- 2.0 c2)))
     (min 1.0 (max 0.0 (- 2.0 c3)))
     0.18]))

(defn draw
  "For each in-bounds passable cell, fill its rect with (region->color id), when
   :debug-regions? is on. Resets the batch tint to white when done."
  [world ^SpriteBatch batch ts ^Texture pixel]
  (when (ui/debug-regions?)
    (let [pg     (pathgrid/for-world world)
          ri     (regions/of-pathgrid pg)
          width  (long (:width (:grid world)))
          height (long (:height (:grid world)))]
      (dotimes [x width]
        (dotimes [y height]
          (let [id (regions/region-at ri x y)]
            (when (>= id 0)
              (let [[r g b a] (region->color id)
                    px  (* (double x) (double ts))
                    py  (* (double (- height 1 y)) (double ts))]
                (.setColor batch (float r) (float g) (float b) (float a))
                (.draw batch pixel (float px) (float py) (float ts) (float ts)))))))
      (.setColor batch Color/WHITE))))
