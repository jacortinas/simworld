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

(defn draw
  "Draw a single-cell tinted quad at the hovered tile when in a build mode. Green
   when placement is valid, red when not. No-op outside a build mode or when hover
   is nil. Resets the batch tint to white when done."
  [world ^SpriteBatch batch ts ^Texture pixel]
  (when (build-modes (ui/mode))
    (when-let [[tx ty] (ui/hover)]
      (let [height (long (:height (:grid world)))
            ok?    (command/can-build? world [tx ty])
            [x y w h] (cell-rect tx ty height ts)]
        (.setColor batch (if ok? can-place-color no-place-color))
        (.draw batch pixel (float x) (float y) (float w) (float h))
        (.setColor batch Color/WHITE)))))
