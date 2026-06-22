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
     1 / 2 / 3    → set sim speed (slow / medium / fast)
     Z            → enter stockpile zoning mode
     B            → enter wall build mode
     O            → enter door build mode
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
   [sim.tools    :as tools]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx Gdx InputAdapter Input$Buttons Input$Keys)
   (com.badlogic.gdx.graphics OrthographicCamera)
   (com.badlogic.gdx.math Vector3)))

(set! *warn-on-reflection* true)

(def ^:private tool-keys
  "Keycode -> the ui-state :mode it enters. The one input-layer half of a tool
   (libGDX keys live here, behavior lives in sim.tools); adding a tool's hotkey
   is a one-line edit here, not a new keyDown branch."
  ;; B/O are per-building hotkeys; a categorized build MENU will subsume them
  ;; later (then a tool carries which def to place, not a dedicated key each).
  {(int Input$Keys/Z) :zone-stockpile
   (int Input$Keys/B) :build
   (int Input$Keys/O) :build-door})

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
     :on-set-speed         -> (fn [mult]) invoked on the 1/2/3 keys (optional)
     :on-open-pause-menu   -> (fn []) invoked on the Escape key (optional)

   on-ui-click / on-toggle-pause / on-set-speed / on-open-pause-menu are
   injected (rather than required) so this namespace stays decoupled from the
   clock, the HUD, and the game loop: easy to test with stub fns."
  [{:keys [camera-fn tile-size world-fn on-ui-click on-toggle-pause on-set-speed on-open-pause-menu]}]
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
        (let [kc (int keycode)]
          (if-let [m (tool-keys kc)]
            (do (ui/set-mode! m) true)              ; enter a placement tool (data-driven)
            (condp = kc
              ;; space → pause is INJECTED (on-toggle-pause) to keep this ns from
              ;; importing sim.clock. The debug toggle is called DIRECTLY because
              ;; we already depend on sim.ui-state (zoom/pan) -- no new coupling.
              Input$Keys/SPACE  (do (when on-toggle-pause (on-toggle-pause)) true)
              ;; 1/2/3 set sim speed. Injected (on-set-speed) for the same reason
              ;; space is: keep this proxy from importing sim.clock.
              Input$Keys/NUM_1  (do (when on-set-speed (on-set-speed 1.0)) true)
              Input$Keys/NUM_2  (do (when on-set-speed (on-set-speed 2.0)) true)
              Input$Keys/NUM_3  (do (when on-set-speed (on-set-speed 3.0)) true)
              Input$Keys/GRAVE  (do (ui/toggle-debug!) true)   ; backtick ` : debug overlay
              Input$Keys/F1     (do (ui/toggle-debug-regions?) true)     ; toggle region overlay
              Input$Keys/F2     (do (ui/toggle-debug-pathgrid?) true)    ; toggle pathgrid overlay
              Input$Keys/F3     (do (ui/toggle-debug-rooms?) true)       ; toggle rooms overlay
              ;; Escape is context-sensitive: back out of the active tool first;
              ;; only with no tool active does it open the pause menu (RimWorld
              ;; backs out of the current tool before the menu).
              Input$Keys/ESCAPE (do (if (tools/tool (ui/mode))
                                      (do (ui/set-mode! :select) (ui/clear-drag!))
                                      (when on-open-pause-menu (on-open-pause-menu)))
                                    true)
              false))))

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
                ;; Left press: if a placement tool owns this mode, a drag tool
                ;; begins a rectangle and a click tool fires immediately; with no
                ;; tool active it is the default select command. Right press backs
                ;; out of any active tool, else issues a move order.
                Input$Buttons/LEFT
                (if-let [t (tools/tool (ui/mode))]
                  (if (:drag? t)
                    ;; Shift held at drag-start makes this an ERASE drag; the flag
                    ;; sticks for the whole drag (drives preview color + commit).
                    (ui/set-drag! {:start [tx ty] :current [tx ty] :erase? (shift-down?)})
                    ((:on-click t) tx ty (shift-down?)))
                  (command/left-click! tx ty))

                Input$Buttons/RIGHT
                (if (tools/tool (ui/mode))
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

            ;; Left-drag under a rectangle tool grows the preview rect to the
            ;; current tile (commit happens on touchUp).
            (and (= (int button) Input$Buttons/LEFT)
                 (some-> (tools/tool (ui/mode)) :drag?))
            (let [height  (long (:height (:grid (world-fn))))
                  [tx ty] (screen->tile (camera-fn) tile-size height screen-x screen-y)]
              (when-let [d (ui/drag)]
                (ui/set-drag! (assoc d :current [tx ty])))))
          (swap! drag assoc :x screen-x :y screen-y))
        true)

      (touchUp [_screen-x _screen-y _pointer _button]
        (let [button (:button @drag)
              t      (tools/tool (ui/mode))]
          (reset! drag nil)
          (if (and button
                   (= (int button) Input$Buttons/LEFT)
                   (:drag? t)
                   (ui/drag))
            (let [{:keys [start current erase?]} (ui/drag)]
              ((:on-commit t) start current erase?)
              (ui/clear-drag!)
              true)
            false))))))
