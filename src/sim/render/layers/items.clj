(ns sim.render.layers.items
  "Items layer: ground items drawn through their :graphic at their tile anchor.
   Carried items (:pos nil) are filtered out."
  (:require
   [sim.entity :as entity]
   [sim.render.interp :as interp]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  [world ^SpriteBatch batch tile-size now-ms]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)]
    (doseq [item (entity/items world)]
      (when (:pos item)
        (sprites/draw-graphic! batch (:graphic item)
                               (interp/draw-pos item ts height) ts now-ms)))))
