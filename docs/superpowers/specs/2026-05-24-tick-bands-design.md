# Tick Bands & Staggering — Design

*Date: 2026-05-24 · Status: approved-pending-spec-review*

## Context & goal

Today `sim.simulation/tick` updates the **whole world uniformly every tick** at 30 Hz: `step-pawns` decays needs *and* re-decides for **every pawn every tick**, and there is no notion of "this entity only needs attention occasionally." This is the *uniform-tick trap* documented in `docs/rimworld-engine-internals.md` §1.1 — cost is `O(total entities) × tickrate`, billing a tree that grows once a day at 30×/s, with no amortization.

**Goal:** replace the uniform tick with a tiered, *staggered* scheduler so per-tick cost scales with **active** work, not total entity count — mirroring RimWorld's `TickManager` (Normal / Rare / Long tick lists + hash-bucketed staggering).

This is step 1 of the revised build plan in `docs/rimworld-engine-internals.md` §4 — chosen first because it is the cheapest to adopt now and is the foundation later systems sit on.

## Background: how RimWorld actually does it

- `TickManager` holds three tick lists keyed by each thing's `def.tickerType`: **Normal** (every tick), **Rare** (every 250 ticks), **Long** (every 2000 ticks), at 60 TPS.
- **Staggering:** each list is `interval` physical buckets; a thing's home bucket is `hash % interval`; each tick only the bucket where `TicksGame % interval == bucketIndex` runs. So ~1/interval of each band's entities run per tick — flat amortized cost, no spike.
- **Pawns are `tickerType = Normal`** — they tick *every* tick. Their needs are *not* bucket-scheduled; `Pawn_NeedsTracker` runs every tick but throttles internally (`if (TicksGame % 150 == 0)`). The rare/long buckets exist for the *numerous cheap* things (plants, filth, buildings, items).

## Design

### Two complementary mechanisms

We adopt **both** staggering shapes RimWorld uses, each where it fits:

1. **Physical bucket index** — for entities whose *entire* update is periodic (items, and later plants/filth/buildings). True `O(active)` per tick: the scheduler touches only today's bucket, never the full set.
2. **`due?` sub-throttle predicate** — for periodic *sub-work* inside per-tick systems (a pawn's needs-decay / idle re-think). The pawn must tick every tick for movement anyway, so we throttle its rare sub-work with a pure modulo check rather than a maintained bucket. Pawns are few; we're already iterating them.

Same staggering math (`home-bucket(id) == due-bucket(clock)`), two delivery shapes. Forcing pawns into a rare bucket would contradict "move every tick" and be *less* faithful to RimWorld, not more.

### Bands & intervals

At our 30 Hz, halve RimWorld's 60-TPS tick counts to preserve the same wall-clock cadence:

| Band | Interval (ticks) | Wall-clock @30 Hz | RimWorld equiv |
|---|---|---|---|
| `:normal` | 1 | every tick | 60 TPS Normal |
| `:rare` | **125** | ~4.2 s | 250 ticks (~4.2 s) |
| `:long` | **1000** | ~33 s | 2000 ticks (~33 s) |

Defined as tunable constants in `sim.schedule`.

### `:ticker-type` on entities

Each entity carries `:ticker-type` ∈ `{:rare :long :never}` = the *bucketed* band it joins. (Normal/every-tick work is handled by normal systems that select their own targets, independent of ticker-type — so there is no `:normal` ticker-type value and no normal index.)

Defaults by `:kind`, set at construction in `sim.entity/make-*`, overridable per entity (data-driven, mod-friendly):

| Kind | `:ticker-type` | Rationale |
|---|---|---|
| `:pawn` | `:never`¹ | Pawn periodic work uses `due?` sub-throttle inside normal systems, not a bucket. |
| `:item` | `:long` | Deterioration (stub for now). |
| `:tree` | `:never` | Inert until a growth/chop system exists. |

¹ Pawns carry `:never` because they are not bucket-scheduled. Their every-tick work (job advance) and their throttled rare work (needs, re-think) both run via normal systems. `:never` here means "not in a rare/long bucket," **not** "never updated."

### World data shape

New derived key (an index, never hand-edited, **not persisted**):

```clojure
:schedule {:rare (vec (repeat 125 #{}))     ; 125 buckets of entity-ids
           :long (vec (repeat 1000 #{}))}   ; 1000 buckets of entity-ids
```

- **Home bucket** of an entity: `(mod id interval)`. Our ids are a monotonic counter (`sim.entity/id-counter`), so plain `mod` distributes them perfectly evenly — no hash needed now. A `hash-offset` seam is left for future non-sequential ids.
- **Due bucket** each tick: `(mod (:clock world) interval)`.

### New namespace: `sim.schedule`

Pure functions + a system registry.

```
;; Constants
bands                      => {:normal 1 :rare 125 :long 1000}

;; Pure bucket math
(home-bucket id interval)  => bucket index
(due-bucket clock interval)=> bucket index
(due? clock interval id)   => bool   ; home-bucket == due-bucket — the sub-throttle predicate

;; Index maintenance (return new world)
(register world entity)    ; add id to its ticker-type bucket (no-op for :never)
(unregister world entity)  ; remove id from its bucket
(empty-index)              => fresh {:rare [...] :long [...]}
(reindex world)            ; rebuild :schedule from :entities — idempotent; used on load

;; System registry (module-level, defonce; survives reload)
(register-system! band name f)   ; f : (world due-entities) -> world
(run world)                      ; the scheduler step (below)
```

**System signature:** every system is `(fn [world due-entities] world)`.
- For the **normal** band, `due-entities` is `nil` — normal systems select their own targets from `world` (e.g. `advance-jobs` filters to pawns with a job; `process-events` ignores the arg; `decay-needs` iterates pawns and applies the `due?` sub-throttle). This avoids materializing the whole entity set every tick.
- For **rare/long** bands, `due-entities` is the due bucket's ids resolved to entity maps (nils — from any id removed without unregister — skipped defensively).

`run`:
1. **Normal systems**, in registration order, every tick, called `(f world nil)`.
2. **Rare systems** — called `(f world due-rare-entities)` where due-rare-entities = entities of `(:rare schedule)` bucket `(due-bucket clock 125)`.
3. **Long systems** — called `(f world due-long-entities)` likewise for the long bucket.

### Built-in systems (seeded at load)

| Band | System | Source today | Notes |
|---|---|---|---|
| normal | `process-events` | `simulation/process-events` | unchanged, moved into a system |
| normal | `advance-jobs` | from `step-pawns`/`ai/decide` | pawns with an active job advance every tick; finished jobs cleared immediately; physical step still gated by the existing per-pawn move cadence (`ai/moves-this-tick?`) |
| normal | `decay-needs` | from `simulation/decay-needs` | iterates pawns, applies decay only where `(due? clock 125 id)` — the sub-throttle |
| normal | `redeliberate-idle` | from `ai/decide` idle branch | idle pawns pick work only where `(due? clock 125 id)` (player-forced jobs still apply immediately via `sim.command`, not here) |
| long | `deteriorate` | **stub** | wired to the long bucket over `:item` entities; no-op until item deterioration exists |

### Integration points

1. **`sim.simulation/tick`** → `(-> world advance-clock schedule/run)`. The old `process-events`/`step-pawns`/`decay-needs` bodies move into registered systems (logic preserved, relocated).
2. **Entity lifecycle is the index chokepoint.** `sim.entity/add-entity` and `remove-entity` also call `schedule/register`/`unregister`. (`sim.entity` requires `sim.schedule`; schedule reads plain map keys, so no cycle.) All current callers (`world/spawn-pawn!`, haul drop creating ground items, worldgen, REPL helpers) get index maintenance for free. The plan phase enumerates and verifies every call site.
3. **`sim.world/initial-world`** includes `:schedule (schedule/empty-index)`.
4. **Save/load.** `:schedule` is derived → `sim.save/save!` dissocs it before freezing (smaller saves, no drift); the load path calls `schedule/reindex` after thaw so the index is always rebuilt from authoritative `:entities`. Worldgen may bulk-`reindex` once at the end instead of relying on per-add registration.
5. **Movement speed stays separate.** `ai/moves-this-tick?` (the 15-tick "step one tile twice a second" gate) is *gameplay speed*, not scheduling. `advance-jobs` runs every tick; the move-gate decides whether the pawn physically advances a tile. The two are not conflated.

### Expected impact (honest)

- **Immediate measurable win:** pawn needs-decay and idle re-think drop from 30×/s to ~once per 4.2 s per pawn (125× less of that work) via the `due?` sub-throttle.
- **The physical bucket index is forward infrastructure.** Today only `:item` entities populate it (long band, deterioration stub), so its immediate CPU payoff is modest. Its value compounds as we add the cheap-numerous entities RimWorld's TickLists are built for (plants, filth, buildings). Building it now — while the scheduler is small — avoids a painful retrofit and is why "full scheduler" was chosen over the minimal slice.

## Testing (headless, pure — no GL)

- **Bucket math:** `home-bucket`/`due-bucket` correctness; `due?` fires exactly once per interval per id.
- **Index maintenance:** `register`/`unregister` place/remove ids in the right bucket; `:never` entities are skipped; `reindex` is idempotent (`reindex ∘ reindex == reindex`) and equals incremental maintenance after a sequence of add/removes.
- **Coverage property (the core guarantee):** over any `interval` consecutive ticks, every entity in a band is processed **exactly once** — never starved, never double-served. Checked deterministically with plain `clojure.test` (sweep clock `0..interval-1`, tally hits per id, assert all == 1) — no `test.check` dependency needed.
- **Behavioral equivalence:** needs after 125 ticks ≈ old per-tick × 125 (within float tolerance); pawns still move every tick; finished jobs still clear immediately.
- **Purity/determinism:** `tick` remains a pure `world → world`; `save → reindex → load` round-trips to an equivalent world; two worlds ticked N times from the same seed stay identical.

## Out of scope / future

- Item deterioration logic (only the long-band wiring + stub here).
- RimWorld 1.6-style *distance-based variable pawn tick rates* (off-screen pawns tick less) — a later optimization; the classic Normal-ticker + sub-throttle model is what we build now.
- `hash-offset` for non-sequential ids (seam left; plain `mod` suffices while ids are a counter).
- Additional bands or per-system custom intervals beyond normal/rare/long.
- Set-valued `:ticker-type` (entity in multiple bucketed bands at once) — not needed while each entity has at most one periodic band.

## Files touched

- **New:** `src/sim/schedule.clj`, `test/sim/schedule_test.clj`
- **Modified:** `src/sim/simulation.clj` (tick → schedule/run; relocate bodies), `src/sim/entity.clj` (add/remove maintain index; `:ticker-type` defaults in `make-*`), `src/sim/world.clj` (`:schedule` in initial-world), `src/sim/save.clj` (dissoc `:schedule` on save; reindex on load), `src/sim/ai.clj` (decide logic split into systems), `dev/user.clj` & `CLAUDE.md` (vocabulary + status), possibly `src/sim/worldgen.clj` (bulk reindex).
