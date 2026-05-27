(ns sim.screens.pause-menu
  "The :pause-menu overlay screen.

   Drawn by composing :play's draw (with the clock paused, the world view
   is frozen underneath), darkening the viewport with a translucent rect,
   then rendering a centered modal: Paused title + three buttons.

   This is the codebase's first screen composition: one defmethod calls
   another via the multimethod. No general screen-stack framework — just
   this one specific overlay."
  (:require
   [sim.app     :as app]
   [sim.screens :as screens])
  (:import
   (com.badlogic.gdx Gdx Input$Buttons Input$Keys InputAdapter)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont GlyphLayout)))

(set! *warn-on-reflection* true)

(def ^:private overlay-color   (Color. 0.0  0.0  0.0  0.55))
(def ^:private modal-bg        (Color. 0.10 0.10 0.13 1.0))
(def ^:private modal-border    (Color. 0.45 0.75 0.95 1.0))
(def ^:private title-color     (Color. 0.95 0.97 1.0  1.0))
(def ^:private btn-fill        (Color. 0.16 0.16 0.20 1.0))
(def ^:private btn-border      (Color. 0.45 0.75 0.95 1.0))
(def ^:private btn-label-color (Color. 0.95 0.97 1.0  1.0))

(def ^:const ^:private btn-w 220)
(def ^:const ^:private btn-h 40)
(def ^:const ^:private btn-gap 12)
(def ^:const ^:private modal-w 280)
(def ^:const ^:private modal-h 240)

(defn modal-rect
  "Pure: [vw vh] -> [x y w h] of the modal panel."
  [^long vw ^long vh]
  [(long (- (quot vw 2) (quot modal-w 2)))
   (long (- (quot vh 2) (quot modal-h 2)))
   modal-w modal-h])

(defn button-rects
  "Pure: [vw vh] -> {:resume r :quit-to-menu r :quit-game r}.
   Three buttons stacked inside the modal, vertically centered."
  [^long vw ^long vh]
  (let [[mx my _ _] (modal-rect vw vh)
        x           (long (- (+ mx (quot modal-w 2)) (quot btn-w 2)))
        block-h     (long (+ (* 3 btn-h) (* 2 btn-gap)))
        top-y       (long (+ my (quot (- modal-h block-h) 2)
                             (* 2 (+ btn-h btn-gap))))]
    {:resume       [x  top-y                       btn-w btn-h]
     :quit-to-menu [x  (- top-y (+ btn-h btn-gap)) btn-w btn-h]
     :quit-game    [x  (- top-y (* 2 (+ btn-h btn-gap))) btn-w btn-h]}))

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
    (.setColor font btn-label-color)
    (let [cap (.getCapHeight font)
          tw  (.width (GlyphLayout. font ^String label))
          tx  (float (+ bx (/ (- bw tw) 2.0)))
          ty  (float (+ by (/ (+ bh cap) 2.0)))]
      (.draw font batch ^String label tx ty))))

(defn draw
  [{:keys [^SpriteBatch batch ^BitmapFont font ^Texture pixel
           ^com.badlogic.gdx.graphics.OrthographicCamera ui-cam]
    :as ctx}]
  ;; 1. Draw :play underneath (the world is frozen since clock is paused).
  (screens/draw-screen :play ctx)

  ;; 2. Darken the viewport with a translucent overlay.
  (let [vw (.getWidth  (Gdx/graphics))
        vh (.getHeight (Gdx/graphics))]
    (.setProjectionMatrix batch (.combined ui-cam))
    (.begin batch)
    (.setColor batch overlay-color)
    (.draw batch pixel (float 0) (float 0) (float vw) (float vh))

    ;; 3. Draw the modal: border + fill + title + buttons.
    (let [[mx my mw mh]                  (modal-rect vw vh)
          {:keys [resume quit-to-menu
                  quit-game]}            (button-rects vw vh)]
      (.setColor batch modal-border)
      (.draw batch pixel (float mx) (float my) (float mw) (float mh))
      (.setColor batch modal-bg)
      (.draw batch pixel (float (+ mx 2)) (float (+ my 2))
                         (float (- mw 4)) (float (- mh 4)))
      (.setColor batch Color/WHITE)
      ;; title
      (.setColor font title-color)
      (let [title "Paused"
            tw    (.width (GlyphLayout. font ^String title))
            tx    (float (+ mx (/ (- mw tw) 2.0)))
            ty    (float (+ my mh -28))]
        (.draw font batch ^String title tx ty))
      ;; buttons
      (draw-button! batch font pixel resume       "Resume")
      (draw-button! batch font pixel quit-to-menu "Quit to Menu")
      (draw-button! batch font pixel quit-game    "Quit Game"))
    (.end batch)))

(defmethod screens/draw-screen :pause-menu [_ ctx]
  (draw ctx))

(defn make-processor
  "Build the :pause-menu InputProcessor.
     Esc / click Resume        -> resume-from-pause-menu!
     click Quit to Menu        -> quit-to-menu!
     click Quit Game           -> quit-game!
     Clicks outside the modal  -> consumed (do nothing — no pass-through to :play)."
  []
  (proxy [InputAdapter] []
    (keyDown [keycode]
      (if (= (int keycode) Input$Keys/ESCAPE)
        (do (app/resume-from-pause-menu!) true)
        false))
    (touchDown [screen-x screen-y _pointer button]
      (if (= (int button) Input$Buttons/LEFT)
        (let [vw    (.getWidth  (Gdx/graphics))
              vh    (.getHeight (Gdx/graphics))
              y     (- vh screen-y)
              rects (button-rects vw vh)]
          (cond
            (inside? (:resume rects)       screen-x y) (do (app/resume-from-pause-menu!) true)
            (inside? (:quit-to-menu rects) screen-x y) (do (app/quit-to-menu!)            true)
            (inside? (:quit-game rects)    screen-x y) (do (app/quit-game!)               true)
            :else                                       true))  ; consumed; clicks don't fall through to :play
        false))))
