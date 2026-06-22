(ns sim.movement
  "Grid-faithful gliding movement: advance a pawn ONE TICK along its job's A*
   path. This is the shared movement core every walk-to-target job reuses
   (:go-to, haul's two walk phases, eat's walk phase), so it lives apart from any
   single job type (sim.job dispatches the FSMs and calls walk-toward here).

   RimWorld's model: a pawn always LOGICALLY occupies an integer cell; the smooth
   glide is a render lie (sim.render.interp lerps :from -> :pos). Three load-
   bearing rules carried over from the old in-job implementation:
     1. :pos flips to the destination cell when a segment STARTS (the pawn
        occupies the cell it is ENTERING: reservation/collision honesty).
     2. Speed is in TICKS, never wall-clock: segment-cost = base move-ticks x the
        unified traversal-cost A* minimizes, so the route chosen is the fastest
        to walk.
     3. The progress float lives only in render; the sim stays integer-tick, so
        same-seed runs are bit-identical.

   walk-toward returns [result world'] where result is :arrived | :walking |
   :failed. Progress lives in (:job pawn): :path / :path-index, plus a :move
   record {:from :to :elapsed :cost} for the segment in flight."
  (:require
   [sim.door        :as door]
   [sim.entity      :as entity]
   [sim.pathfinding :as pathfinding]
   [sim.pathgrid    :as pathgrid]))

(set! *warn-on-reflection* true)

(defn set-job
  "Update a pawn's :job via (apply update job f args). The shared pawn-job
   mutation helper used by the movement core here AND by sim.job's FSM
   transitions (mark-state, next-phase, mark-in-progress); it lives in this lower
   layer so both call one definition with the dependency pointing job -> movement."
  [world pawn-id f & args]
  (entity/update-entity world pawn-id
                        (fn [p] (apply update p :job f args))))

(defn segment-cost
  "Ticks for a pawn with `move-ticks` base speed to cross from `from` into the
   adjacent cell `to`: base x the unified traversal-cost (terrain move-cost, x sqrt2
   for a diagonal), rounded, clamped to a 1-tick minimum. The same currency
   sim.pathfinding minimizes, so the route A* prefers is the fastest to walk."
  [grid move-ticks from to]
  (max 1 (Math/round (* (double move-ticks)
                        (pathfinding/traversal-cost grid from to)))))

(defn- start-segment
  "Begin gliding from the pawn's current cell into path cell `next-i`. Flips :pos
   to that cell immediately (the pawn now occupies the cell it is ENTERING,
   decision D2) and records the from/to/cost it must glide across."
  [world pawn next-i]
  (let [pid  (:id pawn)
        from (:pos pawn)
        to   ((get-in pawn [:job :path]) next-i)
        cost (segment-cost (:grid world) (:move-ticks pawn) from to)]
    (entity/update-entity world pid
                          (fn [p]
                            (-> p
                                (assoc :pos to)
                                (assoc-in [:job :path-index] next-i)
                                (assoc-in [:job :move]
                                          {:from from :to to :elapsed 0 :cost cost}))))))

(defn- next-cell-passable?
  "Is path cell `next-i` still passable in `world`'s current PathGrid? A wall
   built across a walking pawn's route turns this false."
  [world pawn next-i]
  (let [[nx ny] ((get-in pawn [:job :path]) next-i)]
    (pathgrid/passable? (pathgrid/for-world world) nx ny)))

(defn- step-or-replan
  "Begin gliding into path cell `next-i`. Three outcomes:
   - a WALL now blocks the cell: nil the path so walk-toward's (nil? path) branch
     replans for free (a runtime wall on the route);
   - a not-yet-open DOOR holds the cell: STALL (return the world unchanged). The
     pawn stays settled one cell away and re-polls next tick; sim.door's system,
     seeing this pawn waiting, swings the door open. Determinism is preserved: the
     wait is exactly the door's :open-ticks, all in integer ticks;
   - otherwise start the glide.
   Order matters: a door is PASSABLE in the PathGrid, so the wall check never
   fires on it; the door gate is the second cond."
  [world pawn next-i]
  (let [cell ((get-in pawn [:job :path]) next-i)]
    (cond
      (not (next-cell-passable? world pawn next-i))
      [:walking (set-job world (:id pawn) assoc :path nil :path-index 0 :move nil)]

      (door/blocking? world cell)
      [:walking world]

      :else
      [:walking (start-segment world pawn next-i)])))

(defn walk-toward
  "Advance the pawn ONE TICK toward `target`, gliding sub-cell. Returns
   `[result world']`:
     :arrived  pawn has reached and finished gliding into target
     :walking  path computed, a segment started, or a glide advanced
     :failed   no path exists
   Progress lives in (:job pawn): :path / :path-index, plus a :move record
   {:from :to :elapsed :cost} for the segment in flight. :pos flips to the
   destination cell when a segment STARTS (D2); the glide to :cost ticks is just
   how long the DRAWN position takes to catch up (see sim.render.interp)."
  [world pawn target]
  (let [job   (:job pawn)
        path  (:path job)
        pid   (:id pawn)
        last? (fn [i] (>= (long i) (dec (count path))))]
    (cond
      ;; First call: compute the path. Don't move yet.
      (nil? path)
      (if-let [new-path (pathfinding/find-path world (:pos pawn) target)]
        [:walking (set-job world pid assoc :path new-path :path-index 0 :move nil)]
        [:failed world])

      ;; Gliding across a cell: spend one tick on the segment in flight.
      (:move job)
      (let [{:keys [elapsed cost]} (:move job)
            elapsed' (inc (long elapsed))]
        (if (< elapsed' (long cost))
          [:walking (set-job world pid assoc-in [:move :elapsed] elapsed')]
          ;; Segment finished this tick. The pawn already sits on :move's :to
          ;; (= :pos). Clear the glide, then arrive or roll straight into the
          ;; next segment in the SAME tick (no dead settle-tick between cells).
          (let [cleared (set-job world pid assoc :move nil)
                idx     (:path-index job)]
            (if (last? idx)
              [:arrived cleared]
              (step-or-replan cleared (entity/entity cleared pid) (inc idx))))))

      ;; Settled with no glide in flight: at the goal, or kick off the next cell.
      (last? (:path-index job))
      [:arrived world]

      :else
      (step-or-replan world pawn (inc (:path-index job))))))
