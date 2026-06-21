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

(defn advance-job
  "Job-execution step for one pawn — runs every tick. Clears a finished job;
   otherwise advances it one tick. There is NO speed gate here: move speed lives
   in sim.movement/segment-cost (ticks per cell), so the glide accumulates smoothly,
   one tick at a time. (The old 15-tick gate complected walk speed with the
   scheduler's deliberation cadence — two unrelated concerns.) Pure:
   (world, pawn) -> world."
  [world pawn]
  (cond
    ;; Clean up a finished job — pawn is idle starting this tick.
    (and (:job pawn) (job/done? (:job pawn)))
    (entity/update-entity world (:id pawn) assoc :job nil)

    (:job pawn)
    (job/advance world pawn)

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
