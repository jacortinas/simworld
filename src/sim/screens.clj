(ns sim.screens
  "Per-screen draw + input dispatch.

   draw-screen is a multimethod keyed on the current :screen value from
   sim.app. Each concrete screen (sim.screens.main-menu, .worldgen, .play,
   .pause-menu) registers its draw via defmethod. sim.render.gdx/render
   calls (draw-screen (app/current-screen) ctx) every frame.

   ctx is a map of GL resources + atom snapshots:
     {:batch ^SpriteBatch
      :font  ^BitmapFont
      :pixel ^Texture                 ; the 1x1 white tex from sim.ui.hud
      :world-cam ^OrthographicCamera
      :ui-cam    ^OrthographicCamera
      :world  <world-value>
      :app    <app-value>}

   Input processors are *built* by each screen's make-processor (one per
   screen) once GL is up, then stored in sim.app/processors keyed by screen.
   sim.app/transition! does the setInputProcessor swap; sim.screens isn't
   involved in that — only in draw dispatch.

   This namespace also hosts two small shared widget helpers that more
   than one screen uses: inside? (rect hit-test) and draw-button! (the
   1px-texture button-rendering grammar)."
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Cursor$SystemCursor Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont GlyphLayout)))

(set! *warn-on-reflection* true)

(defmulti draw-screen
  "Draw the current screen. Dispatches on the screen keyword.
   ctx is the resource map described in the namespace docstring."
  (fn [screen _ctx] screen))

;; ---------------------------------------------------------------------------
;; Shared widget helpers — reused by main-menu, worldgen, pause-menu.
;; ---------------------------------------------------------------------------

(defn inside?
  "Pure axis-aligned hit-test: is pixel coord (x, y) inside [rx ry rw rh]?"
  [[rx ry rw rh] x y]
  (and (<= (long rx) (long x) (+ (long rx) (long rw)))
       (<= (long ry) (long y) (+ (long ry) (long rh)))))

(defn hovered?
  "Pure: is UI-cam-space coord (x, y) inside any of `rects`?"
  [rects x y]
  (boolean (some #(inside? % x y) rects)))

(defn hover-cursor!
  "Set the system cursor to Hand if the mouse is over any rect, else Arrow.
   Reads the current mouse position from libGDX and flips screen-Y-down to
   UI-cam-Y-up. setSystemCursor is idempotent at the OS level, so calling
   this every frame is fine. Screens call this near the end of their draw fn
   with their currently clickable rects."
  [rects]
  (let [input  Gdx/input
        vh     (.getHeight Gdx/graphics)
        x      (.getX input)
        y      (- vh (.getY input))
        cursor (if (hovered? rects x y)
                 Cursor$SystemCursor/Hand
                 Cursor$SystemCursor/Arrow)]
    (.setSystemCursor Gdx/graphics cursor)))

(defn draw-button!
  "Draw a 1px-texture button: bordered rect + inset fill + centered label.
   `border-color`, `fill-color`, and `label-color` are all libGDX Colors.
   The screen passes whatever colors match its palette."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel
   ^Color border-color ^Color fill-color ^Color label-color
   [x y w h] label]
  (let [bx (float x) by (float y) bw (float w) bh (float h)
        ix (float (+ bx 2)) iy (float (+ by 2))
        iw (float (- bw 4)) ih (float (- bh 4))]
    (.setColor batch border-color)
    (.draw batch pixel bx by bw bh)
    (.setColor batch fill-color)
    (.draw batch pixel ix iy iw ih)
    (.setColor batch Color/WHITE)
    (.setColor font label-color)
    (let [cap (.getCapHeight font)
          tw  (.width (GlyphLayout. font ^String label))
          tx  (float (+ bx (/ (- bw tw) 2.0)))
          ty  (float (+ by (/ (+ bh cap) 2.0)))]
      (.draw font batch ^String label tx ty))))
