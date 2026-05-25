# Reservations — Design

*Date: 2026-05-25 · Status: approved-pending-spec-review*

## Context & goal

Two pawns must never grab the same target. Today nothing prevents it cleanly:
all job assignment routes through `sim.job/assign` (always sets the job), and
the haul FSM only catches a conflict *after the fact* — its `:go-to-item` phase
fails if the item is already `:carried-by` someone else, but the `:pickup` phase
does not re-check, so two pawns arriving on the same tick can both pick up the
same item (the double-grab). There is also no auto-assignment yet (idle pawns
random-wander via `sim.ai/redeliberate`), so the *competing consumer* that
reservations ultimately serve — work-scanning — is step 4.

**Goal:** introduce reservations — a way to ask "is this target already claimed?"
and to refuse double-claims — per `docs/rimworld-engine-internals.md` §4 step 3.
This unblocks "multiple pawns interacting" without double-grabs and lays the
write-disjointness invariant that later parallel job execution depends on.

## Background: how RimWorld does it

RimWorld's `ReservationManager` is a per-map mutable structure. `CanReserve` /
`Reserve` are keyed on `(target, job, layer)` with a max-claimants count, checked
*during* work-scanning so a contested target is never even handed out;
reservations are released when the job ends.

## Design decisions (settled in brainstorming)

1. **Storage: a derived query, not stored state.** Each job already encodes its
   target (`:item-id` for haul), so "who claims X" is a pure function of the
   pawns' current jobs. Nothing is stored; **release is automatic** — when a job
   clears, its claim simply vanishes (zero drift, zero release plumbing). O(pawns)
   per check, trivial at our scale, and a non-breaking upgrade to a materialized
   index later if a profile ever demands it.
2. **Forced orders override.** A player-forced order (`:priority :forced`)
   proceeds regardless of reservation (player is boss). Only **auto** (non-forced)
   assignment consults `can-reserve?`. Since every current caller is forced and
   `go-to` reserves nothing, *this changes no observable behavior today* —
   reservations are the structure the step-4 auto work-scanner sits on, like the
   tick-bands bucket index was forward infrastructure.

## Architecture

A new **`sim.reservation`** namespace of pure queries — no stored state. The
single source of truth for "who claimed what" is the pawns' active jobs.

```
sim.reservation → sim.entity      ; pure reads over pawn jobs
sim.job         → sim.reservation ; assign consults can-reserve?; haul pickup guards on it
```

No dependency cycle: `entity → schedule`, `reservation → entity`,
`job → reservation + entity`. The "which targets does a job claim"
interpretation lives *in* `sim.reservation` (interpreting jobs is reservation's
job), so `sim.job` never reaches back — which is what keeps the graph acyclic
while still letting `assign` (the one assignment chokepoint) be the enforcement
point.

## Components — `sim.reservation`

```clojure
(reserved-targets job)
;; The entity ids a job claims. :haul -> [item-id]; :go-to -> nil.
;; Cell/destination reservations are out of scope. New reservable job types
;; extend this (a case on (:type job)).

(claimant world target)
;; The id of the LOWEST-id pawn whose ACTIVE (not done?) job claims `target`,
;; else nil.

(can-reserve? world target pawn-id)
;; True if `target` is unclaimed, or already claimed by `pawn-id` itself.
```

- **Lowest-id, sorted:** `(entity/pawns world)` walks `vals`, whose order is
  unspecified, so `claimant` sorts the candidate ids and takes the lowest. Ties
  resolve deterministically — directly serving the determinism/MT goal (a
  parallel reconcile would pick the same winner regardless of scheduling).
- **"Active" ignores `done?`:** a target frees the instant its job completes,
  even before `sim.ai/advance-job` nils the slot. The done check
  (`#{:complete :failed}` on `:state`) is inlined privately in `sim.reservation`
  so the namespace needn't depend on `sim.job` (avoids the cycle).

## Integration — two enforcement points

1. **At assignment (`sim.job/assign`).** Refuse a **non-forced** job whose
   reserved targets are claimed by another pawn: append a `:job/blocked` log
   entry and return the world unchanged. **Forced** orders proceed. Sketch:

   ```clojure
   (let [job     (merge job overrides)
         forced? (= :forced (:priority job))
         blocked (when-not forced?
                   (seq (remove #(reservation/can-reserve? world % pawn-id)
                                (reservation/reserved-targets job))))]
     (if blocked
       (log/append world {:type :job/blocked :pawn pawn-id :job (:type job)
                          :reserved (vec blocked)})
       (-> world
           (entity/update-entity pawn-id assoc :job job)
           (prime-path pawn-id)
           (log/append (assigned-entry pawn-id job)))))
   ```

   `go-to` reserves nothing → never blocked. Every current caller is forced →
   no behavior change today.

2. **At execution (haul `:pickup` phase).** Guard: if
   `(not (reservation/can-reserve? world item-id pid))`, fail the job. This makes
   "first claimant wins" real at the physical moment of pickup, closing the
   same-tick double-grab two forced haul orders could otherwise cause. A lone
   hauler is its own claimant, so the normal path is unaffected.

`reserved-targets` for the same-tick contested case: both pawns hold active haul
jobs for the item; `claimant` returns the lowest id; that pawn's pickup guard
passes (claimant == self), the other's fails (claimant != self) and the loser
re-deliberates. Deterministic, no double-grab.

## Concurrency & future MT (the seam)

Documented intent so it isn't re-derived later (see [[mt-direction]] memory and
`docs/rimworld-engine-internals.md`):

- The **pure predicate `can-reserve?`/`claimant` is the primary primitive**;
  `assign`-refuses and the haul pickup-guard are *replaceable consumers* of it.
- The future parallel-assignment path is **propose → reconcile**: score candidate
  targets in parallel (read-only over the tick-start snapshot), then a sequential
  reconcile pass picks winners deterministically via `claimant`. This is *not*
  lock-based CAS on a mutable structure, which would be non-deterministic.
- Invariant — **reserve what you'll write** ⇒ disjoint entity writes across pawns
  ⇒ `clojure.core.reducers/fold` + `merge` makes parallel job execution safe and
  deterministic. The pickup guard is what enforces this invariant at runtime
  (the role ECS engines fill statically via archetype analysis).
- The actual near-term MT/determinism prerequisite is **threading RNG through
  state** (`sim.ai/random-step` currently calls global `rand-nth`); tracked as a
  separate follow-up, not part of this step.

## Testing (headless, pure — no GL)

- **Queries:** unclaimed → `claimant` nil, `can-reserve?` true; self-claim →
  `can-reserve?` true; other-claim → false; a `done?` job frees its target; two
  claimers resolve to the lowest id (determinism).
- **`reserved-targets`:** `:haul` → `[item-id]`; `:go-to` → nil.
- **Assign gate:** an auto (non-forced) haul of a claimed item is refused, logs
  `:job/blocked`, and leaves the world unchanged; a forced haul of the same item
  proceeds.
- **Double-grab prevention:** drive two haul jobs targeting one item to
  completion; assert exactly one pawn ends up carrying/delivering it and the
  other job is `failed?` with a logged failure.
- **No regression:** the existing `job_test` haul/go-to lifecycle stays green
  (single-hauler pickup unaffected).

## Out of scope / future (seams left)

- **Cell/destination reservations** (two pawns to the same standing spot) — only
  entity-id targets now.
- **Reservation layers / max-claimants-per-target** (RimWorld has both) — single
  claimant per target now.
- **Materialized reservation index** — derived query suffices at our scale;
  backing it with a chokepoint-maintained, load-rebuilt index (à la `:schedule`)
  is a non-breaking change behind the same predicate.
- **Parallel propose/reconcile assignment** — seam documented above; not built.
- **RNG-threading determinism work** — tracked separately as the real MT
  prerequisite.

## Files touched

- **New:** `src/sim/reservation.clj`, `test/sim/reservation_test.clj`
- **Modified:** `src/sim/job.clj` (assign reservation gate + haul `:pickup`
  guard), `dev/user.clj` (optional read-only `(claims)` inspector), `CLAUDE.md`
  (reservation decision + "reserve what you'll write" invariant + MT seam note +
  files-to-know)
