(ns sim.screens.play
  "The :play screen. Draws the world (terrain → flora → items → pawns →
   debug overlay) under the world cam, then the HUD under the UI cam.
   This is the body that USED to live in sim.render.gdx/render.

   make-processor wraps sim.input/make-processor with one extra injected
   callback: :on-open-pause-menu (Esc → app/enter-pause-menu!)."
  (:require
   [sim.clock                  :as clock]
   [sim.input                  :as input]
   [sim.screens                :as screens]
   [sim.ui-state               :as ui]
   [sim.ui.hud                 :as hud]
   [sim.render.layers.terrain  :as terrain]
   [sim.render.layers.flora    :as flora-layer]
   [sim.render.layers.items    :as items-layer]
   [sim.render.layers.pawns    :as pawns-layer]
   [sim.render.layers.debug    :as debug-layer])
  (:import
   (com.badlogic.gdx.graphics Color OrthographicCamera Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont)))

(set! *warn-on-reflection* true)

;; Tile size lives in sim.render.gdx (the GL ns owns pixels-per-tile); we
;; pull it in here via the ctx map rather than re-requiring gdx (would be a
;; circular dep).

(defn draw
  "Render :play. ctx provides GL resources + world/app snapshots + tile-size."
  [{:keys [^SpriteBatch        batch
           ^BitmapFont         font
           ^Texture            pixel
           ^OrthographicCamera world-cam
           ^OrthographicCamera ui-cam
           tile-size
           world]}]
  (let [cam (ui/camera)]
    ;; --- Sync world cam from ui-state data, then draw world layers ---
    (.set (.position world-cam) (float (:x cam)) (float (:y cam)) (float 0.0))
    (set! (.zoom world-cam) (float (:zoom cam)))
    (.update world-cam)
    (.setProjectionMatrix batch (.combined world-cam))
    (.begin batch)
    (.setColor batch Color/WHITE)
    (terrain/draw     world batch tile-size)
    (flora-layer/draw world batch tile-size)
    (items-layer/draw world batch tile-size)
    (pawns-layer/draw world batch tile-size (ui/selected))
    (debug-layer/draw world batch tile-size pixel)
    (.end batch)

    ;; --- Fixed UI cam: HUD that ignores pan/zoom ---
    (.setProjectionMatrix batch (.combined ui-cam))
    (.begin batch)
    (hud/draw batch font pixel world)
    (.end batch)))

(defmethod screens/draw-screen :play [_ ctx]
  (draw ctx))

(defn make-processor
  "Build the InputProcessor for the :play screen. Wraps
   sim.input/make-processor with the existing two injected callbacks.

   The :on-open-pause-menu binding (Esc → app/enter-pause-menu!) is added
   in the pause-menu task — once the :pause-menu draw-screen defmethod
   exists. Until then, Esc in :play is a no-op."
  [{:keys [camera-fn tile-size world-fn]}]
  (input/make-processor
   {:camera-fn       camera-fn
    :tile-size       tile-size
    :world-fn        world-fn
    :on-ui-click     (fn [x y] (hud/click! x y))
    :on-toggle-pause (fn [] (clock/toggle-pause!))}))
