(ns sim.app
  "App-level state machine above the sim clock.

   Owns the {:screen :worldgen :pause-menu} atom and is the SINGLE place
   that orchestrates `sim.clock`, `sim.world`, and screen transitions in
   tandem. Screens read `current-screen` each frame to decide what to draw;
   the worldgen background future writes phase + result via `swap! app
   next-app-state` (the pure update fn).

   `next-app-state` is fully unit-tested. The effectful transition wrappers
   (added in a follow-up task) are thin orchestrators over the pure fn plus
   `clock/start!` / `clock/stop!` / `setInputProcessor` — too thin to merit
   their own tests; the *logic* lives in the pure fn.")

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
