(ns sim.render.layers.debug-rooms
  "Translucent per-room tint (gated :debug-rooms?, F3): each room a stable hue,
   with ENCLOSED rooms drawn at a stronger alpha than outdoor ones so a sealed
   building reads at a glance and the outdoors stays faint. Door cells belong to
   no room, so they show through untinted. room->color reuses the golden-ratio
   hue hash from layers/debug-regions; the GL fill mirrors that layer and is not
   unit-tested (the room math it reads is covered by sim.rooms-test)."
  (:require
   [sim.rooms    :as rooms]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn room->color
  "Stable [r g b] hue for room id `n` (golden-ratio hash). Alpha is applied by
   the caller, enclosed rooms get more."
  [n]
  (let [h  (mod (* (long n) 0.61803398875) 1.0)
        c1 (Math/abs (- (* 6.0 (mod h 1.0)) 3.0))
        c2 (Math/abs (- (* 6.0 (mod (+ h 0.33) 1.0)) 3.0))
        c3 (Math/abs (- (* 6.0 (mod (+ h 0.66) 1.0)) 3.0))]
    [(min 1.0 (max 0.0 (- c1 1.0)))
     (min 1.0 (max 0.0 (- 2.0 c2)))
     (min 1.0 (max 0.0 (- 2.0 c3)))]))

(def ^:const ^:private enclosed-alpha 0.34)   ; sealed rooms pop
(def ^:const ^:private outdoor-alpha  0.10)    ; the outdoors stays faint

(defn draw
  "For each cell in a room, fill its rect with the room's hue, brighter when the
   room is enclosed, when :debug-rooms? is on. Resets the batch tint when done."
  [world ^SpriteBatch batch ts ^Texture pixel]
  (when (ui/debug-rooms?)
    (let [ri     (rooms/for-world world)
          width  (long (:width (:grid world)))
          height (long (:height (:grid world)))]
      (dotimes [x width]
        (dotimes [y height]
          (let [rid (rooms/room-at ri x y)]
            (when (>= rid 0)
              (let [[r g b] (room->color rid)
                    a  (if (:enclosed? (rooms/room ri rid)) enclosed-alpha outdoor-alpha)
                    px (* (double x) (double ts))
                    py (* (double (- height 1 y)) (double ts))]
                (.setColor batch (float r) (float g) (float b) (float a))
                (.draw batch pixel (float px) (float py) (float ts) (float ts)))))))
      (.setColor batch Color/WHITE))))
