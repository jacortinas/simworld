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
   [sim.pathfinding :as pathfinding]))

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
   assignment goes through here."
  ([world pawn-id job] (assign world pawn-id job nil))
  ([world pawn-id job overrides]
   (let [job (merge job overrides)]
     (-> world
         (entity/update-entity pawn-id assoc :job job)
         (prime-path pawn-id)
         (log/append (assigned-entry pawn-id job))))))

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
  "Move haul job to next phase, clearing the path so it gets recomputed."
  [world pawn-id phase]
  (set-job world pawn-id
           (fn [job] (assoc job :phase phase :path nil :path-index 0))))

(defn- walk-toward
  "Step the pawn one tile toward `target`. Returns `[result world']`:
     :arrived  — pawn is now at target
     :walking  — pawn moved or path was just computed
     :failed   — no path exists
   The job's :path / :path-index slots track progress across calls."
  [world pawn target]
  (let [job   (:job pawn)
        path  (:path job)
        pid   (:id pawn)]
    (cond
      ;; First call: compute the path. Don't move yet.
      (nil? path)
      (if-let [new-path (pathfinding/find-path world (:pos pawn) target)]
        [:walking (set-job world pid assoc :path new-path)]
        [:failed world])

      ;; Path exhausted: we're there.
      (>= (:path-index job) (dec (count path)))
      [:arrived world]

      ;; Step one tile.
      :else
      (let [next-i   (inc (:path-index job))
            next-pos (path next-i)]
        [:walking
         (entity/update-entity world pid
                               (fn [p]
                                 (-> p
                                     (assoc :pos next-pos)
                                     (assoc-in [:job :path-index] next-i))))]))))

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
            (next-phase pid :go-to-dest))

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
