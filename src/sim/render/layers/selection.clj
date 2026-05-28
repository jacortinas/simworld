(ns sim.render.layers.selection
  "World-space selection marker: a box outline around the selected entity, for
   ANY selectable kind. Replaces the pawn-tint stopgap that lived in
   sim.render.layers.pawns.

   Same discipline as the debug layer: selection-box-rects is pure geometry
   (tested headless in sim.selection-layer-test); draw is the untested GL view.
   The bottom-left world-pixel (incl. the Y-flip and any in-flight glide) comes
   from sim.render.interp/draw-pos, so the box registers exactly with — and
   glides alongside — the sprite underneath. Reuses the shared 1px pixel texture
   for solid rects; resets the batch tint to white when done."
  (:require
   [sim.ui-state      :as ui]
   [sim.entity        :as entity]
   [sim.render.interp :as interp])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

;; Outline thickness in world px. Public so the geometry test derives expected
;; rects from it. ^:const flag (not a type hint) avoids the ^double-on-def trap.
(def ^:const box-thickness 2.0)

(def ^:private box-color (Color. 1.0 0.85 0.2 0.95)) ; amber, like the debug goal

(defn selection-box-rects
  "Four thin edge rects [x y w h] (doubles, world px) framing the tile whose
   bottom-left world-pixel is `[px py]`. The Y-flip and any glide are applied
   upstream by sim.render.interp/draw-pos. Order: bottom, top, left, right."
  [[px py] tile-size]
  (let [ts (double tile-size)
        t  box-thickness
        px (double px)
        py (double py)]
    [[px              py              ts t]    ; bottom edge
     [px              (- (+ py ts) t) ts t]    ; top edge
     [px              py              t  ts]   ; left edge
     [(- (+ px ts) t) py              t  ts]])) ; right edge

(defn draw
  "Draw the box around the selected entity. No-op when nothing is selected, the
   id is stale, or the entity has no :pos (e.g. a carried item). The box tracks
   the entity's interpolated draw position, so it glides with a moving pawn.
   Reads (ui/selected) -> entity -> draw-pos."
  [world ^SpriteBatch batch tile-size ^Texture pixel]
  (when-let [sel (ui/selected)]
    (when-let [ent (entity/entity world sel)]
      (when (:pos ent)
        (let [height (:height (:grid world))]
          (.setColor batch box-color)
          (doseq [[x y w h] (selection-box-rects
                             (interp/draw-pos ent tile-size height) tile-size)]
            (.draw batch pixel (float x) (float y) (float w) (float h)))
          (.setColor batch Color/WHITE))))))
