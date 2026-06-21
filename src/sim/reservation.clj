(ns sim.reservation
  "Reservations — who has claimed which target — as a PURE DERIVED QUERY over
   the pawns' active jobs. Nothing is stored: each job already encodes its
   target (a haul's :item-id), so 'who claims X' is a function of current state.

   The payoff of deriving rather than storing: RELEASE IS A NON-EVENT. When a
   job clears, its claim simply vanishes — there is no release fn to call and no
   job-end hook to forget, so the 'phantom reservation' bug class (RimWorld's
   manager must release on every despawn/fail/complete/interrupt path) cannot
   occur here.

   This namespace interprets jobs but does NOT depend on sim.job — that keeps
   the graph acyclic (sim.job consults can-reserve? in assign). It depends only
   on sim.entity."
  (:require
   [sim.entity :as entity]))

(set! *warn-on-reflection* true)

(defn reserved-targets
  "The entity ids a job claims (reserves). :haul and :eat claim their item;
   :go-to claims nothing (cell/destination reservations are out of scope). New
   reservable job types extend this. Returns a seq or nil."
  [job]
  (case (:type job)
    (:haul :eat) (when-let [item-id (:item-id job)] [item-id])
    nil))

(defn- active?
  "A job that still needs its target — not done. (sim.job/done? is the public
   form; inlined here so this namespace needn't depend on sim.job.)"
  [job]
  (and job (not (#{:complete :failed} (:state job)))))

(defn claimant
  "The id of the pawn whose active job claims `target`, or nil. Resolves ties to
   the LOWEST id (not merely the first found). (entity/pawns) now yields pawns
   ascending by id, so the min is belt-and-suspenders rather than the sole
   guarantee — but it's kept deliberately: this fn must not couple its
   correctness to another namespace's sort invariant, and the min lets a future
   parallel reconcile pick the same winner regardless of thread scheduling."
  [world target]
  (let [ids (->> (entity/pawns world)
                 (filter (fn [p] (and (active? (:job p))
                                      (some #(= target %)
                                            (reserved-targets (:job p))))))
                 (map :id))]
    (when (seq ids) (apply min ids))))

(defn can-reserve?
  "True if `target` is unclaimed, or already claimed by `pawn-id` itself.
   Single-target form: derives one claimant (O(pawns)). To check MANY targets in
   one deliberation, build a `claims` map once and use `reservable?` instead; that
   amortizes the pawn scan to O(pawns) total rather than O(pawns) per target."
  [world target pawn-id]
  (let [c (claimant world target)]
    (or (nil? c) (= c pawn-id))))

(defn claims
  "Map of reserved-target to lowest claiming pawn-id, over every pawn's active
   job. ONE O(pawns) pass: a caller that then checks K targets against it pays
   O(pawns + K), not O(pawns) PER target (the quadratic give-haul/give-eat used
   to incur). Ties resolve to the lowest pawn-id via `(fnil min ...)`, exactly as
   `claimant` does, so the chosen claimant is independent of pawn iteration order
   (the same determinism the parallel-job path will need)."
  [world]
  (reduce (fn [m p]
            (let [job (:job p)]
              (if (active? job)
                (reduce (fn [m t] (update m t (fnil min (:id p)) (:id p)))
                        m (reserved-targets job))
                m)))
          {}
          (entity/pawns world)))

(defn reservable?
  "Claims-map twin of `can-reserve?`: true if `target` is unclaimed in the
   precomputed `claims` map (see `claims`), or already claimed by `pawn-id`. The
   batch path filters candidate targets through this after building `claims` once."
  [claims target pawn-id]
  (let [c (get claims target)]
    (or (nil? c) (= c pawn-id))))
