(ns sim.render.layers.build-cursor
  "Single-cell hover indicator for the build modes (a translucent quad at the
   hovered tile, green if placeable / red if not). Shared by :build (walls) and
   :build-door (doors): both place one building per click and use the same
   can-build? validity, so one cursor serves both. Pure cell-rect is tested
   headless; the GL fill mirrors layers/zones.clj and is not unit-tested."
  (:require
   [sim.command  :as command]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(def ^:const tile-size 32)

(def ^:private can-place-color  (Color. 0.2 0.9 0.2 0.40))  ; translucent green
(def ^:private no-place-color   (Color. 0.9 0.2 0.2 0.40))  ; translucent red

(defn cell-rect
  "Bottom-left [x y w h] pixel rect for tile (tx, ty) on an h-tall grid, with the
   (height-1-y) Y-flip the world layers use."
  [tx ty height tile-size]
  (let [ts (double tile-size)]
    [(* (double tx) ts)
     (* (double (- (long height) 1 (long ty))) ts)
     ts ts]))

(def ^:private build-modes #{:build :build-door})

(defn- footprint-cells
  "Cells of the [w h] rect anchored at origin [ox oy]."
  [[ox oy] [w h]]
  (for [dy (range h) dx (range w)] [(+ (long ox) (long dx)) (+ (long oy) (long dy))]))

(defn draw
  "Tinted placement preview, green when can-build?, red when not. During a door
   DRAG it previews the spanned gate footprint; otherwise (any build mode, just
   hovering) it shows the single hovered cell. No-op outside a build mode / with
   no hover. Resets the batch tint when done."
  [world ^SpriteBatch batch ts ^Texture pixel]
  (let [height     (long (:height (:grid world)))
        draw-cells (fn [cells ok?]
                     (.setColor batch ^Color (if ok? can-place-color no-place-color))
                     (doseq [[cx cy] cells]
                       (let [[x y w h] (cell-rect cx cy height ts)]
                         (.draw batch pixel (float x) (float y) (float w) (float h))))
                     (.setColor batch Color/WHITE))]
    (cond
      ;; door drag in flight: preview the spanned gate, validity over the whole span
      (and (= :build-door (ui/mode)) (ui/drag))
      (let [{:keys [start current]} (ui/drag)
            [origin size] (command/door-span start current)]
        (draw-cells (footprint-cells origin size) (command/can-build? world origin size)))

      ;; any build mode, just hovering: the single hovered cell
      (build-modes (ui/mode))
      (when-let [[tx ty] (ui/hover)]
        (draw-cells [[tx ty]] (command/can-build? world [tx ty]))))))
