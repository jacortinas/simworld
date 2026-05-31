(ns sim.render.layers.flora
  "Flora layer: tree entities drawn through their :graphic at their tile anchor.
   Z: terrain < flora < items < pawns."
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
    (doseq [t (entity/trees world)]
      (when (:pos t)
        (sprites/draw-graphic! batch (:graphic t)
                               (interp/draw-pos t ts height) ts now-ms)))))
