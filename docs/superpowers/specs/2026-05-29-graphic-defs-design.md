# Graphic Defs & Loose-PNG Pipeline — Design

*Date: 2026-05-29 · Status: **approved / implementation-ready**.*

This is the implementation spec for **Move 1(b) + the sizing core of Move 3**
from the broader `2026-05-29-asset-pipeline-design.md` direction doc, with the
TextureAtlas (Move 2) deliberately deferred. It refines that doc's open
questions into decided answers and adds two things that doc did not cover: a
slicing utility that converts our sheet cells into loose PNGs, and directional
(per-facing) graphics for pawns.

## Goal

Make sprite selection **content**, not code, and decouple a sprite's native
pixel size from the 32px tile footprint, so the coming content-diversity pass
ships new art as pure data. Concretely:

1. A new `:graphic` def category (an orthogonal axis referenced by defs, like
   `:material`), validated by spec, reloadable live.
2. A dev-only utility that slices the 32rogues cells we actually use into loose
   PNGs **at their native 32px** (verbatim, no scaling, no modification),
   establishing the classic-RimWorld "one texture file per graphic" workflow.
   The pipeline is built size-agnostic, so larger / non-32px art is a later
   drop-in, not part of this pass.
3. Per-graphic `draw-size` / `draw-offset` in tile units, so a sprite can be
   larger than its cell (a 1x2 tree) without code changes. Built and unit-tested
   now; all current art stays `[1 1]` until genuinely larger art exists.
4. Directional facing for pawns (up / down / left / right, right mirrored from
   left by default, diagonals collapsing onto left/right), built now and stubbed
   to the single camera-facing sprite until real directional art lands.

## Non-goals (deferred, triggers intact)

- **TextureAtlas / TexturePacker batching** (Move 2). Content references textures
  by id and path, so atlasing later touches zero content. Its trigger is
  draw-call cost at scale, not anything here.
- **Image-strip animation** (multi-frame loose PNGs). Water, our one animated
  terrain, stays a sheet cell with `:anim`, so both source kinds get exercised.
- **Random variants, autotiling** (Move 3 extras).
- **Last-facing persistence.** An idle pawn faces down (camera), rather than
  remembering the way it last walked. Adding memory is new state; defer.
- **Real directional, multi-tile, or higher-resolution art.** The seams are
  built and tested; the art is a later drop-in with no code change. Current
  placeholders are the 32rogues sprites used verbatim at 32px.

## The `:graphic` def category

A new `resources/defs/graphics.edn`, loaded by `sim.defs` into `(:graphic db)`,
spec-validated like every other category. A graphic entry:

```clojure
{;; terrain — single-source loose PNGs
 :grass  {:image "graphics/grass.png"}
 :dirt   {:image "graphics/dirt.png"}
 :gravel {:image "graphics/gravel.png"}
 :stone  {:image "graphics/stone.png"}
 :wall   {:image "graphics/wall.png"}
 ;; water — stays a sheet cell + real-time animation (image-strip anim deferred)
 :water  {:cell [:animated 0 10] :anim {:frames 11 :fps 6}}
 ;; pawn — directional; every facing stubs to the base sprite for now
 :colonist {:image "graphics/colonist.png" :directional true}
 ;; flora
 :tree {:image "graphics/tree.png"}
 ;; items
 :wood  {:image "graphics/wood.png"}
 :rock  {:image "graphics/rock.png"}
 :apple {:image "graphics/apple.png"}}
```

### Fields

- **Source, exactly one of:**
  - `:cell [sheet col row]` — a 32px cell on a preloaded sheet (back-compat;
    used by `:water` and any not-yet-sliced art).
  - `:image "path"` — a standalone PNG at native resolution (the loose-PNG path).
- `:draw-size [w h]` — tile units, optional, default `[1 1]`. A 1x2 tree is
  `[1 2]`; it draws two tiles tall, anchored at its cell's bottom-left, so it
  towers upward (RimWorld's tall-tree look).
- `:draw-offset [ox oy]` — tile units, optional, default `[0 0]`. Shifts the
  draw rect to center oversized or multi-tile art on its cell.
- `:anim {:frames n :fps f}` — optional. Steps the source cell's **column** over
  wall-clock time. Cell-source only for now (image-strip animation is deferred).
- `:directional true` — optional. Marks a Graphic_Multi: the renderer resolves a
  facing and may pick a per-direction override (see Facing).
- `:facings {…}` — optional per-direction source overrides (absent for now):
  `{:up {…source…} :down {…source…} :left {…source…} :right {…source…}}`. Each
  value is itself a source map (`:cell` or `:image`).

### Spec (clojure.spec, validated at load)

- A graphic has **exactly one** top-level source: `:cell` xor `:image` (never
  both, never neither). This base is the fallback for every facing; `:facings`
  only overrides specific directions, so the right-mirrors-left default always
  has a `:left` (or base) to flip.
- `:draw-size` / `:draw-offset` are 2-vectors of numbers; `:draw-size` entries
  are positive.
- `:anim` is `{:frames pos-int :fps pos-number}`.
- `:directional` is boolean; `:facings` keys are a subset of
  `#{:up :down :left :right}`, each value a valid source.

A malformed entry fails fast at load with the offending key named, exactly like
the existing categories.

### Lookup

`sim.defs/graphic` returns the entry or `nil`. Unlike `make-thing`'s fail-fast
construction read, a graphic lookup is a **use-time** read on the render path:
a missing graphic **degrades** (the layer skips it, or draws a fallback box),
never crashes. This matches `terrain`'s grass fallback. The render path must
never throw on bad content.

## Defs reference graphics; instances copy the ref

`thing` and `terrain` defs gain a `:graphic <id>` key:

- `things.edn`: `:colonist {… :graphic :colonist}`, `:tree {… :graphic :tree}`,
  `:wood {… :material :wood :graphic :wood}`, `:food {… :material :food :graphic
  :apple}`, `:stone {… :material :stone :graphic :rock}`.
- `terrain.edn`: each entry gains `:graphic` (`:grass {… :graphic :grass}`, etc).

`:graphic` joins `sim.entity/template-keys`, so `make-thing` copies it onto each
constructed entity, exactly as `:kind` and `:material` are copied today. The
entity holds the graphic **id** (a stable keyword), and the graphic **data**
resolves at use-time from the Def DB, so editing `graphics.edn` and
`(reload-defs!)` re-skins live without reconstructing entities.

Every drawable then answers one question, "what is my graphic id?":

- entities: `(:graphic entity)`
- terrain: `(:graphic (defs/terrain terrain-key))`

This collapses today's four inconsistent lookups (`terrain->cell`,
`material->cell`, the fixed `pawn-cell`, the fixed `tree-cell`) into a single
resolver. As a side benefit, pawns and trees stop sharing one hard-coded sprite,
so per-type art becomes free, which is exactly what content diversity wants.

## Facing (directional graphics)

Facing is a **render concern derived from movement**, never sim state. A walking
pawn carries a `:move {:from :to :elapsed :cost}` segment in its job; the
direction it faces is a pure function of `(:to − :from)`. No new world field, no
save change, no determinism impact (same axis as `interp/draw-pos`).

### Derivation rule

We have 8-directional movement, and our render flips Y (smaller y is up-screen).
Given a step delta `[dx dy]`:

- `dx > 0` → `:right`; `dx < 0` → `:left` (horizontal dominates, so every
  diagonal collapses onto left/right per the design intent).
- `dx = 0` → `dy < 0` is `:up`, `dy > 0` is `:down`.
- No `:move` (idle) → `:down` (facing the camera, the rest pose).

`sim.render.graphic/facing-for` takes the delta (or the pawn's `:move`) and
returns one of `#{:up :down :left :right}`. Pure, headless-tested across all
eight deltas plus idle.

### Source selection per facing

For a directional graphic and a resolved facing:

- `:up` / `:down` / `:left` → the `:facings` override if present, else the base
  source.
- `:right` → the `:facings :right` override **as-is** if present (this enables
  asymmetric characters), **else** the resolved `:left` source flipped
  horizontally (the cheap mirror default).

`sim.render.graphic/source-for` returns `[source flip?]` purely (which source,
and whether to U-flip), headless-tested with and without overrides. Today no
overrides exist, so all four facings resolve to the one `colonist.png`, with
`:right` being that sprite mirrored. The apparatus runs live; the art is
identical in every direction until per-direction PNGs are added, at which point
they drop into `graphics/` plus a `:facings` map with zero render-code change.

## The slicing utility (dev tooling)

A small, headless, GL-free utility (no libGDX needed) lives under `dev/`
(`dev/tools/slice_sprites.clj`). It converts the sheet cells we render into
loose PNGs at native 32px, verbatim:

- Reads `32rogues/<sheet>.png` via Java `ImageIO` into a `BufferedImage`.
- For each manifest entry, `getSubimage(col*32, row*32, 32, 32)` lifts the cell.
- Writes that 32x32 cell **verbatim** (no scaling, no filtering) to
  `graphics/<name>.png` via `ImageIO.write`.

The **manifest is the inverse of the lookup tables we are deleting**: each entry
is `{:sheet :col :row :out}`. The ten sprites:

| out          | sheet     | col,row | source (today)            |
|--------------|-----------|---------|---------------------------|
| grass.png    | tiles     | 4,7     | terrain :grass            |
| dirt.png     | tiles     | 4,8     | terrain :dirt             |
| gravel.png   | tiles     | 4,9     | terrain :gravel           |
| stone.png    | tiles     | 0,1     | terrain :stone            |
| wall.png     | tiles     | 0,2     | terrain :wall             |
| colonist.png | rogues    | 1,6     | farmer (scythe)           |
| tree.png     | tiles     | 2,25    | tree                      |
| wood.png     | tiles     | 6,17    | material :wood (log pile) |
| rock.png     | tiles     | 0,18    | material :stone (rock)    |
| apple.png    | items     | 2,25    | material :food (apple)    |

Water is **not** sliced; it stays `:cell [:animated 0 10] :anim {…}`.

The slice of a `BufferedImage` is pure and unit-tested: feed a known input,
assert the output is 32x32 and pixel-identical to the source cell.

**Why slice at all if we don't scale:** the loose-PNG split (one `Texture` per
file at a path) is the long-term RimWorld content model and the thing worth
proving now; it stands on its own at 32px and exercises the `:image` loader for
real. The 32rogues sprites are used verbatim as placeholders. Pixel resolution
is a separate axis the pipeline already handles (the loader reads each texture's
real size, `draw-size` is in tile units), so larger art is a later drop-in with
no def change.

## Asset directory & licensing

Sliced PNGs live in a working-dir `graphics/` folder (sibling to `32rogues/`),
loaded via `Gdx.files.internal` like the sheets. Moving them to the classpath
(`resources/`) is a later, content-only change since graphics reference by path.

`32rogues/LICENSE.txt` constrains these assets: usable in commercial and
non-commercial work, but **not** "in conjunction with generative artificial
intelligence or machine learning projects," and **no redistribution or resale.**
Implications: the deterministic ImageIO slice (a permitted modification) is fine;
we will **not** ML-process or upscale these assets, and we use them verbatim as
placeholders; and if this repo is public, committing the sheets (and their
derived PNGs) is a redistribution question for the owner to weigh.

## tile-size becomes purely layout

`tile-size` (32) stays the world-grid / camera constant. Sprite native size is
per-graphic via `draw-size`. The two stop being the same number by coincidence,
which is the decoupling the direction doc's open question 3 wanted. Camera and
zoom are untouched: they scale the whole world, and `draw-rect` only sizes the
quad. A 32px source draws at `[1 1]` as 32 world px today; a larger source later
would draw at the same one-tile footprint, its extra detail showing only when
zoomed in.

## Namespaces & responsibilities

Preserve the established **pure-core / untested-GL-draw split** per layer.

- **`sim.defs`** (content) — add the `:graphic` category, its specs, and the
  `graphic` lookup (nil on miss, degrade).
- **`resources/defs/graphics.edn`** (content) — the entries above.
- **`sim.render.graphic`** (NEW, pure, headless-tested):
  - `facing-for` — step delta / `:move` → facing keyword.
  - `source-for` — graphic + facing → `[source flip?]`.
  - `draw-rect` — base anchor `[px py]` + `draw-size` + `draw-offset` +
    `tile-size` → `[x y w h]` (the size/offset/scale math; the Y-flip and the
    glide anchor are supplied by the caller).
- **`sim.render.sprites`** (GL) — keep `load!` / `dispose!` / `region` for
  sheets. Add: scan `graphics.edn` for `:image` paths (top-level and in
  `:facings`) at `load!` and load each as a Nearest-filtered `Texture`, cached by
  path; `graphic-region` (graphic + facing + now-ms → `TextureRegion`,
  dispatching cell vs image, applying the `:anim` frame via `anim/frame`, and the
  U-flip when `source-for` says so). Remove `terrain->cell`, `material->cell`,
  `pawn-cell`, `tree-cell`, and their helper fns.
- **`sim.render.anim`** — keep the pure `frame` (time → frame index, already
  tested). Remove the `animated-terrain` table and `terrain-cell`; animation is
  now graphic data.
- **`sim.entity`** — add `:graphic` to `template-keys`.
- **`resources/defs/{things,terrain}.edn`** — add `:graphic` refs.
- **`sim.render.layers.{terrain,flora,items,pawns}`** — resolve the graphic id,
  pull the region from `graphic-region` and the rect from `draw-rect`, draw the
  rect. The pawns layer additionally computes facing from the pawn's `:move`
  (idle → down). Missing graphic degrades.

## Data flow (one frame)

1. Layer takes a drawable (entity or terrain cell) and finds its graphic id.
2. `defs/graphic id` → graphic data (or nil → degrade).
3. Pawns only: `graphic/facing-for (:move pawn)` → facing.
4. `sprites/graphic-region graphic facing now-ms` → `TextureRegion` (cell/image,
   anim frame, flip).
5. Layer computes the base anchor (`interp/draw-pos` for gliding pawns; the
   static `(height-1-y)` flip for terrain/items/trees).
6. `graphic/draw-rect base draw-size draw-offset tile-size` → `[x y w h]`.
7. `batch.draw region x y w h`.

## Invariants preserved

- **Animation is a render lie on real time.** `anim/frame` stays a pure function
  of wall-clock millis; the sim never sees a frame. Water ripples while paused;
  same-seed runs stay bit-identical.
- **Pure-core / untested-GL split.** `facing-for`, `source-for`, `draw-rect`, and
  the slice-and-scale are pure and tested; the `Texture` loading and the
  `batch.draw` calls stay untested, as with every current layer.
- **Selection is content, never embedded in state.** Entities and terrain hold a
  graphic **keyword**; the world map never holds a `Texture` / `TextureRegion`.
  Saves stay tiny and acyclic.

## Testing strategy

- **`sim.render.graphic-test`** (NEW, headless): `facing-for` for all 8 deltas +
  idle; `source-for` per facing with and without `:facings` overrides (including
  the right-mirrors-left default and the right-override-wins case); `draw-rect`
  for `[1 1]`, `[1 2]`, and a nonzero `:draw-offset`.
- **`sim.defs-test`**: `graphics.edn` loads and validates; `graphic` lookup;
  reject malformed entries (both sources, neither source, negative draw-size, bad
  anim).
- **slice utility test**: a known `BufferedImage` slices to a 32x32 PNG that is
  pixel-identical to the source cell.
- **`sim.entity-test`**: `:graphic` is copied onto constructed entities.
- **Untested (by invariant):** the standalone-image `Texture` loading and every
  layer's `batch.draw`.

## Build sequence

The detailed, test-first task breakdown comes from the writing-plans pass; this
is the intended order.

1. Slicing utility + its test; run it to generate the ten `graphics/*.png`.
2. `:graphic` category in `sim.defs` (specs, lookup) + `graphics.edn` + tests.
3. `sim.render.graphic` pure fns (`facing-for`, `source-for`, `draw-rect`) +
   tests.
4. `sim.render.sprites`: standalone-image loading + `graphic-region`; remove the
   old cell tables. Trim `sim.render.anim` to `frame`.
5. `sim.entity`: `:graphic` template key; add `:graphic` refs to `things.edn`
   and `terrain.edn` + entity test.
6. Rewire the four layers (terrain, flora, items, pawns-with-facing).
7. Verify: full headless suite green, then in-window check (terrain draws, water
   animates, items/trees draw, pawns draw and face-derive while walking).

## Relationship to other specs

- **`2026-05-29-asset-pipeline-design.md`** — this implements that doc's Move 1(b)
  plus Move 3's sizing core, defers Move 2, and decides its open questions:
  graphic-as-category (Q1); keep `[sheet col row]` for the one animated cell and
  use loose-PNG paths otherwise, switching wholesale to atlas names only at Move 2
  (Q2); decouple `tile-size` from sprite size (Q3); a working-dir `graphics/`
  folder with a REPL-run slice utility, no build-time packer yet (Q4); HUD font
  stays independent (Q5).
- **`2026-05-29-thing-defs-design.md`** — `:graphic` is the image-axis
  continuation of that refactor, riding the same `template-keys` copy mechanism.
- **Content-diversity follow-up** — the demand this unblocks: new types ship art
  as a loose PNG plus a `graphics.edn` entry plus a `:graphic` ref, no code edit.
