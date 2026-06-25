(ns sim.screens.play
  "The :play screen. Draws the world (terrain -> zones -> flora -> items ->
   buildings -> blueprints -> pawns -> selection box -> debug overlay ->
   debug-regions -> debug-pathgrid -> debug-rooms -> build-cursor) under the world
   cam, then the HUD + hover inspect panel under the UI cam.
   This is the body that USED to live in sim.render.gdx/render.

   make-processor wraps sim.input/make-processor with one extra injected
   callback: :on-open-pause-menu (Esc -> app/enter-pause-menu!)."
  (:require
   [sim.app                             :as app]
   [sim.clock                           :as clock]
   [sim.input                           :as input]
   [sim.screens                         :as screens]
   [sim.ui-state                        :as ui]
   [sim.ui.hud                          :as hud]
   [sim.ui.time-controls                :as time-controls]
   [sim.ui.build-menu                   :as build-menu]
   [sim.ui.inspect-panel                :as inspect-panel]
   [sim.render.layers.terrain           :as terrain]
   [sim.render.layers.zones             :as zones-layer]
   [sim.render.layers.flora             :as flora-layer]
   [sim.render.layers.items             :as items-layer]
   [sim.render.layers.buildings         :as buildings-layer]
   [sim.render.layers.blueprints        :as blueprints-layer]
   [sim.render.layers.pawns             :as pawns-layer]
   [sim.render.layers.selection         :as selection-layer]
   [sim.render.layers.debug             :as debug-layer]
   [sim.render.layers.debug-regions     :as debug-regions-layer]
   [sim.render.layers.debug-pathgrid    :as debug-pathgrid-layer]
   [sim.render.layers.debug-rooms       :as debug-rooms-layer]
   [sim.render.layers.build-cursor      :as build-cursor-layer])
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
        ;; Wall-clock stamp for this frame: drives real-time sprite animation
        ;; (graphic-region, sim.render.graphic/frame). On real-time, NOT the sim
        ;; clock, so water ripples while paused; captured once so all animated
        ;; tiles share one frame.
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
    ;; Buildings (walls) above items and below pawns: occlude the floor but not
    ;; colonists.
    (buildings-layer/draw world batch tile-size now-ms)
    ;; Blueprints (unbuilt designations) ghosted just above built structures and
    ;; below pawns: a planned wall/door reads over the floor but a colonist on it
    ;; still draws on top. Needs the 1px pixel for the progress bar.
    (blueprints-layer/draw world batch tile-size pixel now-ms)
    (pawns-layer/draw world batch tile-size now-ms)
    ;; Selection box: world-space marker around the selected entity's tile (any
    ;; kind), reusing the 1px texture. Before the debug overlay so a debug path
    ;; still draws on top of the box.
    (selection-layer/draw world batch tile-size pixel)
    (debug-layer/draw world batch tile-size pixel)
    ;; Debug overlays: region tints and pathgrid blocked-cell wash. Drawn after
    ;; the path overlay so region/pathgrid sit above paths visually but below
    ;; the build cursor (which must be on top of everything).
    (debug-regions-layer/draw world batch tile-size pixel)
    (debug-pathgrid-layer/draw world batch tile-size pixel)
    ;; Rooms overlay (F3): per-room tint, enclosed rooms brighter than outdoors.
    (debug-rooms-layer/draw world batch tile-size pixel)
    ;; Build cursor: single-cell indicator on top of all world layers so the
    ;; placement preview is never obscured.
    (build-cursor-layer/draw world batch tile-size pixel)
    (.end batch)

    ;; --- Fixed UI cam: HUD that ignores pan/zoom ---
    (.setProjectionMatrix batch (.combined ui-cam))
    (.begin batch)
    (hud/draw batch font pixel world)
    ;; Top-right time controls (pause + 1x/2x/3x), per the sim.ui.layout
    ;; information hierarchy: global + interactive lives top-right.
    (time-controls/draw batch font pixel)
    ;; Bottom-left build menu (RimWorld's Architect): category row + the open
    ;; category's buildables. Arms a tool by setting ui-state/:mode.
    (build-menu/draw batch font pixel)
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
    ;; Time controls get first dibs on a left click (top-right), then the HUD
    ;; bar (bottom). Both sit ON TOP of the world, so they eat the click before
    ;; it can become a world command, the input-side mirror of their z-order.
    :on-ui-click        (fn [x y] (or (time-controls/click! x y)
                                      (build-menu/click! x y)
                                      (hud/click! x y)))
    :on-toggle-pause    (fn [] (clock/toggle-pause!))
    ;; Selecting a speed un-pauses (RimWorld feel); set-speed! itself is
    ;; pause-orthogonal, the resume! is the UI policy.
    :on-set-speed       (fn [n] (clock/set-speed! n) (clock/resume!))
    :on-open-pause-menu (fn [] (app/enter-pause-menu!))}))
