(ns sim.render.layers.pawns
  "Pawns layer: each pawn drawn as a 32px sprite at its tile.

   The selected pawn is tinted to signal selection — a stopgap until a proper
   world-space selection box (now feasible with the 1px texture in sim.ui.hud)
   lands. Resets the batch tint to white when done so later draws aren't
   tinted."
  (:require
   [sim.entity :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color)
   (com.badlogic.gdx.graphics.g2d SpriteBatch TextureRegion)))

(set! *warn-on-reflection* true)

(def ^:private selected-tint (Color. 1.0 1.0 0.5 1.0))

(defn draw
  "Render pawns in WORLD coordinates. Same Y-flip convention as terrain."
  [world ^SpriteBatch batch tile-size selected-id]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)
        ^TextureRegion region (sprites/pawn-region)]
    (doseq [pawn (entity/pawns world)]
      (when-let [[x y] (:pos pawn)]
        (let [px (* (long x) ts)
              ;; bottom-left anchor; (height-1-y) matches screen->tile (see terrain)
              py (* (- height (long y) 1) ts)]
          (.setColor batch (if (= selected-id (:id pawn)) selected-tint Color/WHITE))
          (.draw batch region (float px) (float py) (float ts) (float ts)))))
    (.setColor batch Color/WHITE)))
