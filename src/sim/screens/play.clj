(ns sim.screens.play
  "The :play screen. Draws the world (terrain → zones → flora → items → pawns →
   selection box → debug overlay) under the world cam, then the HUD + hover
   inspect panel under the UI cam.
   This is the body that USED to live in sim.render.gdx/render.

   make-processor wraps sim.input/make-processor with one extra injected
   callback: :on-open-pause-menu (Esc → app/enter-pause-menu!)."
  (:require
   [sim.app                    :as app]
   [sim.clock                  :as clock]
   [sim.input                  :as input]
   [sim.screens                :as screens]
   [sim.ui-state               :as ui]
   [sim.ui.hud                 :as hud]
   [sim.ui.inspect-panel       :as inspect-panel]
   [sim.render.layers.terrain  :as terrain]
   [sim.render.layers.zones    :as zones-layer]
   [sim.render.layers.flora    :as flora-layer]
   [sim.render.layers.items    :as items-layer]
   [sim.render.layers.pawns    :as pawns-layer]
   [sim.render.layers.selection :as selection-layer]
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
  (let [cam    (ui/camera)
        ;; Wall-clock stamp for this frame — drives real-time sprite animation
        ;; (sim.render.anim). On real-time, NOT the sim clock, so water ripples
        ;; while paused; captured once so all animated tiles share one frame.
        now-ms (System/currentTimeMillis)]
    ;; --- Sync world cam from ui-state data, then draw world layers ---
    (.set (.position world-cam) (float (:x cam)) (float (:y cam)) (float 0.0))
    (set! (.zoom world-cam) (float (:zoom cam)))
    (.update world-cam)
    (.setProjectionMatrix batch (.combined world-cam))
    (.begin batch)
    (.setColor batch Color/WHITE)
    (terrain/draw     world batch tile-size pixel now-ms)
    ;; Stockpile-zone floor overlay + live drag preview: above terrain, below
    ;; flora/items/pawns so stored items show on top of the zone.
    (zones-layer/draw world batch tile-size pixel)
    (flora-layer/draw world batch tile-size now-ms)
    (items-layer/draw world batch tile-size now-ms)
    (pawns-layer/draw world batch tile-size now-ms)
    ;; Selection box: world-space marker around the selected entity's tile (any
    ;; kind), reusing the 1px texture. Before the debug overlay so a debug path
    ;; still draws on top of the box.
    (selection-layer/draw world batch tile-size pixel)
    (debug-layer/draw world batch tile-size pixel)
    (.end batch)

    ;; --- Fixed UI cam: HUD that ignores pan/zoom ---
    (.setProjectionMatrix batch (.combined ui-cam))
    (.begin batch)
    (hud/draw batch font pixel world)
    ;; Bottom-right hover inspect panel: reads (ui/hover), draws under the UI
    ;; cam like the HUD. No-op when nothing is hovered.
    (inspect-panel/draw batch font pixel world)
    (.end batch)))

(defmethod screens/draw-screen :play [_ ctx]
  (draw ctx))

(defn make-processor
  "Build the InputProcessor for the :play screen. Wraps
   sim.input/make-processor with the existing two injected callbacks, plus
   on-open-pause-menu for the Esc key."
  [{:keys [camera-fn tile-size world-fn]}]
  (input/make-processor
   {:camera-fn          camera-fn
    :tile-size          tile-size
    :world-fn           world-fn
    :on-ui-click        (fn [x y] (hud/click! x y))
    :on-toggle-pause    (fn [] (clock/toggle-pause!))
    :on-open-pause-menu (fn [] (app/enter-pause-menu!))}))
