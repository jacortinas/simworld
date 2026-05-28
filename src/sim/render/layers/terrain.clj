(ns sim.render.layers.terrain
  "Terrain layer: per tile, a base color quad (the terrain's :color, stamped with
   the shared 1px pixel texture) then the detail sprite on top. Transparent
   '(no bg)' sprites let the bright base show; opaque sprites (water/stone/wall)
   cover it — so one uniform draw handles every terrain, alpha does the branching.

   Pure function of (world, batch, tile-size, pixel). The batch's begin/end is
   the caller's responsibility; this layer leaves the batch tint white when done."
  (:require
   [sim.tile :as tile]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  "Render every tile in WORLD coordinates. The camera maps world→screen, so this
   layer doesn't know about viewport or scroll — it places tile [x y] at world
   pixel [x*ts, (height-1-y)*ts]. The (height-1-y) flip puts row 0 at the top
   (libGDX world space is Y-up) AND matches sim.input/screen->tile, so clicks hit
   the right tile. Each tile = a base color quad + the detail sprite."
  [world ^SpriteBatch batch tile-size ^Texture pixel]
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
          ;; 2. detail sprite on top, untinted
          (.setColor batch Color/WHITE)
          (.draw batch (sprites/terrain-region t)
                 (float px) (float py) (float ts) (float ts)))))))
