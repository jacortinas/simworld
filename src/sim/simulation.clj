(ns sim.simulation
  "The per-tick world transformation.

   `tick` is a PURE function: (tick world) -> new-world. It advances the clock
   then runs the staggered scheduler (sim.schedule), which dispatches the
   registered band systems. Most things do NOT run every tick — only :normal
   systems do; :rare/:long work runs over the staggered bucket due this tick.
   See docs/superpowers/specs/2026-05-24-tick-bands-design.md."
  (:require
   [sim.ai       :as ai]
   [sim.entity   :as entity]
   [sim.events   :as events]
   [sim.schedule :as schedule]))

(set! *warn-on-reflection* true)

(defn advance-clock [world] (update world :clock inc))

;; ---------------------------------------------------------------------------
;; Systems. Each: (world due-entities) -> world. :normal systems get nil due
;; and select their own targets; :rare/:long get the due bucket's entities.
;; ---------------------------------------------------------------------------

(defn process-events-system
  "Normal: drain and apply the event queue."
  [world _due]
  (let [[evs world'] (events/drain world)]
    (events/apply-events world' evs)))

;; 0.0125 = old per-tick rate (0.0001) x rare interval (125), so the effective
;; decay-per-second is unchanged now that decay fires ~once per 125 ticks.
(def ^:private ^:const need-decay-per-rare 0.0125)

(defn- decay-needs
  "Decrement each need toward 0 by one rare-tick's worth."
  [pawn]
  (update pawn :needs
          (fn [needs]
            (reduce-kv
             (fn [m k v] (assoc m k (max 0.0 (- (double v) need-decay-per-rare))))
             {} needs))))

(defn decay-needs-system
  "Normal + sub-throttled: decay each pawn's needs on its staggered rare tick."
  [world _due]
  (let [clock    (long (:clock world))
        interval (long (:rare schedule/bands))]
    (reduce (fn [w pawn]
              (if (schedule/due? clock interval (:id pawn))
                (entity/update-entity w (:id pawn) decay-needs)
                w))
            world
            (entity/pawns world))))

(defn advance-jobs-system
  "Normal: every pawn with a job advances it (clearing finished jobs)."
  [world _due]
  (reduce (fn [w stale]
            (if-let [pawn (entity/entity w (:id stale))]
              (ai/advance-job w pawn)
              w))
          world
          (entity/pawns world)))

(defn redeliberate-idle-system
  "Normal + sub-throttled: idle pawns pick behavior on their staggered rare tick."
  [world _due]
  (let [clock    (long (:clock world))
        interval (long (:rare schedule/bands))]
    (reduce (fn [w stale]
              (let [pawn (entity/entity w (:id stale))]
                (if (and pawn (schedule/due? clock interval (:id stale)))
                  (ai/redeliberate w pawn)
                  w)))
            world
            (entity/pawns world))))

(defn deteriorate-system
  "Long: would deteriorate the due :item entities. Stub until items deteriorate.
   `due` is the long bucket's items; no-op for now."
  [world due]
  (reduce (fn [w _item] w) world due))

;; ---------------------------------------------------------------------------
;; Registration. Idempotent across reloads (register-system! replaces by name).
;; Order within :normal: events -> decay -> advance-jobs -> redeliberate, so
;; needs decay before any decision, matching the old step-pawns ordering.
;; ---------------------------------------------------------------------------

(schedule/register-system! :normal ::process-events process-events-system)
(schedule/register-system! :normal ::decay-needs    decay-needs-system)
(schedule/register-system! :normal ::advance-jobs   advance-jobs-system)
(schedule/register-system! :normal ::redeliberate   redeliberate-idle-system)
(schedule/register-system! :long   ::deteriorate    deteriorate-system)

;; ---------------------------------------------------------------------------

(defn tick
  "Advance the world one fixed-timestep tick: bump the clock, then run the
   staggered scheduler over the registered systems."
  [world]
  (-> world advance-clock schedule/run))
