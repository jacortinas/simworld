(ns sim.screens.worldgen
  "The :worldgen screen — shown while sim.worldgen/generate runs on a
   background future. Polls (:worldgen @app) each frame and renders one of:
     :running → phase label + animated trailing dots
     :failed  → error message + Back button
     :done    → triggers (app/enter-play! result) on this same frame
   The animation uses System/nanoTime (real time), NOT the sim clock — the
   clock is stopped during worldgen, so coupling to it would freeze the dots."
  (:require
   [sim.app     :as app]
   [sim.screens :as screens])
  (:import
   (com.badlogic.gdx Gdx Input$Buttons Input$Keys InputAdapter)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont GlyphLayout)))

(set! *warn-on-reflection* true)

(def ^:private bg-color    (Color. 0.05 0.05 0.08 1.0))
(def ^:private text-color  (Color. 0.95 0.97 1.0  1.0))
(def ^:private err-color   (Color. 1.0  0.45 0.45 1.0))
(def ^:private btn-fill    (Color. 0.16 0.16 0.20 1.0))
(def ^:private btn-border  (Color. 0.45 0.75 0.95 1.0))

(def ^:const ^:private btn-w 180)
(def ^:const ^:private btn-h 38)

(defn back-button-rect
  "Pure: [vw vh] -> [x y w h] for the Back button (only visible when failed)."
  [^long vw ^long vh]
  [(long (- (quot vw 2) (quot btn-w 2)))
   (long (- (quot vh 2) (+ btn-h 40)))
   btn-w btn-h])

(defn- phase-label [phase]
  (case phase
    :terrain  "Generating world... (terrain)"
    :detail   "Generating world... (detail)"
    "Generating world..."))

(defn- dots-suffix
  "1..4 dots cycling every 300ms wall-clock. Pure of args, reads nanoTime."
  []
  (let [cycle-ms 300
        ms       (quot (System/nanoTime) 1000000)
        n        (inc (mod (quot ms cycle-ms) 4))]
    (apply str (repeat n "."))))

(defn- draw-centered-text!
  [^SpriteBatch batch ^BitmapFont font ^String s y vw color]
  (let [vw (long vw)]
    (.setColor font color)
    (let [tw (.width (GlyphLayout. font s))
          tx (float (- (quot vw 2) (quot (long tw) 2)))]
      (.draw font batch s tx (float y)))))

(defn- draw-button!
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel [x y w h] label]
  (let [bx (float x) by (float y) bw (float w) bh (float h)
        ix (float (+ bx 2)) iy (float (+ by 2))
        iw (float (- bw 4)) ih (float (- bh 4))]
    (.setColor batch btn-border)
    (.draw batch pixel bx by bw bh)
    (.setColor batch btn-fill)
    (.draw batch pixel ix iy iw ih)
    (.setColor batch Color/WHITE)
    (.setColor font text-color)
    (let [cap (.getCapHeight font)
          tw  (.width (GlyphLayout. font ^String label))
          tx  (float (+ bx (/ (- bw tw) 2.0)))
          ty  (float (+ by (/ (+ bh cap) 2.0)))]
      (.draw font batch ^String label tx ty))))

(defn draw
  [{:keys [^SpriteBatch batch ^BitmapFont font ^Texture pixel
           ^com.badlogic.gdx.graphics.OrthographicCamera ui-cam
           app]}]
  (let [vw      (.getWidth  (Gdx/graphics))
        vh      (.getHeight (Gdx/graphics))
        wg      (:worldgen app)
        status  (:status wg)]
    (.setProjectionMatrix batch (.combined ui-cam))
    (.begin batch)
    (.setColor batch bg-color)
    (.draw batch pixel (float 0) (float 0) (float vw) (float vh))
    (case status
      :running
      (let [msg (str (phase-label (:phase wg)) " " (dots-suffix))]
        (draw-centered-text! batch font msg (+ (quot vh 2) 8) vw text-color))

      :failed
      (let [msg     (str "World generation failed: "
                         (some-> ^Throwable (:error wg) .getMessage))
            back    (back-button-rect vw vh)]
        (draw-centered-text! batch font msg (+ (quot vh 2) 60) vw err-color)
        (draw-button! batch font pixel back "Back to Menu"))

      :done
      nil          ; nothing to draw; we trigger the transition below

      nil)         ; :idle is unreachable from this screen, but be safe
    (.end batch)

    ;; Self-driven transition: on the same frame we observe :status :done,
    ;; flip to :play. enter-play! handles seed-colony!, clock/start!, and
    ;; the screen + processor swap.
    (when (= status :done)
      (app/enter-play! (:result wg)))))

(defmethod screens/draw-screen :worldgen [_ ctx]
  (draw ctx))

(defn- inside?
  "Hit-test helper: is screen-coord (sx, sy) inside [rx ry rw rh]?"
  [[rx ry rw rh] sx sy]
  (and (<= (long rx) (long sx) (+ (long rx) (long rw)))
       (<= (long ry) (long sy) (+ (long ry) (long rh)))))

(defn make-processor
  "Build the :worldgen InputProcessor.
     - When :status :failed, Esc or click on Back returns to the main menu.
     - Otherwise input is ignored — the user is waiting."
  []
  (proxy [InputAdapter] []
    (keyDown [keycode]
      (if (and (= :failed (get-in @app/app [:worldgen :status]))
               (= (int keycode) Input$Keys/ESCAPE))
        (do (app/quit-to-menu!) true)
        false))
    (touchDown [screen-x screen-y _pointer button]
      (if (and (= :failed (get-in @app/app [:worldgen :status]))
               (= (int button) Input$Buttons/LEFT))
        (let [vw   (.getWidth  (Gdx/graphics))
              vh   (.getHeight (Gdx/graphics))
              back (back-button-rect vw vh)
              y    (- vh screen-y)]
          (if (inside? back screen-x y)
            (do (app/quit-to-menu!) true)
            false))
        false))))
