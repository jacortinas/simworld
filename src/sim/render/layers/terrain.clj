(ns sim.render.layers.terrain
  "Terrain layer: one 32px sprite per tile.

   Pure function of (world, batch, tile-size). Emits one batched draw per
   tile. The batch's begin/end and tint are the caller's responsibility — the
   caller sets the batch color to white before world layers so sprites draw
   untinted."
  (:require
   [sim.tile :as tile]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  "Render every tile in WORLD coordinates. The camera maps world→screen, so
   this layer doesn't know about viewport or scroll — it places tile [x y] at
   world pixel [x*ts, (height-y)*ts]. The (height-y) flip puts row 0 at the
   top, since libGDX world space is Y-up."
  [world ^SpriteBatch batch tile-size]
  (let [grid   (:grid world)
        width  (long (:width grid))
        height (long (:height grid))
        ts     (long tile-size)]
    (dotimes [y height]
      (dotimes [x width]
        (let [t  (tile/tile-at grid x y)
              px (* x ts)
              ;; Sprites anchor at their BOTTOM-left and draw upward (unlike
              ;; font glyphs, which anchored at the top and drew down). So we
              ;; use (height-1-y), which both puts row 0 at the top AND matches
              ;; sim.input/screen->tile — otherwise clicks miss by one row.
              py (* (- height y 1) ts)]
          (.draw batch (sprites/terrain-region t)
                 (float px) (float py) (float ts) (float ts)))))))
