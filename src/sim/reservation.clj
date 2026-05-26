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
   on sim.entity. See docs/superpowers/specs/2026-05-25-reservations-design.md."
  (:require
   [sim.entity :as entity]))

(set! *warn-on-reflection* true)

(defn reserved-targets
  "The entity ids a job claims (reserves). :haul claims its item; :go-to claims
   nothing (cell/destination reservations are out of scope). New reservable job
   types extend this. Returns a seq or nil."
  [job]
  (case (:type job)
    :haul (when-let [item-id (:item-id job)] [item-id])
    nil))

(defn- active?
  "A job that still needs its target — not done. (sim.job/done? is the public
   form; inlined here so this namespace needn't depend on sim.job.)"
  [job]
  (and job (not (#{:complete :failed} (:state job)))))

(defn claimant
  "The id of the pawn whose active job claims `target`, or nil. Resolves ties to
   the LOWEST id (not merely the first found): (entity/pawns) walks vals, whose
   order is unspecified, so taking the min makes the result deterministic — and
   lets a future parallel reconcile pick the same winner regardless of thread
   scheduling."
  [world target]
  (let [ids (->> (entity/pawns world)
                 (filter (fn [p] (and (active? (:job p))
                                      (some #(= target %)
                                            (reserved-targets (:job p))))))
                 (map :id))]
    (when (seq ids) (apply min ids))))

(defn can-reserve?
  "True if `target` is unclaimed, or already claimed by `pawn-id` itself."
  [world target pawn-id]
  (let [c (claimant world target)]
    (or (nil? c) (= c pawn-id))))
