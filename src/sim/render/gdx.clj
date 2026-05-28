(ns sim.render.gdx
  "libGDX-based graphical renderer.

   This namespace replaces (eventually) the terminal renderer in sim.render.
   The window owns its own thread + OpenGL context; the sim still ticks on
   sim.clock's thread. Both observe the same world atom — no locking,
   atomic reads, immutable snapshots.

   Architecture sketch (we'll grow into it):
     - one ApplicationAdapter proxy = the libGDX entry point
     - one SpriteBatch for all draw calls
     - one BitmapFont (default) for text/glyphs
     - one OrthographicCamera for the world view
     - layers added incrementally — for now we just render the status line.

   Camera model: TWO OrthographicCameras.
     - world-cam pans/zooms; world layers draw under it
     - ui-cam is fixed at screen pixels; HUD draws under it
   Swapping batch.setProjectionMatrix between them is the entire trick to
   'this scrolls, that stays put'. World-cam state is synced each frame
   from sim.ui-state (plain data) — the Java camera is a derived view."
  (:refer-clojure :exclude [run!])      ; our run! shadows clojure.core/run! (unused here)
  (:require
   [sim.app]           ; loaded so we can use fully-qualified sim.app/* in render
   [sim.clock         :as clock]
   [sim.screens]       ; loaded so we can use sim.screens/draw-screen fully-qualified
   [sim.screens.play]                    ; side-effect: register :play defmethod
   [sim.screens.main-menu]              ; side-effect: register :main-menu defmethod
   [sim.screens.pause-menu]             ; side-effect: register :pause-menu defmethod
   [sim.screens.worldgen]               ; side-effect: register :worldgen defmethod
   [sim.ui-state      :as ui]
   [sim.ui.hud        :as hud]
   [sim.world         :as world]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx ApplicationAdapter Gdx Input$Keys)
   (com.badlogic.gdx.backends.lwjgl3
    Lwjgl3Application Lwjgl3ApplicationConfiguration)
   (com.badlogic.gdx.graphics GL20 OrthographicCamera Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont)))

(set! *warn-on-reflection* true)

;; Single source of truth for pixels-per-tile. Public so REPL helpers
;; (user/look-at!) and the sprite layers reference it instead of re-hardcoding.
;; 32 = native 32rogues cell size, so sprites draw 1:1 at zoom 1.0.
(def ^:const tile-size  32)
(def ^:const ^:private screen-w   800)
(def ^:const ^:private screen-h   600)

(defn- poll-camera-keys!
  "Pan the camera from held arrow / WASD keys. Polled once per frame rather
   than driven by key-repeat events, which lag for continuous movement.
   Pan speed scales with zoom so the world moves at a consistent screen
   pace whether zoomed in or out."
  []
  (let [input* (Gdx/input)
        speed  (* 8.0 (double (:zoom (ui/camera))))
        down?  (fn [k] (.isKeyPressed input* (int k)))]
    (when (or (down? Input$Keys/LEFT)  (down? Input$Keys/A)) (ui/pan! (- speed) 0.0))
    (when (or (down? Input$Keys/RIGHT) (down? Input$Keys/D)) (ui/pan! speed 0.0))
    (when (or (down? Input$Keys/UP)    (down? Input$Keys/W)) (ui/pan! 0.0 speed))
    (when (or (down? Input$Keys/DOWN)  (down? Input$Keys/S)) (ui/pan! 0.0 (- speed)))))

(defn- make-listener
  "Build the ApplicationAdapter that libGDX will call into on the GL thread.
   `world-atom` is captured in closure so the proxy never touches a global —
   keeps it easy to test and easy to reason about lifetime."
  [world-atom]
  (let [batch     (atom nil)
        font      (atom nil)
        pixel     (atom nil)
        world-cam (atom nil)
        ui-cam    (atom nil)]
    (proxy [ApplicationAdapter] []
      (create []
        ;; All GL-resource creation MUST happen here: at this point libGDX
        ;; has bound the OpenGL context to this thread.
        (reset! batch (SpriteBatch.))
        (reset! pixel (hud/make-pixel-texture))
        (sprites/load!)                 ; preload the 32rogues sheets (GL thread)
        (let [f (BitmapFont.)]
          ;; Scale 1.0 = the font's native bitmap size, so HUD text is crisp.
          ;; (1.6 upscaled a small bitmap and read as blurry.) The world is
          ;; sprites now; the font is only the HUD + the soon-to-be-sprited
          ;; items layer.
          (.setScale (.getData f) (float 1.0))
          (reset! font f))
        (let [wc (OrthographicCamera.)
              uc (OrthographicCamera.)]
          (.setToOrtho wc false (double screen-w) (double screen-h))
          (.setToOrtho uc false (double screen-w) (double screen-h))
          (reset! world-cam wc)
          (reset! ui-cam uc))
        ;; Center the world camera on the map's midpoint.
        (let [grid (:grid @world-atom)
              cx   (/ (* (long (:width grid))  tile-size) 2.0)
              cy   (/ (* (long (:height grid)) tile-size) 2.0)]
          (ui/center-camera! cx cy))
        ;; Build all input processors and register them in sim.app/processors.
        ;; Screen transition fns swap them via setInputProcessor when :screen changes.
        (let [play-proc (sim.screens.play/make-processor
                         {:camera-fn (fn [] @world-cam)
                          :tile-size tile-size
                          :world-fn  (fn [] @world-atom)})]
          (swap! sim.app/processors assoc :play play-proc))
        ;; Main-menu processor — built once GL exists, registered in app/processors.
        (swap! sim.app/processors assoc :main-menu (sim.screens.main-menu/make-processor))
        ;; Worldgen processor — active only while :worldgen screen is showing.
        (swap! sim.app/processors assoc :worldgen (sim.screens.worldgen/make-processor))
        ;; Pause-menu processor — modal overlay over the frozen play view.
        (swap! sim.app/processors assoc :pause-menu (sim.screens.pause-menu/make-processor))
        ;; Initial active processor: :main-menu (the app boots there per sim.app/initial-app).
        ;; All four processors are registered in sim.app/processors above; the screen
        ;; transition fns swap them via setInputProcessor when :screen changes.
        (.setInputProcessor (Gdx/input) (get @sim.app/processors :main-menu)))

      (render []
        (poll-camera-keys!)
        (let [^SpriteBatch        b   @batch
              ^BitmapFont         f   @font
              ^Texture            px  @pixel
              ^OrthographicCamera wc  @world-cam
              ^OrthographicCamera uc  @ui-cam
              w   @world-atom
              ctx {:batch     b
                   :font      f
                   :pixel     px
                   :world-cam wc
                   :ui-cam    uc
                   :tile-size tile-size
                   :world     w
                   :app       @sim.app/app}]
          (.glClearColor (Gdx/gl) 0.12 0.13 0.16 1.0)
          (.glClear      (Gdx/gl) GL20/GL_COLOR_BUFFER_BIT)
          (sim.screens/draw-screen (sim.app/current-screen) ctx)))

      (resize [w h]
        (.setToOrtho ^OrthographicCamera @world-cam false (double w) (double h))
        (.setToOrtho ^OrthographicCamera @ui-cam    false (double w) (double h)))

      (dispose []
        ;; Window closed → stop the sim loop. In `clj -M:run` this lets the
        ;; parked main thread (sim.core/-main) fall through so the process
        ;; exits; in the REPL it just halts ticking and leaves the session up.
        (clock/stop!)
        (sprites/dispose!)
        (when-let [b @batch] (.dispose ^SpriteBatch b))
        (when-let [f @font]  (.dispose ^BitmapFont  f))
        (when-let [p @pixel] (.dispose ^Texture     p))))))

(defn run!
  "Open the libGDX window and run it ON THE CALLING THREAD, which MUST be the
   process main thread (a hard macOS requirement, and now uniform on every
   platform). Blocks until the window closes. `sim.core/-main` calls this on
   the main thread; `dispose` (window close) stops the sim clock."
  ([] (run! world/world))
  ([world-atom]
   (let [cfg (doto (Lwjgl3ApplicationConfiguration.)
               (.setTitle "sim")
               (.setWindowedMode 800 600)
               (.useVsync true)
               (.setForegroundFPS 60))]
     (Lwjgl3Application. (make-listener world-atom) cfg))))

(defn stop!
  "Ask the libGDX application to exit gracefully. Safe to call from REPL."
  []
  (when-let [app (Gdx/app)]
    (.exit app))
  :stopping)
