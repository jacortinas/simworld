(ns sim.render.layers.debug-pathgrid
  "Faint red wash on blocked (INFINITY cost) cells in the PathGrid, gated by
   :debug-pathgrid?. No pure helper beyond pathgrid/passable?; the GL fill
   mirrors layers/zones.clj and is not unit-tested."
  (:require
   [sim.pathgrid :as pathgrid]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(def ^:private blocked-color (Color. 0.85 0.15 0.15 0.30))  ; faint red wash

(defn draw
  "Draw a faint red quad on each blocked (INFINITY) cell in the PathGrid when
   :debug-pathgrid? is on. Resets the batch tint to white when done."
  [world ^SpriteBatch batch ts ^Texture pixel]
  (when (ui/debug-pathgrid?)
    (let [pg     (pathgrid/for-world world)
          width  (long (:width (:grid world)))
          height (long (:height (:grid world)))]
      (.setColor batch blocked-color)
      (dotimes [x width]
        (dotimes [y height]
          (when-not (pathgrid/passable? pg x y)
            (let [px (* (double x) (double ts))
                  py (* (double (- height 1 y)) (double ts))]
              (.draw batch pixel (float px) (float py) (float ts) (float ts))))))
      (.setColor batch Color/WHITE))))
