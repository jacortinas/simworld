(ns sim.render.layers.terrain
  "Terrain layer: per tile, a base color quad (the terrain's :color) then the
   detail sprite on top, resolved through the terrain def's :graphic. Transparent
   '(no bg)' sprites let the base show; opaque ones cover it. Pure function of
   (world, batch, tile-size, pixel, now-ms); now-ms lets an animated terrain
   graphic (water) pick its frame, a render lie on real-time."
  (:require
   [sim.tile :as tile]
   [sim.defs :as defs]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  [world ^SpriteBatch batch tile-size ^Texture pixel now-ms]
  (let [grid   (:grid world)
        width  (long (:width grid))
        height (long (:height grid))
        ts     (long tile-size)]
    (dotimes [y height]
      (dotimes [x width]
        (let [t       (tile/tile-at grid x y)
              px      (* x ts)
              py      (* (- height y 1) ts)
              [r g b] (tile/terrain-color t)]
          ;; 1. base color quad (terrain's :color), via the 1px pixel texture
          (.setColor batch (float r) (float g) (float b) (float 1.0))
          (.draw batch pixel (float px) (float py) (float ts) (float ts))
          ;; 2. detail sprite on top, untinted, resolved through the graphic
          (sprites/draw-graphic! batch (:graphic (defs/terrain t)) [px py] ts now-ms))))))
