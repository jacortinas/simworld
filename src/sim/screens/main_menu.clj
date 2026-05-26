(ns sim.screens.main-menu
  "The :main-menu screen.

   UI cam only — no world to draw. A dark background, the title 'sim', and
   two stacked buttons: New Colony / Quit. Buttons reuse the 1px-texture
   render grammar from sim.ui.hud.

   Hit-test geometry is computed pure in button-rects from the live UI cam
   dimensions, so click! and draw stay in lockstep across window resizes."
  (:require
   [sim.app     :as app]
   [sim.screens :as screens])
  (:import
   (com.badlogic.gdx Gdx Input$Buttons InputAdapter)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont GlyphLayout)))

(set! *warn-on-reflection* true)

(def ^:private bg-color       (Color. 0.05 0.05 0.08 1.0))
(def ^:private btn-fill       (Color. 0.16 0.16 0.20 1.0))
(def ^:private btn-border     (Color. 0.45 0.75 0.95 1.0))
(def ^:private btn-label      (Color. 0.95 0.97 1.0  1.0))
(def ^:private title-color    (Color. 0.95 0.97 1.0  1.0))

(def ^:const ^:private btn-w 220)
(def ^:const ^:private btn-h 44)
(def ^:const ^:private btn-gap 16)

(defn button-rects
  "Pure: [vw vh] -> {:new-colony [x y w h] :quit [x y w h]}.
   Buttons centered horizontally; stacked vertically just below mid-screen."
  [^long vw ^long vh]
  (let [x      (long (- (quot vw 2) (quot btn-w 2)))
        new-y  (long (- (quot vh 2) (+ btn-h (quot btn-gap 2))))
        quit-y (long (- new-y btn-h btn-gap))]
    {:new-colony [x new-y  btn-w btn-h]
     :quit       [x quit-y btn-w btn-h]}))

(defn- inside? [[rx ry rw rh] x y]
  (and (<= (long rx) (long x) (+ (long rx) (long rw)))
       (<= (long ry) (long y) (+ (long ry) (long rh)))))

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
    (.setColor font btn-label)
    (let [cap (.getCapHeight font)
          tw  (.width (GlyphLayout. font ^String label))
          tx  (float (+ bx (/ (- bw tw) 2.0)))
          ty  (float (+ by (/ (+ bh cap) 2.0)))]
      (.draw font batch ^String label tx ty))))

(defn draw
  "Render :main-menu. ctx provides batch, font, pixel, ui-cam, viewport dims."
  [{:keys [^SpriteBatch batch ^BitmapFont font ^Texture pixel
           ^com.badlogic.gdx.graphics.OrthographicCamera ui-cam]}]
  (let [vw (.getWidth  (Gdx/graphics))
        vh (.getHeight (Gdx/graphics))]
    (.setProjectionMatrix batch (.combined ui-cam))
    (.begin batch)
    ;; full-viewport background
    (.setColor batch bg-color)
    (.draw batch pixel (float 0) (float 0) (float vw) (float vh))
    ;; title text — centered, ~3/4 up the screen
    (.setColor font title-color)
    (let [title "sim"
          tw    (.width (GlyphLayout. font ^String title))
          tx    (float (- (quot vw 2) (quot (long tw) 2)))
          ty    (float (+ (quot vh 2) (quot vh 4)))]
      (.draw font batch ^String title tx ty))
    ;; buttons
    (let [{:keys [new-colony quit]} (button-rects vw vh)]
      (draw-button! batch font pixel new-colony "New Colony")
      (draw-button! batch font pixel quit       "Quit"))
    (.end batch)))

(defmethod screens/draw-screen :main-menu [_ ctx]
  (draw ctx))

(defn make-processor
  "Build the :main-menu InputProcessor. Left-click on button rects triggers
   the matching transition; everything else falls through (returns false)."
  []
  (proxy [InputAdapter] []
    (touchDown [screen-x screen-y _pointer button]
      (if (= (int button) Input$Buttons/LEFT)
        (let [vw    (.getWidth  (Gdx/graphics))
              vh    (.getHeight (Gdx/graphics))
              ;; libGDX touchDown gives Y-down screen coords; UI cam is Y-up
              ;; with origin bottom-left, so flip: y_ui = vh - sy.
              x     screen-x
              y     (- vh screen-y)
              rects (button-rects vw vh)]
          (cond
            (inside? (:new-colony rects) x y) (do (app/enter-worldgen!) true)
            (inside? (:quit rects)       x y) (do (app/quit-game!)      true)
            :else                              false))   ; left-click miss — pass through
        false))))
