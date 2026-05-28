(ns sim.render.layers.pawns
  "Pawns layer: each pawn drawn as a 32px sprite at its (interpolated) position.

   The draw position comes from sim.render.interp/draw-pos: a settled pawn snaps
   to its cell, a walking one GLIDES between cells (same Y-flip as terrain). The
   glide is a render-only float — the sim keeps integer ticks.

   Selection feedback is now the world-space box (sim.render.layers.selection),
   uniform across all selectable kinds — so pawns always draw untinted. Resets
   the batch tint to white when done so later draws aren't affected."
  (:require
   [sim.entity         :as entity]
   [sim.render.interp  :as interp]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color)
   (com.badlogic.gdx.graphics.g2d SpriteBatch TextureRegion)))

(set! *warn-on-reflection* true)

(defn draw
  "Render pawns in WORLD coordinates, gliding between cells while they walk."
  [world ^SpriteBatch batch tile-size]
  (let [height (:height (:grid world))
        ts     (long tile-size)
        ^TextureRegion region (sprites/pawn-region)]
    (doseq [pawn (entity/pawns world)]
      (when (:pos pawn)
        (let [[px py] (interp/draw-pos pawn ts height)]
          (.setColor batch Color/WHITE)
          (.draw batch region (float px) (float py) (float ts) (float ts)))))
    (.setColor batch Color/WHITE)))
