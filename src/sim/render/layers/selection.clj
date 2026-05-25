(ns sim.render.layers.selection
  "World-space selection marker: a box outline around the selected entity's
   tile, for ANY selectable kind. Replaces the pawn-tint stopgap that lived in
   sim.render.layers.pawns.

   Same discipline as the debug layer: selection-box-rects is pure geometry
   (tested headless in sim.selection-layer-test); draw is the untested GL view.
   Uses the (height-1-y) Y-flip — identical to terrain/pawns/debug — so the box
   registers exactly with the sprite underneath, and reuses the shared 1px
   pixel texture for solid rects. Resets the batch tint to white when done."
  (:require
   [sim.ui-state :as ui]
   [sim.entity   :as entity])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

;; Outline thickness in world px. Public so the geometry test derives expected
;; rects from it. ^:const flag (not a type hint) avoids the ^double-on-def trap.
(def ^:const box-thickness 2.0)

(def ^:private box-color (Color. 1.0 0.85 0.2 0.95)) ; amber, like the debug goal

(defn selection-box-rects
  "Four thin edge rects [x y w h] (doubles, world px) framing tile [x y], with
   the (grid-height-1-y) Y-flip. Order: bottom, top, left, right."
  [[x y] tile-size grid-height]
  (let [ts (double tile-size)
        t  box-thickness
        px (* (double x) ts)
        py (* (double (- (long grid-height) (long y) 1)) ts)]
    [[px              py              ts t]    ; bottom edge
     [px              (- (+ py ts) t) ts t]    ; top edge
     [px              py              t  ts]   ; left edge
     [(- (+ px ts) t) py              t  ts]])) ; right edge

(defn draw
  "Draw the box around the selected entity's tile. No-op when nothing is
   selected, the id is stale, or the entity has no :pos (e.g. a carried item).
   Reads (ui/selected) -> entity -> :pos."
  [world ^SpriteBatch batch tile-size ^Texture pixel]
  (when-let [sel (ui/selected)]
    (when-let [pos (:pos (entity/entity world sel))]
      (let [height (long (:height (:grid world)))]
        (.setColor batch box-color)
        (doseq [[x y w h] (selection-box-rects pos tile-size height)]
          (.draw batch pixel (float x) (float y) (float w) (float h)))
        (.setColor batch Color/WHITE)))))
