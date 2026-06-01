(ns sim.job
  "Job definitions and execution.

   A job is a piece of data describing what a pawn is currently doing.
   Lifecycle: :pending -> :in-progress -> (:complete | :failed).
   The pawn carries its current job in (:job pawn); when done, the slot
   is cleared back to nil by `sim.ai/advance-job`.

   `advance` is the per-tick step function. It now returns a world (not a
   pawn) so jobs can touch multiple entities — pickup/drop both mutate the
   item AND the pawn.

   New job types are added by `defmethod advance :their-type`."
  (:require
   [sim.entity      :as entity]
   [sim.log         :as log]
   [sim.pathfinding :as pathfinding]
   [sim.pathgrid    :as pathgrid]
   [sim.reservation :as reservation]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn go-to
  "A job that walks the pawn to `target` along an A* path."
  [target]
  {:type       :go-to
   :state      :pending
   :priority   :normal
   :source     :auto-assigned
   :target     target
   :path       nil
   :path-index 0})

(defn haul
  "A job that picks up `item-id` and brings it to `destination`."
  [item-id destination]
  {:type        :haul
   :state       :pending
   :priority    :normal
   :source      :auto-assigned
   :item-id     item-id
   :destination destination
   :phase       :go-to-item    ; :go-to-item | :pickup | :go-to-dest | :drop
   :path        nil
   :path-index  0})

(defn eat
  "A job that walks the pawn to food item `food-id` and consumes it, refilling
   the pawn's :food need. Uses :item-id (like haul) so reservations cover it."
  [food-id]
  {:type       :eat
   :state      :pending
   :priority   :normal
   :source     :auto-assigned
   :item-id    food-id
   :phase      :go-to-food    ; :go-to-food | :consume
   :path       nil
   :path-index 0})

;; ---------------------------------------------------------------------------
;; Assignment — the ONE path every job assignment routes through (player
;; click, REPL helper, future auto-assignment), so the set-job + log side
;; effects can't drift apart between callers.
;; ---------------------------------------------------------------------------

(def forced-by-player
  "Override map stamping a job as a forced player order (vs auto-assignment).
   Merge it onto a job when assigning from a player command."
  {:priority :forced :source :player})

(defn- assigned-entry
  "Build the :job/assigned log entry from a job map. Type-specific fields
   (:target, :item, :destination) are included when present, so new job types
   log sensibly without touching this."
  [pawn-id job]
  (cond-> {:type     :job/assigned
           :pawn     pawn-id
           :job      (:type job)
           :priority (:priority job)
           :source   (:source job)}
    (:target job)      (assoc :target      (:target job))
    (:item-id job)     (assoc :item        (:item-id job))
    (:destination job) (assoc :destination (:destination job))))

(defn- prime-path
  "Compute a :go-to job's A* path at assignment time, so the route lives in
   world state immediately — drawn by the path overlay the instant the order
   is given, even while the sim is PAUSED (no tick has run yet), and followed
   with no one-tick delay on resume. Computed from the pawn's current pos, which
   is identical to where walk-toward would start on the next tick, so the route
   is unchanged — only its timing moves earlier.

   No-op for non-:go-to jobs (haul recomputes its path per phase via next-phase,
   so priming there would be wrong) or when a path is already set. walk-toward's
   lazy `(nil? path)` branch stays the replanning fallback (future dynamic
   obstacles nil the path to force a free recompute)."
  [world pawn-id]
  (let [{:keys [job pos]} (entity/entity world pawn-id)]
    (if (and (= :go-to (:type job)) (nil? (:path job)))
      (if-let [path (pathfinding/find-path world pos (:target job))]
        (entity/update-entity world pawn-id assoc-in [:job :path] path)
        world)
      world)))

(defn assign
  "Assign `job` to pawn `pawn-id` and log a :job/assigned entry. Pure:
   world -> world. `overrides` (optional) is merged onto the job — pass
   `forced-by-player` for player orders. A :go-to job's A* path is primed here
   (see `prime-path`) so the route shows immediately, even paused. Every
   assignment goes through here.

   Reservation gate: an AUTO (non-forced) job whose reserved targets are already
   claimed by another pawn is refused — logged as :job/blocked, world otherwise
   unchanged. Forced player orders override (player is boss). go-to reserves
   nothing, so it is never blocked. See sim.reservation."
  ([world pawn-id job] (assign world pawn-id job nil))
  ([world pawn-id job overrides]
   (let [job     (merge job overrides)
         forced? (= :forced (:priority job))
         blocked (when-not forced?
                   (seq (remove #(reservation/can-reserve? world % pawn-id)
                                (reservation/reserved-targets job))))]
     (if blocked
       (log/append world {:type     :job/blocked
                          :pawn     pawn-id
                          :job      (:type job)
                          :reserved (vec blocked)})
       (-> world
           (entity/update-entity pawn-id assoc :job job)
           (prime-path pawn-id)
           (log/append (assigned-entry pawn-id job)))))))

;; ---------------------------------------------------------------------------
;; State predicates
;; ---------------------------------------------------------------------------

(defn complete? [job] (= :complete (:state job)))
(defn failed?   [job] (= :failed   (:state job)))
(defn done?     [job] (or (complete? job) (failed? job)))

;; ---------------------------------------------------------------------------
;; Internal helpers — all return a world.
;; ---------------------------------------------------------------------------

(defn- set-job
  [world pawn-id f & args]
  (entity/update-entity world pawn-id
                        (fn [p] (apply update p :job f args))))

(defn- mark-state
  "Transition a pawn's job to `new-state`. Auto-logs terminal states so every
   defmethod gets :job/complete and :job/failed entries for free."
  [world pawn-id new-state]
  (let [job-type (get-in (entity/entity world pawn-id) [:job :type])
        world'   (set-job world pawn-id assoc :state new-state)]
    (case new-state
      :complete (log/append world' {:type :job/complete :pawn pawn-id :job job-type})
      :failed   (log/append world' {:type :job/failed   :pawn pawn-id :job job-type})
      world')))

(defn- next-phase
  "Move haul job to next phase, clearing the path (so it gets recomputed) and
   any in-flight glide. The :move is already nil on arrival; clearing it here is
   defensive against a force-changed phase."
  [world pawn-id phase]
  (set-job world pawn-id
           (fn [job] (assoc job :phase phase :path nil :path-index 0 :move nil))))

(defn segment-cost
  "Ticks for a pawn with `move-ticks` base speed to cross from `from` into the
   adjacent cell `to`: base × the unified traversal-cost (terrain move-cost,
   ×√2 for a diagonal), rounded, clamped to a 1-tick minimum. The same currency
   sim.pathfinding minimizes — so the route A* prefers is the fastest to walk."
  [grid move-ticks from to]
  (max 1 (Math/round (* (double move-ticks)
                        (pathfinding/traversal-cost grid from to)))))

(defn- start-segment
  "Begin gliding from the pawn's current cell into path cell `next-i`. Flips :pos
   to that cell immediately — the pawn now occupies the cell it is ENTERING
   (decision D2) — and records the from/to/cost it must glide across."
  [world pawn next-i]
  (let [pid  (:id pawn)
        from (:pos pawn)
        to   ((get-in pawn [:job :path]) next-i)
        cost (segment-cost (:grid world) (:move-ticks pawn 15) from to)]
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
  "Start gliding into path cell `next-i`, unless it is now blocked (a wall
   appeared), in which case nil the path so walk-toward's (nil? path) branch
   replans for free."
  [world pawn next-i]
  (if (next-cell-passable? world pawn next-i)
    [:walking (start-segment world pawn next-i)]
    [:walking (set-job world (:id pawn) assoc :path nil :path-index 0 :move nil)]))

(defn- walk-toward
  "Advance the pawn ONE TICK toward `target`, gliding sub-cell. Returns
   `[result world']`:
     :arrived — pawn has reached and finished gliding into target
     :walking — path computed, a segment started, or a glide advanced
     :failed  — no path exists
   Progress lives in (:job pawn): :path / :path-index, plus a :move record
   {:from :to :elapsed :cost} for the segment in flight. :pos flips to the
   destination cell when a segment STARTS (D2); the glide to :cost ticks is just
   how long the DRAWN position takes to catch up — see sim.render.interp."
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

;; ---------------------------------------------------------------------------
;; advance: the public dispatch. (world, pawn) -> world.
;; ---------------------------------------------------------------------------

(defmulti advance
  (fn [_world pawn] (get-in pawn [:job :type])))

(defmethod advance :default
  [world _pawn]
  world)

(defmethod advance :go-to
  [world pawn]
  (let [job (:job pawn)]
    (cond
      (done? job) world

      :else
      (let [[result world'] (walk-toward world pawn (:target job))]
        (case result
          :arrived (mark-state world' (:id pawn) :complete)
          :failed  (mark-state world' (:id pawn) :failed)
          :walking (set-job world' (:id pawn) assoc :state :in-progress))))))

(defmethod advance :haul
  [world pawn]
  (let [job     (:job pawn)
        pid     (:id pawn)
        item-id (:item-id job)
        item    (entity/entity world item-id)]
    (cond
      (done? job) world

      ;; The item disappeared (eaten? despawned?) — fail.
      (nil? item)
      (mark-state world pid :failed)

      :else
      (case (:phase job)
        :go-to-item
        ;; Item could have been picked up by someone else (Layer 3) — fail.
        (if (and (:carried-by item) (not= (:carried-by item) pid))
          (mark-state world pid :failed)
          (let [[result world'] (walk-toward world pawn (:pos item))]
            (case result
              :arrived (next-phase world' pid :pickup)
              :failed  (mark-state world' pid :failed)
              :walking (set-job world' pid assoc :state :in-progress))))

        :pickup
        ;; Reservation guard: if another pawn holds the claim (the same-tick
        ;; race where two pawns reach :pickup together), this one loses and
        ;; fails rather than double-grabbing. The lone hauler is its own
        ;; claimant, so the normal path is unaffected. This is the execution-time
        ;; half of "reserve what you'll write" — it makes pawn writes disjoint.
        (if-not (reservation/can-reserve? world item-id pid)
          (mark-state world pid :failed)
          (-> world
              (entity/update-entity pid       assoc :carrying item-id)
              (entity/update-entity item-id   (fn [it]
                                                (-> it
                                                    (assoc :carried-by pid)
                                                    (assoc :pos nil))))
              (log/append {:type     :job/pickup
                           :pawn     pid
                           :item     item-id
                           :material (:material item)
                           :at       (:pos item)})
              (next-phase pid :go-to-dest)))

        :go-to-dest
        (let [[result world'] (walk-toward world pawn (:destination job))]
          (case result
            :arrived (next-phase world' pid :drop)
            :failed  (mark-state world' pid :failed)
            :walking world'))

        :drop
        (let [drop-pos (:pos pawn)]
          (-> world
              (entity/update-entity pid     assoc :carrying nil)
              (entity/update-entity item-id (fn [it]
                                              (-> it
                                                  (assoc :carried-by nil)
                                                  (assoc :pos drop-pos))))
              (log/append {:type     :job/drop
                           :pawn     pid
                           :item     item-id
                           :material (:material item)
                           :at       drop-pos})
              (mark-state pid :complete)))))))

(defmethod advance :eat
  [world pawn]
  (let [job     (:job pawn)
        pid     (:id pawn)
        food-id (:item-id job)
        food    (entity/entity world food-id)]
    (cond
      (done? job)  world

      ;; Food eaten/despawned by someone else, or carried off the ground — fail.
      (or (nil? food) (nil? (:pos food)))
      (mark-state world pid :failed)

      :else
      (case (:phase job)
        :go-to-food
        (let [[result world'] (walk-toward world pawn (:pos food))]
          (case result
            :arrived (next-phase world' pid :consume)
            :failed  (mark-state world' pid :failed)
            :walking (set-job world' pid assoc :state :in-progress)))

        :consume
        (-> world
            (entity/update-entity pid (fn [p] (assoc-in p [:needs :food] 1.0)))
            (entity/remove-entity food-id)
            (log/append {:type     :job/eat
                         :pawn     pid
                         :item     food-id
                         :material (:material food)
                         :at       (:pos food)})
            (mark-state pid :complete))))))
