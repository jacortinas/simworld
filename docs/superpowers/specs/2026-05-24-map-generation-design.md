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
- A **separate repo / published `gridnoise` library** — the generic core is
  built dependency-clean now (own package, no game imports) but stays in this
  repo. Extraction to `:local/root` / git-dep / Clojars is deferred until a real
  second consumer exists. Packaging adds friction, not decoupling.
- Migrating `sim.tile` onto `gridnoise.grid` — the game's existing grid/render/
  pathfinding code is left untouched; this is a possible future cleanup.
- Tying generation to a "New Game" screen — screens aren't built yet; would
  block worldgen on the screen state machine. Generation is REPL/lifecycle
  driven for now.
- Animated/autotiled terrain edges — separate future work.

## Architecture

The system is split into **two layers** with a strict one-way dependency:

```
  sim.worldgen   (game layer: classification, pipeline, entity scatter)
        │  depends on
        ▼
  gridnoise.*    (generic core: grid algorithms, noise, image — NO game imports)
```

### Two layers: generic core vs. game

A new top-level package, **`gridnoise.*`**, holds everything that knows nothing
about colonies — pure math and data over a generic grid. It lives at
`src/gridnoise/` (a different package than the game's `src/sim/`), on the same
classpath, so no `deps.edn` change is needed. The package name itself makes the
boundary visible: anything under `gridnoise.*` that imports `sim.*` is a bug.

**The dependency rule (the entire reusability win):** `sim.worldgen` →
`gridnoise.*`, **never** the reverse. The core has zero game imports, so it is
reusable as-is and extractable later (to a `:local/root` sub-module, then a
git-dep, then Clojars) by moving the directory and adding a deps entry — *if*
this rule holds from day one. We do **not** make a separate repo now (YAGNI: no
second consumer exists yet; packaging adds versioning/cross-repo friction but
zero additional decoupling).

A grid in the core is generic over its cell type:
`{:width :height :cells [...]}` — cells are doubles for a noise field, booleans
for a CA mask, or (in the game) terrain keywords. The game's existing `:tiles`
vector is already this shape, so `sim.worldgen` builds `gridnoise` grids
internally and emits a plain `:tiles` vector at the end. (`sim.tile` stays as
is — no refactor of working pathfinding/render code. Having `sim.tile` sit on
`gridnoise.grid` is a possible *future* cleanup, explicitly out of scope here.)

The game layer's `sim.worldgen` exposes:

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

**Generic core — `gridnoise.*` (no game imports):**

| File | Change | Purpose |
|---|---|---|
| `src/gridnoise/grid.clj` | new | Generic grid `{:width :height :cells}`: `idx`, `in-bounds?`, neighbors, `flood-fill`, `ca-step`, `map-cells` |
| `src/gridnoise/noise.clj` | new | Composable noise primitives: seeded lattice value, `smoothstep`, octave/fbm combinator |
| `src/gridnoise/image.clj` | new | Render a grid/field to a grayscale `BufferedImage` / PNG via `javax.imageio.ImageIO` — standalone "see the noise" tooling |
| `test/gridnoise/grid_test.clj` | new | Index math, neighbors, flood-fill, ca-step |
| `test/gridnoise/noise_test.clj` | new | Noise determinism, value range, smoothness |

**Game layer — `sim.*` (depends on `gridnoise`, never the reverse):**

| File | Change | Purpose |
|---|---|---|
| `src/sim/worldgen.clj` | new | Pipeline + four passes; terrain classification; consumes `gridnoise` |
| `src/sim/entity.clj` | edit | `make-tree`, `:tree` kind, `trees` query |
| `src/sim/render/sprites.clj` | edit | `tree-region` (tiles cell `26.c`) |
| `src/sim/render/layers/flora.clj` | new | Draw tree entities (z: terrain < flora < items < pawns) |
| `src/sim/render/gdx.clj` | edit | Insert flora layer into the compositor |
| `src/sim/world.clj` | edit | `reset-world!` gains `:generate?` + seed opts |
| `dev/user.clj` | edit | `(generate-world! opts)` REPL helper |
| `test/sim/worldgen_test.clj` | new | Classification, determinism, connectivity, placement |

The flora render layer stays untested, matching the existing
terrain/items/pawns convention (GL `draw` calls are not unit-tested). All pure
logic — in both layers — is tested headless. The `gridnoise` core is tested in
isolation (its tests import no `sim.*`), which is the proof that the boundary
holds.

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

The noise field comes from the generic `gridnoise.noise` primitives: a seeded
lattice-value lookup, `smoothstep` interpolation, and an octave/fbm combinator
that sums scaled samples. The pass is a thin `elevation`/`moisture` → terrain
classifier *in the game layer* over those game-agnostic primitives — so the
noise character (frequency, octaves) is tunable without touching the
classifier, and the same primitives back any other consumer (or a noise-image
dump for debugging).

Builds the `:tiles` vector wholesale via `(vec (for [i (range (* w h))] …))` —
**not** repeated `set-tile` calls (`set-tile` is for incremental edits later,
e.g. mining one tile).

### Pass 2 — Rock (cellular automata) — *Plan 2*

Adds organic stone masses on top of the noise base:

1. Random-fill a boolean rock mask (a `gridnoise` grid of booleans) at ~42%
   density.
2. Run 4 iterations of `gridnoise.grid/ca-step`: a cell becomes rock if ≥5 of
   its 8 neighbors are rock (border-out-of-bounds counts as rock so masses hug
   edges).
3. OR the resulting mask onto the base grid as `:stone`.

Gives cave/ridge-like contiguous formations that the noise threshold alone
produces more diffusely. `ca-step` is a generic `grid → grid` in the core
(parameterized by the survival predicate); only the OR-onto-terrain step is
game-specific.

### Pass 3 — Connectivity guard — *Plan 2*

Guarantees everything generated is reachable, **without mutating terrain**:

1. Carve a small guaranteed-open spawn clearing near map center (force those
   tiles to `:grass`).
2. Flood-fill the reachable set from the clearing via `gridnoise.grid/flood-fill`
   (generic 4-connected fill, parameterized by a passability predicate the game
   supplies).
3. Write the reachable set into `state`; **scatter places only on reachable
   tiles.**

Refusing to place unreachable loot is simpler and safer than carving corridors
through rock (which would fight the generator and could still strand things).
`flood-fill` lives in the core (it is pure grid traversal); the passability
predicate — "is this terrain keyword passable?" — is the only game-specific
part, passed in. This is the reachability primitive `CLAUDE.md` flags for the
future region graph: built generic, reused later.

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
;; Generic core (game-agnostic — usable from any project / the REPL):
(noise/field {:seed 7 :freq 0.08 :octaves 4})  ; => fn [x y] -> double in [0,1]
(grid/flood-fill g start passable?)             ; => set of reachable coords
(grid/ca-step mask survive?)                    ; => smoothed boolean grid
(image/spit-png! "noise.png" g)                 ; => write a grayscale PNG to eyeball

;; Game pipeline (consumes the core):
(worldgen/generate world {:seed 42})            ; => world'  (pure)
(worldgen/generate world {:seed 42 :passes [...]}) ; custom/partial pipeline

;; Lifecycle:
(reset-world! {:generate? true :seed 42})       ; fresh generated world
;; initial-world stays empty-grass; :generate? routes through worldgen/generate

;; REPL helper (dev/user.clj):
(generate-world! {:seed 42})                     ; reset + generate, print status
;; then (spawn-pawn! …) (go!)
```

`opts` keys: `:seed`, optional `:width`/`:height` (default to current 40×20),
optional `:passes` (override the pipeline), and tuning thresholds with sensible
defaults. `initial-world` remains empty-grass so unit tests and the REPL keep a
blank slate. The `image/spit-png!` helper is the concrete payoff of the
"noise images for different purposes" idea — a standalone way to see a field
without booting libGDX.

## Testing strategy (headless, no GL)

**Generic core (`gridnoise.*`, tested in isolation — no `sim.*` imports):**

- **noise**: same `(x,y,seed)` → identical value; output in `[0,1]`; field is
  smooth (adjacent samples close).
- **grid**: `idx`/`in-bounds?`/neighbors correctness; `flood-fill` reaches
  exactly the connected region under a given predicate; `ca-step` applies the
  survival rule and handles borders.

The fact that these tests import no game code is the executable proof that the
boundary holds (rule from the architecture section).

**Game layer (`sim.worldgen`):**

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
`image/spit-png!` is exercised by an optional smoke test (write to a temp file,
assert non-empty) but not pixel-asserted.

## Staging

**Plan 1 — generic core + playable baseline**
Build `gridnoise.grid` (index/neighbors + `map-cells`) and `gridnoise.noise`
(value noise + fbm) and `gridnoise.image` (PNG dump) first, tested in isolation.
Then the game layer: Pass 1 (base) + Pass 4 (scatter) + tree entity + flora
layer + sprites + API/REPL helper. End to end: generate → varied ground/water +
trees + haulable loot; pawns haul it with the existing job. No rock, no
connectivity guard (map trivially connected). `gridnoise.grid`'s `flood-fill`
and `ca-step` may land in Plan 1 (cheap, generic, unit-tested) even though no
game pass uses them yet, or defer to Plan 2 — implementer's call.

**Plan 2 — formations + reachability**
Pass 2 (CA rock, via `gridnoise.grid/ca-step`) + Pass 3 (connectivity guard,
via `gridnoise.grid/flood-fill`) layered in as two new pure passes, scatter
constrained to the reachable set, plus connectivity/band tests. Zero changes to
Plan 1's passes — the pipeline is the extension point.

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
