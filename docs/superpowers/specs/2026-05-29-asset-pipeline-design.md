# Asset Pipeline & Graphics — Design Direction

*Date: 2026-05-29 · Status: **direction / future** — not yet scheduled; key
decisions are deliberately left open (see Open questions). This captures the
diagnosis and the evolution path so the analysis isn't lost; it is **not** an
approved, implementation-ready spec.*

## Context & goal

The thing-defs system (`2026-05-29-thing-defs-design.md`) makes entity *types*
data-driven, and its planned content-diversity follow-up adds a broad
RimWorld-flavored roster of types. A diverse roster of *things* needs a diverse
roster of *images* — and the current sprite system is the bottleneck, because
**sprite selection is code, not content**, the image set is a fixed handful of
sheets, and every drawn sprite is welded to a single 32px tile footprint.

**Goal:** evolve the asset pipeline so that (a) sprite selection is *content* a
thing-def carries, (b) the image set can grow to hundreds of sprites at *mixed
source resolutions* without code edits, and (c) a sprite's native size can be
decoupled from its tile footprint (multi-cell objects) — all while preserving the
two invariants the current renderer holds: **real-time animation is a render lie
off sim-time**, and the **pure-core / untested-GL-draw split** per layer.

## Current state — the three hard-coded assumptions

The system works well at toy scale; these are the assumptions that block scale,
not defects.

### 1. Sprite selection lives in render code, not the Def DB

`sim.render.sprites` holds keyword → `[sheet col row]` lookup tables —
`terrain->cell`, `material->cell`, `pawn-cell`, `tree-cell` — transcribed by hand
from the tileset's `.txt` files. `sim.render.anim/animated-terrain` is the same
shape for animation: `terrain-key → {:sheet :row :frames :fps}`. These are
*exactly* keyword→data content tables, but they sit in render namespaces instead
of `sim.defs`. The render layers reach in by hand: `layers.items` calls
`sprites/item-region (:material item)`; `layers.pawns` calls `pawn-region`;
`layers.terrain` calls `anim/terrain-cell` + `sprites/region`.

Consequence: **adding a thing type's image requires a code edit** to a lookup
table or a layer — the same "this should be content" smell that terrain
move-cost had before the content/state split.

### 2. Fixed sheet set + uniform-cell slicing

`sheet-files` is a hard-coded 4-entry map (`:tiles`/`:rogues`/`:items`/
`:animated`), each loaded as one GPU `Texture`. `sprites/region` slices a cell at
`(col*32, row*32)` of size 32 — `sprite-size` is a `^:const 32`. Growing the
image library means either growing this map by hand or overflowing fixed grids,
and **every source image must be 32px on a uniform grid**.

### 3. Native sprite size is welded to the tile footprint

Every layer's `.draw` stretches the region to `tile-size` (also 32): e.g.
`layers.terrain` draws each region at `(ts, ts)`. So a sprite's *pixel size*, its
*tile footprint*, and the global *tile-size* are all the same number (32). A
1×2 tree, a 2×2 building, or a higher-resolution sprite drawn crisply has no
representation.

## Non-goals (for the eventual effort)

- Not a renderer rewrite. The SpriteBatch + per-layer compositor + camera model
  stays; this changes *what regions resolve from* and *how they're sized*.
- Not a new animation model. The wall-clock `frame` function and the
  render-lie-on-real-time invariant stay; animation becomes a *graphic property*
  rather than a hard-coded terrain table.
- Not asset *authoring* tooling (sprite editors, etc.). Scope is loading,
  selection, and sizing of assets the project already ships.

## The evolution path — three moves, built just-in-time

The recommended sequencing is **demand-driven**: each move lands when the prior
content step makes its absence painful, not speculatively.

### Move 1 — Sprite selection becomes content (graphic refs on defs)

*Trigger: the first time content diversity needs a second sprite for a kind
(e.g. a new tree or resource type), forcing a lookup-table code edit.*

Lift the cell tables into the Def DB, mirroring the content/state split. Two
shapes to choose between (see Open questions):

- **(a) A `:graphic` field directly on the thing-def** — `{:kind :item
  :material :wood :graphic [:tiles 6 17]}`. Simplest; co-locates the image with
  the type.
- **(b) A separate `:graphic` def category** that thing-defs reference by id —
  the orthogonal-axis move (like `:material`), so one graphic can be shared by
  many types and a graphic can carry richer data (animation, variants, draw
  size) without bloating every thing-def.

Either way, the render layers stop hard-coding and resolve generically:
`entity → (:def entity) → graphic`. Terrain/animation tables migrate the same
way (`animated-terrain` becomes graphic data: `frames`/`fps` are content). A new
type then ships its image as **pure data** — the zero-code-change success
criterion the content-diversity pass depends on.

Note: this move is valuable *even before* the atlas exists — the `:graphic` value
can still be a `[sheet col row]` cell against the current sheets. It is the
cheapest, highest-leverage move and the direct continuation of the def work.

### Move 2 — Named-asset loader / `TextureAtlas`

*Trigger: the image library outgrows ~four hand-maintained sheets, or a needed
source image isn't 32px-on-a-grid.*

Replace the fixed `sheet-files` map + manual cell-slicing with a libGDX
**`TextureAtlas`**: a build step (`TexturePacker`) packs a directory of arbitrary
source images into atlas pages; at runtime `atlas.findRegion("name")` returns an
`AtlasRegion` referenced by **name**, not grid coordinates. This:

- removes hand-transcription of `[sheet col row]` (graphic refs become names);
- supports an arbitrary number of images without a growing code map;
- supports **mixed source resolutions** for free — each image is packed at its
  own size and the region carries its own dimensions;
- supports index-based animation frames natively (`findRegions("water")` →
  `water_0, water_1, …`), folding the `frame` index lookup into the atlas.

The cache (`region-cache`) and the GL-thread `load!`/`dispose!` lifecycle carry
over; `region` changes from grid-slicing to atlas name lookup. `Nearest`
filtering for crisp pixel art is preserved (atlas filter settings).

### Move 3 — `Graphic` abstraction (size, multi-cell, variants, autotile)

*Trigger: the first object that doesn't fit one 32px cell — a tall tree, a
multi-tile building, an autotiled wall.*

Introduce a small `Graphic` concept (RimWorld's model) so the *native sprite
size* and the *tile footprint* are independent. A graphic carries `:draw-size`
and `:draw-offset` in tile units; the layer draws at `(draw-size * tile-size)`
anchored by the offset, instead of always `(tile-size, tile-size)`. This unlocks,
as graphic *variants* under one umbrella:

- **multi-cell objects** (a 1×2 tree, a 2×2 building);
- **random variants** (pick a sprite from a set by entity id — RimWorld's
  `Graphic_Random` — so a field of grass isn't uniform);
- **autotiling** for walls/edges via the existing `autotiles.png`
  (RimWorld's `Graphic_Linked` — pick the sprite from passable neighbors), the
  noted-but-unbuilt feature in `CLAUDE.md`;
- **animation as a graphic kind** (frames/fps as graphic data), unifying the
  terrain animation special-case into the general path.

## libGDX mechanisms (reference)

- **`TextureAtlas` / `TextureAtlasData`** — runtime named-region lookup;
  `findRegion(name)` / `findRegions(name)`; pages are `Texture`s.
- **`TexturePacker`** (gdx-tools, build-time) — packs a folder of images into
  `.atlas` + page PNGs; handles disparate source sizes, whitespace stripping
  (region carries `originalWidth`/offsets), and `name_index` animation grouping.
- **`AtlasRegion`** — a `TextureRegion` plus packing metadata (original size,
  offsets, index) — the natural carrier for "native size" in Move 3.
- Keep `Texture$TextureFilter/Nearest` for crisp pixel art under zoom.

## RimWorld model (reference)

- **`graphicData`** on a `ThingDef`: `texPath`, `graphicClass`, `drawSize`
  (Vector2 in cells), `drawOffset`, `color`, `shaderType`. This is Move 1 (the
  ref) + Move 3 (the sizing) combined.
- **`Graphic` subclasses**: `Graphic_Single`, `Graphic_Multi` (rotations),
  `Graphic_Random`, `Graphic_Linked`/`Graphic_Appearance` (autotile/edges),
  `Graphic_StackCount`. Our Move 3 is a minimal subset chosen by demand.

## Invariants to preserve

- **Real-time animation is a render lie off sim-time.** Frame selection stays a
  pure function of wall-clock millis (`anim/frame`); the sim never sees a frame
  number, so determinism (same-seed bit-identical runs) is untouched. Animated
  graphics keep rippling while *paused*.
- **Pure-core / untested-GL split per layer.** Selection/sizing logic (which
  region, what draw size) stays pure and headless-tested; the `.draw` GL calls
  stay untested, as with every current layer.
- **Selection is content, never embedded in state.** A graphic is referenced by
  keyword/name from a def; the world map never holds a `Texture`/`TextureRegion`
  (rule 1 — acyclic, save-safe).

## Open questions / decisions to make when scheduled

1. **Graphic-as-field vs graphic-as-def-category** (Move 1 (a) vs (b)). Lean (b)
   if variants/animation/size land soon (richer data, shared graphics); (a) if
   the roster stays 1:1 and simple. Decide at brainstorming time.
2. **When to introduce the atlas** — keep `[sheet col row]` refs through Move 1
   and switch to names in Move 2, or jump straight to names? (Affects how Move 1
   graphic refs are written.)
3. **`tile-size` vs `sprite-size` decoupling** — today both are 32 and equal.
   Move 3 separates "world tile size" (a camera/layout constant) from "sprite
   native size" (per-graphic). Confirm the camera/zoom math tolerates this.
4. **Build step for `TexturePacker`** — packing at build vs a checked-in
   `.atlas`; where the source images and atlas live (`resources/` vs the current
   `32rogues/` working-dir path noted in `sprites.clj`).
5. **HUD font** — the separate gdx-freetype upgrade noted in `CLAUDE.md` is
   adjacent (also an asset concern); decide whether it rides this effort or
   stays independent.

## Relationship to other specs

- **`2026-05-29-thing-defs-design.md`** — provides the `:graphic` extension point
  (its future section names this doc). Move 1 here is the continuation of that
  refactor for the image axis.
- **Content-diversity follow-up** (named in the thing-defs spec) — the *demand*
  that triggers Moves 1–2. The asset pipeline scales exactly when the roster
  does.

## Files likely touched (when built)

- `src/sim/render/sprites.clj` — `region` lookup (grid → atlas name); `load!`/
  `dispose!` for the atlas; remove the hand-coded cell tables.
- `src/sim/render/anim.clj` — animation defs become graphic data; `terrain-cell`
  generalizes.
- `src/sim/defs.clj` (+ `resources/defs/`) — `:graphic` field or `graphics.edn`
  category + spec + lookup.
- `src/sim/render/layers/*.clj` — resolve `entity → def → graphic`; draw at
  `draw-size`, not always `tile-size`.
- New build asset(s) — `.atlas` + packed pages (Move 2).
