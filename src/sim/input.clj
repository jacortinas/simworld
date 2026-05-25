(ns sim.input
  "Mouse/keyboard input → ui-state + command dispatch.

   libGDX delivers discrete events (scroll, click, drag) through an
   InputProcessor. We proxy InputAdapter (a no-op base implementing every
   method) and override only the ones we care about. Continuous input
   (held arrow keys for panning) is polled in the render loop instead —
   see sim.render.gdx.

   RimWorld mapping:
     left-click   → UI hit-test first; if nothing caught it, select a pawn
     right-click  → order  (sim.command/right-click!)
     middle-drag  → pan
     wheel        → zoom
     space        → toggle pause
     mouse-move   → record hovered tile (sim.ui-state/set-hover!)

   UI-eats-the-click: on-screen widgets (the pause button) live in fixed
   screen space and sit ON TOP of the world visually. Input mirrors that
   z-order — a left click is offered to the HUD first, and only falls
   through to a world command if the HUD didn't consume it.

   Events fire on the GL thread, same as render, so swap!-ing the atoms is
   safe with no extra coordination."
  (:require
   [sim.command  :as command]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx InputAdapter Input$Buttons Input$Keys)
   (com.badlogic.gdx.graphics OrthographicCamera)
   (com.badlogic.gdx.math Vector3)))

(set! *warn-on-reflection* true)

(defn- screen->tile
  "Unproject a screen click through the world camera to a tile [tx ty].
   This is the inverse of rendering: the same camera matrix that placed the
   tile on screen, run backwards, tells us which tile the pixel belongs to —
   so it stays correct under any pan/zoom. The (height-1-row) term undoes
   the Y-flip the layers apply when drawing."
  [^OrthographicCamera camera tile-size height screen-x screen-y]
  (let [v (Vector3. (float screen-x) (float screen-y) (float 0))]
    (.unproject camera v)
    (let [ts (double tile-size)
          tx (long (Math/floor (/ (.x v) ts)))
          ty (- (long height) 1 (long (Math/floor (/ (.y v) ts))))]
      [tx ty])))

(defn make-processor
  "Build the InputProcessor from an opts map:
     :camera-fn       -> live world OrthographicCamera (to unproject clicks)
     :tile-size       -> tile pixel size
     :world-fn        -> current world value (for grid height)
     :on-ui-click     -> (fn [sx sy] -> consumed?) offered every left click
                         BEFORE it becomes a world command
     :on-toggle-pause -> (fn []) invoked on the space key

   on-ui-click / on-toggle-pause are injected (rather than required) so this
   namespace stays decoupled from the HUD and the game loop — easy to test
   with stub fns."
  [{:keys [camera-fn tile-size world-fn on-ui-click on-toggle-pause]}]
  (let [drag (atom nil)]            ; {:button b :x sx :y sy}
    (proxy [InputAdapter] []
      (scrolled [_amount-x amount-y]
        (ui/zoom-by! (if (pos? (double amount-y)) 1.1 0.9))
        true)

      ;; Cursor moved with no button down: record the hovered tile so the
      ;; inspect panel can read it. Returns false — hover must NEVER consume
      ;; the event. Calls ui/set-hover! directly (we already depend on
      ;; sim.ui-state), same precedent as backtick->debug. Stores raw,
      ;; possibly off-map coords; sim.inspect bounds-checks.
      (mouseMoved [screen-x screen-y]
        (let [height  (long (:height (:grid (world-fn))))
              [tx ty] (screen->tile (camera-fn) tile-size height screen-x screen-y)]
          (ui/set-hover! [tx ty]))
        false)

      (keyDown [keycode]
        (condp = (int keycode)
          ;; space → pause is INJECTED (on-toggle-pause) to keep this ns from
          ;; importing sim.clock. The debug toggle is called DIRECTLY because
          ;; we already depend on sim.ui-state (zoom/pan) — no new coupling.
          Input$Keys/SPACE (do (when on-toggle-pause (on-toggle-pause)) true)
          Input$Keys/GRAVE (do (ui/toggle-debug!) true)   ; backtick ` : debug overlay
          false))

      (touchDown [screen-x screen-y _pointer button]
        (reset! drag {:button button :x screen-x :y screen-y})
        (if (and (= (int button) Input$Buttons/LEFT)
                 on-ui-click
                 (on-ui-click screen-x screen-y))
          true                        ; HUD consumed the click — no world cmd
          (do
            (let [height  (long (:height (:grid (world-fn))))
                  [tx ty] (screen->tile (camera-fn) tile-size height screen-x screen-y)]
              (condp = (int button)
                Input$Buttons/LEFT  (command/left-click!  tx ty)
                Input$Buttons/RIGHT (command/right-click! tx ty)
                nil))
            true)))

      (touchDragged [screen-x screen-y _pointer]
        (when-let [{:keys [button x y]} @drag]
          (when (= (int button) Input$Buttons/MIDDLE)
            (let [zoom (double (:zoom (ui/camera)))
                  dx   (- (long screen-x) (long x))
                  dy   (- (long screen-y) (long y))]
              ;; grab-and-drag: world follows cursor → camera moves opposite.
              ;; screen Y is down, world Y up → dy not negated.
              (ui/pan! (* (- dx) zoom) (* dy zoom))))
          (swap! drag assoc :x screen-x :y screen-y))
        true)

      (touchUp [_screen-x _screen-y _pointer _button]
        (reset! drag nil)
        false))))
