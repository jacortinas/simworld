(ns sim.render.layers.items
  "Items layer: ground items rendered as their material's 32px sprite.

   Carried items (`:pos nil`) are filtered out — they live with their pawn
   visually. A carried-indicator overlay on the pawn may come later."
  (:require
   [sim.entity :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  "Render ground items in WORLD coordinates. Same bottom-left anchor and
   (height-1-y) flip as the terrain/pawn layers, so items line up with tiles
   and with sim.input/screen->tile."
  [world ^SpriteBatch batch tile-size]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)]
    (doseq [item (entity/items world)]
      (when-let [[x y] (:pos item)]
        (let [px (* (long x) ts)
              py (* (- height (long y) 1) ts)]
          (.draw batch (sprites/item-region (:material item))
                 (float px) (float py) (float ts) (float ts)))))))
