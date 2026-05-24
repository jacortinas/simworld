(ns sim.simulation
  "The per-tick world transformation.

   `tick` is a PURE function: (tick world) -> new-world.
   The game loop calls (swap! world simulation/tick) at fixed 30 Hz.

   Right now this just bumps the clock, drains pending events, and decays
   each pawn's needs slightly. As we grow the sim, new responsibilities
   (pathfinding step, job scheduling, AI deliberation) become composable
   sub-transformations of the world value."
  (:require
   [sim.ai     :as ai]
   [sim.entity :as entity]
   [sim.events :as events]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Sub-systems. Each takes and returns a world.
;; ---------------------------------------------------------------------------

(defn advance-clock
  [world]
  (update world :clock inc))

(defn process-events
  "Drain the queue and apply each event. Events enqueued *during* processing
   land back on the queue for the next tick — keeps the pipeline simple."
  [world]
  (let [[evs world'] (events/drain world)]
    (events/apply-events world' evs)))

(def ^:private ^:const need-decay-per-tick 0.0001)

(defn- decay-needs
  "Decrement each need toward 0. Clamped to 0 below."
  [pawn]
  (update pawn :needs
          (fn [needs]
            (reduce-kv
             (fn [m k v]
               (assoc m k (max 0.0 (- (double v) need-decay-per-tick))))
             {} needs))))

(defn step-pawns
  "Per-tick per-pawn updates: need decay, then AI decision.

   The reduce threads `w` so each pawn sees the world updated by the
   previous pawn in this tick. We re-fetch the pawn from the threaded
   world after decay so `ai/decide` sees the freshest version."
  [world]
  (reduce
   (fn [w pawn-stale]
     (let [w' (entity/update-entity w (:id pawn-stale) decay-needs)
           pawn (entity/entity w' (:id pawn-stale))]
       (ai/decide w' pawn)))
   world
   (entity/pawns world)))

;; ---------------------------------------------------------------------------
;; The tick function. Composition order matters — keep it explicit.
;; ---------------------------------------------------------------------------

(defn tick
  [world]
  (-> world
      advance-clock
      process-events
      step-pawns))
