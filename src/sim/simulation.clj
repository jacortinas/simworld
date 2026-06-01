(ns sim.simulation
  "The per-tick world transformation.

   `tick` is a PURE function: (tick world) -> new-world. It advances the clock
   then runs the staggered scheduler (sim.schedule), which dispatches the
   registered band systems. Most things do NOT run every tick — only :normal
   systems do; :rare/:long work runs over the staggered bucket due this tick."
  (:require
   [sim.ai       :as ai]
   [sim.defs     :as defs]
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

(defn- decay-needs
  "Decrement each need toward 0 by one rare-tick's worth. The per-need rate is
   content (sim.defs/need-decay, from resources/defs/needs.edn); all needs ship
   at the same 0.0125 so behavior is unchanged."
  [pawn]
  (update pawn :needs
          (fn [needs]
            (reduce-kv
             (fn [m k v] (assoc m k (max 0.0 (- (double v) (defs/need-decay k)))))
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
  "Normal + sub-throttled: idle pawns pick behavior on their staggered rare tick.
   Gates on `due?` FIRST (cheap id math that skips ~124/125 of pawns) before the
   entity fetch and idle check, so a non-due or busy pawn costs almost nothing.
   Behavior matches the old order: ai/redeliberate already no-ops a pawn with a
   job, so skipping the call for a busy pawn is equivalent."
  [world _due]
  (let [clock    (long (:clock world))
        interval (long (:rare schedule/bands))]
    (reduce (fn [w stale]
              (let [id (:id stale)]
                (if (schedule/due? clock interval id)
                  (let [pawn (entity/entity w id)]
                    (if (and pawn (nil? (:job pawn)))
                      (ai/redeliberate w pawn)
                      w))
                  w)))
            world
            (entity/pawns world))))

(defn deteriorate-system
  "Long-band system that WOULD deteriorate the due :item entities. Deliberately
   NOT registered (see below): it is a no-op until items actually deteriorate,
   and run* skips any band with no system — so registering a no-op here would
   just burn a due-bucket computation every tick for nothing. Items are still
   `:ticker-type :long` and bucketed, so the day this does real work, one
   `register-system! :long` call lights it up over the ready buckets. `due` is
   the long bucket's items."
  [world due]
  (reduce (fn [w _item] w) world due))

;; ---------------------------------------------------------------------------
;; Registration. Idempotent across reloads (register-system! replaces by name).
;; Order within :normal: events -> decay -> advance-jobs -> redeliberate, so
;; needs decay before any decision, matching the old step-pawns ordering.
;;
;; Only :normal systems are registered. No entity type does rare-banded work
;; yet, and deterioration (:long) isn't implemented; since run* skips a band
;; with no registered system, the scheduler does ZERO work for :rare/:long
;; while their buckets stay maintained (items live in :long, ready). Register a
;; :rare/:long system the moment one has real work — no other wiring changes.
;; ---------------------------------------------------------------------------

(schedule/register-system! :normal ::process-events process-events-system)
(schedule/register-system! :normal ::decay-needs    decay-needs-system)
(schedule/register-system! :normal ::advance-jobs   advance-jobs-system)
(schedule/register-system! :normal ::redeliberate   redeliberate-idle-system)

;; ---------------------------------------------------------------------------

(defn tick
  "Advance the world one fixed-timestep tick: bump the clock, then run the
   staggered scheduler over the registered systems."
  [world]
  (-> world advance-clock schedule/run))
