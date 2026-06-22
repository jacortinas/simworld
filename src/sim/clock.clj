(ns sim.clock
  "The simulation clock — a fixed-timestep driver for `sim.simulation/tick`.

   This is NOT 'the game loop' in the textbook sense (input → update →
   render). It owns only the UPDATE step: it advances world-state at a fixed
   30 Hz. Input and rendering live on the libGDX thread (sim.render.gdx),
   which reads @world independently — both observe the same atomic snapshot,
   so no coordination is needed. The two threads together make up the
   conceptual game loop, split with the world atom as the hand-off.

   Two distinct axes, easy to conflate:
     - `running?` is LIFECYCLE — does the clock thread exist? Flipped by
       start!/stop!, which spawn/join the thread (heavyweight, done rarely).
     - `paused?` is sim-TIME control — should the live clock advance ticks?
       Flipped by pause!/resume!/toggle-pause! (just an atom write, instant
       and safe to call from the GL thread on a UI click). Pausing freezes
       sim-time only; the render/input loop keeps running.

   The clock runs on a daemon thread spawned via `future`. `defonce` atoms
   hold both flags so REPL reloads don't lose track of a running clock."
  (:require
   [sim.simulation :as simulation]
   [sim.world      :as world]))

(set! *warn-on-reflection* true)

(def ^:const tick-hz             30)
(def ^:const ^long tick-nanos    (long (/ 1e9 tick-hz)))

(defonce ^:private running? (atom false))
(defonce ^:private loop-future (atom nil))

;; Boots PAUSED: the clock thread comes up alive but idle, so the first
;; action is to press play. Nothing in the world moves until then.
(defonce ^:private paused? (atom true))

;; Speed is the THIRD time axis (after running? and paused?): a tick-rate
;; multiplier, RimWorld's 1x/2x/3x. Because the whole sim is denominated in
;; ticks (movement segment-cost, needs decay, the schedule bands), one
;; multiplier scales everything uniformly. It changes only how often a tick
;; fires in WALL-CLOCK, never the tick SEQUENCE, so same-seed runs stay
;; bit-identical. Orthogonal to pause: un-pausing resumes at the last speed.
(defonce ^:private speed (atom 1.0))

(defn- drain-ticks
  "Apply simulation ticks until the accumulator is below one effective tick
   interval. `interval` is tick-nanos divided by the speed multiplier, so a
   faster speed crosses the threshold more often and drains more ticks per
   call. Returns the remaining accumulator. The inner loop returns a value
   (the leftover acc) rather than trying to recur up to a caller."
  ^long [^long acc ^long interval]
  (loop [acc acc]
    (if (>= acc interval)
      (do (swap! world/world simulation/tick)
          (recur (- acc interval)))
      acc)))

(defn- run-loop! []
  (loop [prev    (System/nanoTime)
         sim-acc 0]
    (when @running?
      (let [now      (System/nanoTime)
            elapsed  (- now prev)
            sim-acc' (if @paused?
                       sim-acc
                       (drain-ticks (+ sim-acc elapsed)
                                    (long (/ (double tick-nanos) (double @speed)))))]
        (Thread/sleep 1)
        (recur now sim-acc')))))

(defn start!
  "Start the simulation clock on a background thread. Idempotent — does
   nothing if already running."
  []
  (when-not @running?
    (reset! running? true)
    (reset! loop-future
            (future
              (try (run-loop!)
                   (catch Throwable e
                     (binding [*out* *err*]
                       (println "[sim.clock] crashed:" (.getMessage e))
                       (.printStackTrace e)))
                   (finally
                     (reset! running? false)))))
    :started))

(defn stop!
  "Signal the clock to stop and wait briefly for the thread to exit."
  []
  (reset! running? false)
  (when-let [f @loop-future]
    (try
      (deref f 1000 :timeout)
      (catch Throwable _))
    (reset! loop-future nil))
  :stopped)

(defn running?* []
  @running?)

;; ---------------------------------------------------------------------------
;; Pause control. These only flip an atom — instant and thread-safe, so the
;; libGDX click handler / key handler can call them directly on the GL thread
;; without the blocking thread-join that stop! does.
;; ---------------------------------------------------------------------------

(defn pause!  [] (reset! paused? true)  :paused)
(defn resume! [] (reset! paused? false) :running)

(defn toggle-pause!
  "Flip paused state. Returns the new state (:paused or :running)."
  []
  (if (swap! paused? not) :paused :running))

(defn paused?* [] @paused?)

;; ---------------------------------------------------------------------------
;; Speed control. set-speed! only sets the multiplier (orthogonal to pause);
;; the UI's "select a speed = un-pause" policy lives in the caller (it pairs
;; set-speed! with resume!), keeping this primitive single-purpose.
;; ---------------------------------------------------------------------------

(defn set-speed!
  "Set the tick-rate multiplier (e.g. 1.0, 2.0, 3.0). Clamped positive so the
   effective interval can never be zero or negative. Returns the new speed."
  [mult]
  (reset! speed (max 0.1 (double mult))))

(defn speed* [] @speed)
