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
   involved in that — only in draw dispatch."
  )

(set! *warn-on-reflection* true)

(defmulti draw-screen
  "Draw the current screen. Dispatches on the screen keyword.
   ctx is the resource map described in the namespace docstring."
  (fn [screen _ctx] screen))
