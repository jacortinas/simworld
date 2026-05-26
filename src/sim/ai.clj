(ns sim.ai
  "Pawn decision-making, split into the two cadences the scheduler drives:
   `advance-job` (job execution, runs every tick) and `redeliberate` (idle
   choice, which the caller sub-throttles to the rare band). Both are pure
   (world, pawn) -> world; the return is a *world* (not a pawn) because some
   jobs mutate multiple entities — see sim.job/advance."
  (:require
   [sim.entity :as entity]
   [sim.job    :as job]
   [sim.think  :as think]))

(set! *warn-on-reflection* true)

(def ^:const ^:private move-period
  "Ticks between movement decisions. At 30 Hz sim, 15 = twice per second."
  15)

(defn- moves-this-tick?
  "Stagger pawns by id so they don't all twitch in lockstep."
  [world pawn]
  (zero? (mod (+ (long (:clock world)) (long (:id pawn))) move-period)))

(defn advance-job
  "Job-execution step for one pawn — runs every tick. Clears a finished job;
   otherwise advances it (the physical step is gated by the pawn's move
   cadence). Pure: (world, pawn) -> world."
  [world pawn]
  (cond
    ;; Clean up a finished job — pawn is idle starting this tick.
    (and (:job pawn) (job/done? (:job pawn)))
    (entity/update-entity world (:id pawn) assoc :job nil)

    ;; Active job: compute path immediately, step on gated ticks.
    (:job pawn)
    (if (nil? (get-in pawn [:job :path]))
      (job/advance world pawn)
      (if (moves-this-tick? world pawn)
        (job/advance world pawn)
        world))

    :else world))

(defn redeliberate
  "Idle-pawn deliberation: walk the think-tree (sim.think) to pick a behavior,
   then assign the resulting job through the one chokepoint. The assignment is
   AUTO (not forced), so the reservation gate applies — a pawn won't be handed a
   target another pawn already claims. No job yielded -> the pawn stays idle this
   pass. The CALLER sub-throttles this to the rare band. Pure: (world,pawn)->world."
  [world pawn]
  (if (:job pawn)
    world
    (if-let [job (think/deliberate world pawn)]
      (job/assign world (:id pawn) job)
      world)))
