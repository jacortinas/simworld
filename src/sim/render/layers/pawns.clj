(ns sim.render.layers.pawns
  "Pawns layer: each pawn drawn as a 32px sprite at its tile.

   Selection feedback is now the world-space box (sim.render.layers.selection),
   uniform across all selectable kinds — so pawns always draw untinted. Resets
   the batch tint to white when done so later draws aren't affected."
  (:require
   [sim.entity :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color)
   (com.badlogic.gdx.graphics.g2d SpriteBatch TextureRegion)))

(set! *warn-on-reflection* true)

(defn draw
  "Render pawns in WORLD coordinates. Same Y-flip convention as terrain."
  [world ^SpriteBatch batch tile-size]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)
        ^TextureRegion region (sprites/pawn-region)]
    (doseq [pawn (entity/pawns world)]
      (when-let [[x y] (:pos pawn)]
        (let [px (* (long x) ts)
              ;; bottom-left anchor; (height-1-y) matches screen->tile (see terrain)
              py (* (- height (long y) 1) ts)]
          (.setColor batch Color/WHITE)
          (.draw batch region (float px) (float py) (float ts) (float ts)))))
    (.setColor batch Color/WHITE)))
