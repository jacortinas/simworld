(ns sim.input
  "Mouse/keyboard input → ui-state + command dispatch.

   libGDX delivers discrete events (scroll, click, drag) through an
   InputProcessor. We proxy InputAdapter (a no-op base implementing every
   method) and override only the ones we care about. Continuous input
   (held arrow keys for panning) is polled in the render loop instead —
   see sim.render.gdx.

   RimWorld mapping:
     left-click   → UI hit-test first; if nothing caught it, select a pawn
                    (in zoning mode: left-DRAG paints a stockpile rectangle;
                     SHIFT+left-DRAG erases cells from existing stockpiles)
     right-click  → order (sim.command/right-click!); in zoning mode: cancel mode
     middle-drag  → pan
     wheel        → zoom
     space        → toggle pause
     Z            → enter stockpile zoning mode
     Esc          → cancel the active zoning tool; else open the pause menu
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
   (com.badlogic.gdx Gdx InputAdapter Input$Buttons Input$Keys)
   (com.badlogic.gdx.graphics OrthographicCamera)
   (com.badlogic.gdx.math Vector3)))

(set! *warn-on-reflection* true)

(defn- shift-down?
  "Is either Shift key currently held? Polled from live libGDX input — used to
   pick the erase variant of a zoning drag at the moment it begins."
  []
  (or (.isKeyPressed Gdx/input (int Input$Keys/SHIFT_LEFT))
      (.isKeyPressed Gdx/input (int Input$Keys/SHIFT_RIGHT))))

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
     :camera-fn            -> live world OrthographicCamera (to unproject clicks)
     :tile-size            -> tile pixel size
     :world-fn             -> current world value (for grid height)
     :on-ui-click          -> (fn [sx sy] -> consumed?) offered every left click
                              BEFORE it becomes a world command
     :on-toggle-pause      -> (fn []) invoked on the space key
     :on-open-pause-menu   -> (fn []) invoked on the Escape key (optional)

   on-ui-click / on-toggle-pause / on-open-pause-menu are injected (rather
   than required) so this namespace stays decoupled from the HUD and the game
   loop — easy to test with stub fns."
  [{:keys [camera-fn tile-size world-fn on-ui-click on-toggle-pause on-open-pause-menu]}]
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
          ;; we already depend on sim.ui-state (zoom/pan) -- no new coupling.
          Input$Keys/SPACE  (do (when on-toggle-pause (on-toggle-pause)) true)
          Input$Keys/GRAVE  (do (ui/toggle-debug!) true)   ; backtick ` : debug overlay
          Input$Keys/Z      (do (ui/set-mode! :zone-stockpile) true) ; enter zoning
          Input$Keys/B      (do (ui/set-mode! :build) true)          ; enter build mode
          Input$Keys/F1     (do (ui/toggle-debug-regions?) true)     ; toggle region overlay
          Input$Keys/F2     (do (ui/toggle-debug-pathgrid?) true)    ; toggle pathgrid overlay
          ;; Escape is context-sensitive: cancel the active tool (zoning or build)
          ;; first; only when no tool is active does it open the pause menu
          ;; (RimWorld backs out of the current tool before the menu).
          Input$Keys/ESCAPE (do (if (#{:zone-stockpile :build} (ui/mode))
                                  (do (ui/set-mode! :select) (ui/clear-drag!))
                                  (when on-open-pause-menu (on-open-pause-menu)))
                                true)
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
                ;; In zoning mode left starts a drag-rectangle and right cancels
                ;; the mode; in build mode left places/deconstructs and right
                ;; exits; otherwise the usual select / order commands.
                Input$Buttons/LEFT
                (cond
                  (= (ui/mode) :zone-stockpile)
                  ;; Shift held at drag-start makes this an ERASE drag; the flag
                  ;; sticks for the whole drag (drives preview color + commit).
                  (ui/set-drag! {:start [tx ty] :current [tx ty] :erase? (shift-down?)})

                  (= (ui/mode) :build)
                  (if (shift-down?)
                    (command/deconstruct-wall! tx ty)
                    (command/build-wall! tx ty))

                  :else (command/left-click! tx ty))

                Input$Buttons/RIGHT
                (if (#{:zone-stockpile :build} (ui/mode))
                  (do (ui/set-mode! :select) (ui/clear-drag!))
                  (command/right-click! tx ty))

                nil))
            true)))

      (touchDragged [screen-x screen-y _pointer]
        (when-let [{:keys [button x y]} @drag]
          (cond
            (= (int button) Input$Buttons/MIDDLE)
            (let [zoom (double (:zoom (ui/camera)))
                  dx   (- (long screen-x) (long x))
                  dy   (- (long screen-y) (long y))]
              ;; grab-and-drag: world follows cursor → camera moves opposite.
              ;; screen Y is down, world Y up → dy not negated.
              (ui/pan! (* (- dx) zoom) (* dy zoom)))

            ;; Left-drag in zoning mode grows the preview rectangle to the
            ;; current tile (commit happens on touchUp).
            (and (= (int button) Input$Buttons/LEFT) (= (ui/mode) :zone-stockpile))
            (let [height  (long (:height (:grid (world-fn))))
                  [tx ty] (screen->tile (camera-fn) tile-size height screen-x screen-y)]
              (when-let [d (ui/drag)]
                (ui/set-drag! (assoc d :current [tx ty])))))
          (swap! drag assoc :x screen-x :y screen-y))
        true)

      (touchUp [_screen-x _screen-y _pointer _button]
        (let [button (:button @drag)]
          (reset! drag nil)
          (if (and button
                   (= (int button) Input$Buttons/LEFT)
                   (= (ui/mode) :zone-stockpile)
                   (ui/drag))
            (let [{:keys [start current erase?]} (ui/drag)]
              (if erase?
                (command/erase-stockpile!  start current)
                (command/commit-stockpile! start current))
              (ui/clear-drag!)
              true)
            false))))))
