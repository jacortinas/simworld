(ns sim.render.layers.pawns
  "Pawns layer: each pawn drawn through its :graphic at its interpolated position,
   facing derived from its in-flight :move segment (idle faces down). Pawns draw
   untinted; selection feedback is the world-space box layer."
  (:require
   [sim.entity         :as entity]
   [sim.render.graphic :as graphic]
   [sim.render.interp  :as interp]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  [world ^SpriteBatch batch tile-size now-ms]
  (let [height (:height (:grid world))
        ts     (long tile-size)]
    (doseq [pawn (entity/pawns world)]
      (when (:pos pawn)
        (sprites/draw-graphic! batch (:graphic pawn)
                               (interp/draw-pos pawn ts height) ts now-ms
                               (graphic/facing-for (get-in pawn [:job :move])))))))
