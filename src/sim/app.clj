(ns sim.app
  "App-level state machine above the sim clock.

   Owns the {:screen :worldgen :pause-menu} atom and is the SINGLE place
   that orchestrates `sim.clock`, `sim.world`, and screen transitions in
   tandem. Screens read `current-screen` each frame to decide what to draw;
   the worldgen background future writes phase + result via `swap! app
   next-app-state` (the pure update fn).

   `next-app-state` is fully unit-tested. The effectful transition wrappers
   below are thin orchestrators over the pure fn plus `clock/start!` /
   `clock/stop!` / `setInputProcessor` — too thin to merit their own tests;
   the *logic* lives in the pure fn."
  (:require
   [sim.clock    :as clock]
   [sim.tile     :as tile]
   [sim.world    :as world]
   [sim.worldgen :as worldgen])
  (:import
   (com.badlogic.gdx Gdx)))

(set! *warn-on-reflection* true)

(def initial-app
  "The boot value. Also what :quit-to-menu resets the whole atom to."
  {:screen     :main-menu                ; | :worldgen | :play | :pause-menu
   :worldgen   {:status :idle            ; | :running | :done | :failed
                :phase  nil              ; | :terrain | :detail
                :result nil              ; the generated world, or nil
                :error  nil}             ; a Throwable, or nil
   :pause-menu {:was-paused? false}})    ; captured on enter-pause-menu

(defonce ^{:doc "The app atom. defonce so REPL reloads preserve current state."}
  app
  (atom initial-app))

(defn current-screen
  "The :screen the render loop should dispatch on this frame."
  []
  (:screen @app))

;; ---------------------------------------------------------------------------
;; PURE state-update logic. Tested exhaustively in sim.app-test.
;; ---------------------------------------------------------------------------

(defn next-app-state
  "Pure: (app event & args) -> new-app. Does NOT spawn futures, mutate the
   world, or call libGDX. The effectful wrappers (enter-worldgen!, etc.)
   compose this with their side effects.

   Events:
     :enter-worldgen           (no args)
     :phase                    [phase-kw]
     :worldgen-done            [world]
     :worldgen-failed          [throwable]
     :enter-play               (no args)
     :enter-pause-menu         [was-paused?]
     :resume-from-pause-menu   (no args)
     :quit-to-menu             (no args)"
  [app event & args]
  (case event
    :enter-worldgen
    (if (= :running (get-in app [:worldgen :status]))
      app                                                      ; re-entry guard
      (assoc app
             :screen   :worldgen
             :worldgen {:status :running :phase :terrain
                        :result nil      :error nil}))

    :phase
    (let [[phase] args]
      (assoc-in app [:worldgen :phase] phase))

    :worldgen-done
    (let [[world] args]
      (assoc app :worldgen {:status :done :phase nil
                            :result world :error nil}))

    :worldgen-failed
    (let [[t] args]
      (assoc app :worldgen {:status :failed :phase nil
                            :result nil :error t}))

    :enter-play
    (assoc app :screen   :play
               :worldgen (:worldgen initial-app))

    :enter-pause-menu
    (let [[was-paused?] args]
      (assoc app :screen     :pause-menu
                 :pause-menu {:was-paused? (boolean was-paused?)}))

    :resume-from-pause-menu
    (assoc app :screen     :play
               :pause-menu (:pause-menu initial-app))

    :quit-to-menu
    initial-app

    (throw (ex-info "Unknown app event" {:event event :args args}))))

;; ---------------------------------------------------------------------------
;; Effectful transition wrappers. Pure update + ≤2 side effects each:
;; clock control, world reset, libGDX input-processor swap.
;;
;; The processors atom is populated by sim.render.gdx/create once GL is up
;; (each screen's make-processor needs GL resources to build its hit-test).
;; Before that population happens, set-processor! is a no-op — safe for tests
;; that exercise the transitions without a live libGDX context.
;; ---------------------------------------------------------------------------

(defonce ^{:doc "Map of screen-keyword -> built InputProcessor. Populated by
                 sim.render.gdx/create after GL resources exist."}
  processors
  (atom {}))

(defn- set-processor!
  "Apply the InputProcessor for `screen`. No-op if processors aren't built yet
   (e.g. in tests) or if libGDX hasn't initialized."
  [screen]
  (when-let [p (get @processors screen)]
    (when-let [input (Gdx/input)]
      (.setInputProcessor input p))))

(defn transition!
  "Apply a state event via the pure update fn and, if :screen changed, swap
   the libGDX input processor to match. Returns the new app value."
  [event & args]
  (let [old-screen (:screen @app)
        new-app    (apply swap! app next-app-state event args)]
    (when (not= old-screen (:screen new-app))
      (set-processor! (:screen new-app)))
    new-app))

;; ---------------------------------------------------------------------------
;; The transitions, called from screens' input processors and from the
;; worldgen screen's per-frame :status :done observation.
;; ---------------------------------------------------------------------------

(defn enter-worldgen!
  "Kick off async worldgen on a background future. The future writes phase
   updates and a final result/error into the app atom via next-app-state;
   the :worldgen screen polls them each frame. Idempotent while already
   running — the re-entry guard lives inside next-app-state; this fn only
   spawns the future when the swap actually transitioned out of a non-running
   state (using swap-vals! to make the check atomic with the CAS)."
  []
  (let [[old _new] (swap-vals! app next-app-state :enter-worldgen)]
    (when (not= :running (get-in old [:worldgen :status]))
      (set-processor! :worldgen)
      (future
        (try
          (let [seed   (System/nanoTime)
                opts   {:seed seed
                        :on-phase (fn [phase]
                                    (swap! app next-app-state :phase phase))}
                world' (worldgen/generate (world/initial-world {}) opts)]
            (swap! app next-app-state :worldgen-done world'))
          (catch Throwable t
            (binding [*out* *err*]
              (println "[sim.app] worldgen failed:")
              (.printStackTrace t))
            (swap! app next-app-state :worldgen-failed t)))))))

(def ^:private starter-names
  ["Alice" "Bob" "Cleo" "Dave" "Erin"])

(defn- walkable-tiles-near-center
  "Walkable [x y] coords in row-major order starting from map center,
   spiraling outward. Returns a (lazy) seq."
  [grid]
  (let [w  (long (:width grid))
        h  (long (:height grid))
        cx (quot w 2) cy (quot h 2)]
    (for [r (range (max w h))
          dy (range (- r) (inc r))
          dx (range (- r) (inc r))
          :let [x (+ cx dx) y (+ cy dy)]
          :when (and (= (max (Math/abs (long dx)) (Math/abs (long dy))) r)
                     (<= 0 x (dec w)) (<= 0 y (dec h))
                     (tile/passable? (tile/tile-at grid x y)))]
      [x y])))

(defn seed-colony!
  "Place 3 starter pawns on walkable tiles starting from map center and
   spiraling outward. Throws if fewer than 3 walkable tiles exist."
  []
  (let [grid  (:grid @world/world)
        tiles (vec (take 3 (distinct (walkable-tiles-near-center grid))))]
    (when (< (count tiles) 3)
      (throw (ex-info "seed-colony!: not enough walkable tiles"
                      {:grid-size [(:width grid) (:height grid)]
                       :found     (count tiles)})))
    (let [names (vec (take 3 (shuffle starter-names)))]
      (dotimes [i 3]
        (world/spawn-pawn! (nth names i) (nth tiles i))))))

(defn enter-play!
  "Called by the :worldgen screen on the GL thread when it observes
   :status :done. Atomically: install generated world, seed pawns, start
   clock (boots paused), swap to :play and matching input processor.
   On any failure: log to *err*, reset to a blank world, return to :main-menu."
  [world']
  (try
    (reset! world/world world')
    (seed-colony!)
    (clock/start!)
    (transition! :enter-play)
    (catch Throwable t
      (binding [*out* *err*]
        (println "[sim.app] enter-play! failed; rolling back to main menu:")
        (.printStackTrace t))
      (reset! world/world (world/initial-world {}))
      (transition! :quit-to-menu))))

(defn enter-pause-menu!
  "Open the pause-menu overlay. Captures the current paused? state so Resume
   can restore it; calls clock/pause! (idempotent if already paused)."
  []
  (let [was-paused? (clock/paused?*)]
    (clock/pause!)
    (transition! :enter-pause-menu was-paused?)))

(defn resume-from-pause-menu!
  "Close the pause-menu. If the clock was running before the menu opened,
   call clock/resume!; if it was already paused, leave it paused — the
   safety net that prevents the menu from silently un-pausing a manually
   paused game."
  []
  (let [was-paused? (get-in @app [:pause-menu :was-paused?])]
    (when-not was-paused?
      (clock/resume!))
    (transition! :resume-from-pause-menu)))

(defn quit-to-menu!
  "Return to main menu. Stops the clock (thread join), clears world, fully
   resets app state. Reachable from the pause menu's Quit to Menu button or
   from the worldgen screen's Back button on failure."
  []
  (clock/stop!)
  (reset! world/world (world/initial-world {}))
  (transition! :quit-to-menu))

(defn quit-game!
  "Ask libGDX to exit. The window's dispose then runs (which calls clock/stop!
   defensively), main thread falls through to shutdown-agents, process exits."
  []
  (when-let [g (Gdx/app)] (.exit g)))
