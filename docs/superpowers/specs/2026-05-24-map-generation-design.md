# Map Generation — Design Spec

**Date:** 2026-05-24
**Status:** Approved, ready for implementation planning
**Topic:** Procedural, gameplay-meaningful map generation for the `sim` colony sim

## Context

`sim.world/initial-world` currently fills the whole grid with `:grass`
(`tile/make-grid width height :grass`). The map is inert and uniform. This is
the "all-grass until worldgen" gap noted in `CLAUDE.md`.

The grid data model is simple and already generation-ready: a grid is
`{:width :height :tiles [kw kw …]}`, linearized as `(+ x (* y width))`.
Generation only needs to produce that `:tiles` vector and add some entities.
`:rng-seed` already lives in the world map, so deterministic seeded generation
is anticipated by the data model.

A* pathfinding already reads terrain passability/cost, so terrain we generate
(water, rock walls) shapes pathing for free. The `:haul` job already works end
to end with `:wood`/`:stone`/`:food` items, so loose resources we scatter are
immediately actionable by pawns — no new job required.

## Goals

Generate a coherent, gameplay-meaningful map containing all four feature
classes:

1. **Varied ground + water** — `:grass`/`:dirt`/`:gravel` patches and `:water`
   bodies. Contiguous, organic regions. Water affects pathing immediately.
2. **Rock/stone formations** — contiguous impassable `:stone` masses that carve
   the map into open vs. walled regions. A* routes around them now; mining is a
   future job.
3. **Trees & plants** — passable `:tree` entities scattered on grass. A wood
   source and visual life; inert until a chop job exists, rendered now.
4. **Loose haulable resources** — `:wood`/`:stone`/`:food` ground items the
   **existing haul job consumes today**.

Generation must be **deterministic**: same seed → byte-identical map.

## Non-goals (YAGNI)

- Mining, chopping, and farming **jobs** — out of scope. Generated rock and
  trees are inert placeholders that future jobs will act on.
- New terrain types (`:sand`, fertile `:soil` with `:fertility`) — deferred
  until farming arrives. v1 reuses existing terrain keywords, so no new sprites.
- Full biome worldgen (temperature/rainfall maps, multiple biomes) — premature.
- Tying generation to a "New Game" screen — screens aren't built yet; would
  block worldgen on the screen state machine. Generation is REPL/lifecycle
  driven for now.
- Animated/autotiled terrain edges — separate future work.

## Architecture

A new pure pipeline namespace, `sim.worldgen`, exposes:

```clojure
(generate world opts) ; => world'  — rebuilds :grid, adds tree/item entities
```

### Composability is the core constraint

The whole point of this design is that passes can be **added, removed,
reordered, or swapped** by editing a data value — never by rewiring control
flow. Three rules enforce this:

**1. Uniform pass signature — every pass is `state → state`.** A pass never
takes `world` directly; it takes a `state` map and returns a new one:

```clojure
state = {:world    <world>      ; the world being built
         :seed     <long>       ; master seed
         :opts     <map>        ; thresholds, width/height, densities
         :reachable <set-or-nil>} ; tiles reachable from spawn (set by Pass 3)
```

Because the interface is uniform, any pass composes with any other. A pass that
needs upstream data (e.g. scatter needs `:reachable`) reads it from `state`; a
pass that produces it (connectivity) writes it. Data flows through `state`, not
through function arguments wired by hand.

**2. The pipeline is data, not code.** `generate` is a one-liner reduce over a
**vector of pass fns**:

```clojure
(def default-pipeline [base-pass rock-pass connectivity-pass scatter-pass])

(defn generate [world opts]
  (let [passes (:passes opts default-pipeline)]
    (:world (reduce (fn [s pass] (pass s)) (init-state world opts) passes))))
```

Adding a feature = `conj` a pass onto the vector. Removing one = drop it.
Reordering = reorder the vector. Swapping the rock algorithm = replace
`rock-pass` with `rock-pass-v2` — same signature, drops in. Callers can pass
`:passes` to run a custom pipeline (e.g. tests run a single pass in isolation,
or "base only" for a flat map).

**3. Passes are built from smaller composable functions.** Each pass is itself a
composition of tiny pure helpers (noise primitives, a CA `step`, a flood-fill,
a rejection-sampler) — each independently testable and reusable. Nothing is a
monolithic procedure.

This mirrors the project's existing wins: pure tick, job-as-data multimethod,
render layers as a walked list. The pipeline *is* the extension point.

### New / edited files

| File | Change | Purpose |
|---|---|---|
| `src/sim/worldgen/noise.clj` | new | Composable noise primitives (lattice value, `smoothstep`, octave/fbm combinator); pure, headless-testable |
| `src/sim/worldgen.clj` | new | Pipeline + the four passes |
| `src/sim/entity.clj` | edit | `make-tree`, `:tree` kind, `trees` query |
| `src/sim/render/sprites.clj` | edit | `tree-region` (tiles cell `26.c`) |
| `src/sim/render/layers/flora.clj` | new | Draw tree entities (z: terrain < flora < items < pawns) |
| `src/sim/render/gdx.clj` | edit | Insert flora layer into the compositor |
| `src/sim/world.clj` | edit | `reset-world!` gains `:generate?` + seed opts |
| `dev/user.clj` | edit | `(generate-world! opts)` REPL helper |
| `test/sim/worldgen_test.clj` | new | Determinism, connectivity, band proportions |
| `test/sim/worldgen/noise_test.clj` | new | Noise determinism + value range |

The flora render layer stays untested, matching the existing
terrain/items/pawns convention (GL `draw` calls are not unit-tested). All pure
logic is tested headless.

## Data-model additions

### Tree entity

```clojure
{:id (next-id!) :kind :tree :pos [x y] :species :tree}
```

- **Passable.** A* reads only terrain, so trees never touch pathfinding —
  pawns walk through them (RimWorld's model). This keeps the pathfinding
  contract terrain-only.
- `sim.entity` gains `make-tree`, a `trees` query (filter `:kind :tree`), and
  the `:tree` kind is purely additive (no changes to existing queries).

### Terrain

v1 reuses existing keywords only: `:grass`, `:dirt`, `:gravel`, `:water`,
`:stone`. No new terrain definitions or sprites. `:sand`/`:soil` with a
`:fertility` field is a trivial later add when farming lands.

## The pipeline — four passes

`generate` runs these in order. Plan 1 ships passes 1 and 4; Plan 2 adds
passes 2 and 3.

### Pass 1 — Base (value noise)

Sample an **elevation** noise field per tile and threshold:

- elevation `< water-level` → `:water`
- elevation `> rock-level`  → `:stone`
- otherwise → ground, with a second **moisture** noise field splitting ground
  into `:grass` (wet), `:dirt` (mid), `:gravel` (dry).

Thresholds are the tuning knobs (e.g. `water-level ≈ 0.30`, `rock-level ≈ 0.78`
— tune during implementation). Produces contiguous bands rather than confetti.

The noise field is itself composed from small primitives in `noise.clj`: a
seeded lattice-value lookup, `smoothstep` interpolation, and an octave/fbm
combinator that sums scaled samples. The pass is a thin
`elevation`/`moisture` → terrain classifier over those primitives, so the noise
character (frequency, octaves) is tunable without touching the classifier and
the same primitives back any future noise needs.

Builds the `:tiles` vector wholesale via `(vec (for [i (range (* w h))] …))` —
**not** repeated `set-tile` calls (`set-tile` is for incremental edits later,
e.g. mining one tile).

### Pass 2 — Rock (cellular automata) — *Plan 2*

Adds organic stone masses on top of the noise base:

1. Random-fill a boolean rock mask at ~42% density.
2. Run 4 smoothing iterations: a cell becomes rock if ≥5 of its 8 neighbors are
   rock (border-out-of-bounds counts as rock so masses hug edges).
3. OR the resulting mask onto the base grid as `:stone`.

Gives cave/ridge-like contiguous formations that the noise threshold alone
produces more diffusely. Each iteration is a pure `mask → mask`.

### Pass 3 — Connectivity guard — *Plan 2*

Guarantees everything generated is reachable, **without mutating terrain**:

1. Carve a small guaranteed-open spawn clearing near map center (force those
   tiles to `:grass`).
2. Flood-fill the reachable passable set from the clearing (4-connected over
   passable terrain).
3. Expose the reachable set to the scatter pass; **scatter places only on
   reachable tiles.**

Refusing to place unreachable loot is simpler and safer than carving corridors
through rock (which would fight the generator and could still strand things).
The flood-fill is the reachability primitive `CLAUDE.md` flags for the future
region graph — built early, reused later.

### Pass 4 — Scatter

- **Trees** on `:grass` via rejection sampling with a minimum spacing, so they
  form loose groves rather than a uniform grid or a single clump.
- **Loose items** as ground entities (`entity/make-item`):
  - `:wood` near trees
  - `:stone` near rock
  - `:food` on open grass
Placement reads `:reachable` from `state`: when it is a set (Plan 2, after the
connectivity pass), scatter is constrained to it; when it is `nil` (Plan 1, the
pass hasn't run), scatter treats every valid tile as allowed. With no
impassable rock masses in Plan 1 the map is trivially connected, so `nil` is
the correct "no constraint" default — and adding Pass 3 later changes scatter's
behavior with zero edits to the scatter pass itself.

## Determinism

`clojure.core/rand`/`rand-int` are **not** seedable — they delegate to a shared
global `Math/random`. Generation must thread explicit seeded randomness.

**Decision:** each pass derives its **own** `java.util.Random` from
`(:seed state) + pass-offset` (distinct constant offset per pass). Passes are
therefore independent and order-insensitive — adding, removing, or reordering
passes does not perturb any other pass's output. `:seed` comes from `opts`
(default to the world's `:rng-seed`).

This is what makes rule 2 above (reorderable pipeline) *safe*: if passes shared
one threaded `Random`, reordering the vector would silently change every map.
Per-pass seeds buy determinism *and* the composability the user asked for from
one decision — same seed → byte-identical map; swapping passes in and out never
shifts the others.

## Public API & REPL surface

```clojure
;; Pure core:
(worldgen/generate world {:seed 42})        ; => world'

;; Lifecycle:
(reset-world! {:generate? true :seed 42})   ; fresh generated world
;; initial-world stays empty-grass; :generate? routes through worldgen/generate

;; REPL helper (dev/user.clj):
(generate-world! {:seed 42})                 ; reset + generate, print status
;; then (spawn-pawn! …) (go!)
```

`opts` keys: `:seed`, optional `:width`/`:height` (default to current 40×20),
and tuning thresholds with sensible defaults. `initial-world` remains
empty-grass so unit tests and the REPL keep a blank slate.

## Testing strategy (headless, no GL)

- **noise**: same `(x,y,seed)` → identical value; output in expected range;
  field is smooth (adjacent samples close).
- **determinism**: `(generate w {:seed s})` twice → identical `:grid` and
  identical entity set.
- **band proportions**: with fixed seed, terrain-type counts fall within
  tolerance of threshold expectations (guards against threshold regressions).
- **connectivity (Plan 2)**: every scattered tree and item sits on a tile in
  the reachable set; the spawn clearing is open.
- **entity placement**: trees only on `:grass`; items have valid `:pos`,
  `:kind :item`, expected materials.

Render layer (`flora`) is not unit-tested (GL draw), consistent with existing
layers; `flora`'s geometry is trivial (one sprite per tree entity at its pos).

## Staging

**Plan 1 — playable baseline**
`noise.clj` + Pass 1 (base) + Pass 4 (scatter) + tree entity + flora layer +
sprites + API/REPL helper + tests for noise/determinism/placement. End to end:
generate → varied ground/water + trees + haulable loot; pawns haul it with the
existing job. No rock, no connectivity guard (map trivially connected).

**Plan 2 — formations + reachability**
Pass 2 (CA rock) + Pass 3 (connectivity guard) layered in as two new pure
passes, scatter constrained to the reachable set, plus connectivity/band tests.
Zero changes to Plan 1's passes — the pipeline is the extension point.

## Open questions / deferred

- Tuning constants (thresholds, CA fill %, iteration count, tree spacing, item
  densities) — set sensible defaults, tune live in the REPL during
  implementation.
- Default map size — keep 40×20; larger maps show the hybrid terrain better and
  are opt-driven.
- `:sand`/`:soil` + `:fertility` terrain — add with farming.
- Spawn-point contract — Plan 2 carves a center clearing; whether `generate`
  should also stash a suggested `:spawn [x y]` in the world for callers is a
  small decision to settle during Plan 2.
