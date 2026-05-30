(ns sim.render.layers.pawns
  "Pawns layer: each pawn drawn through its :graphic at its interpolated position,
   facing derived from its in-flight :move segment (idle faces down). Pawns draw
   untinted; selection feedback is the world-space box layer."
  (:require
   [sim.entity         :as entity]
   [sim.defs           :as defs]
   [sim.render.graphic :as graphic]
   [sim.render.interp  :as interp]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  [world ^SpriteBatch batch tile-size now-ms]
  (let [height (:height (:grid world))
        ts     (long tile-size)]
    (doseq [pawn (entity/pawns world)]
      (when (:pos pawn)
        (when-let [gr (defs/graphic (:graphic pawn))]
          (let [facing (graphic/facing-for (get-in pawn [:job :move]))]
            (when-let [region (sprites/graphic-region gr facing now-ms)]
              (let [[px py] (interp/draw-pos pawn ts height)
                    [gx gy gw gh] (graphic/draw-rect gr [px py] ts)]
                (.setColor batch Color/WHITE)
                (.draw batch region (float gx) (float gy) (float gw) (float gh))))))))
    (.setColor batch Color/WHITE)))
