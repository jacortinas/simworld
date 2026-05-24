(ns sim.render.layers.flora
  "Flora layer: tree entities rendered as the tree sprite. Drawn ABOVE terrain
   and BELOW items/pawns (z: terrain < flora < items < pawns)."
  (:require
   [sim.entity :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  "Render tree entities in WORLD coordinates."
  [world ^SpriteBatch batch tile-size]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)]
    (doseq [t (entity/trees world)]
      (when-let [[x y] (:pos t)]
        (let [px (* (long x) ts)
              py (* (- height (long y) 1) ts)]
          (.draw batch (sprites/tree-region)
                 (float px) (float py) (float ts) (float ts)))))))
