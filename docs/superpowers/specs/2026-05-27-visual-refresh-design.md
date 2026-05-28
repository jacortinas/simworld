# Visual Refresh — brighten the map

*Design spec, 2026-05-27. Lift the map out of dungeon-murk into a bright,
readable, RimWorld-ish look using 32rogues assets we already own. No worldgen
logic — this re-skins the six existing terrains only.*

## Context

The current map reads dark and bland. The cause is structural, not tuning:

- The GL clear color is `0.05 0.05 0.08` (near-black) — `sim.render.gdx:134`.
- `32rogues/tiles.png` is a **dungeon** tileset: terrain cells are muted colors
  on dark baked-in backgrounds. The terrain layer draws those opaque dark-bg
  sprites, so the whole map inherits the murk.
- **A dark sprite cannot be brightened by tint.** libGDX `batch.setColor`
  multiplies — it darkens or colorizes, never lightens. So real brightening
  needs lighter *source pixels*.

Two assets we already own but never wired up unlock the fix:

- `tiles.png` carries **"(no bg)" detail variants** of every ground terrain
  (e.g. `8.b grass 1` has a dark tile bg; `8.e grass 1 (no bg)` is just the
  tuft, transparent). Drawing the transparent detail over a bright **per-terrain
  color base** brightens the map with zero new art.
- `animated-tiles.png` has a **real water tile** (row 11, "water waves") — a
  brighter blue than the "blank blue floor" we currently fake water with.

The render layer is built for this: `sim.render.layers.zones` already stamps
per-cell tinted color quads with the shared 1×1 `pixel` texture. The terrain
layer does the same trick, then draws the detail sprite on top.

## Goals

1. Bright, readable ground — per-terrain color base + "(no bg)" detail sprite.
2. Real (static) water tile instead of the dark fake.
3. A lighter clear color framing the map.
4. Proper, low-key attribution for the 32rogues art.

## Non-goals (YAGNI)

- **New terrain TYPES** (marsh, sand, deep vs shallow water) — the deferred
  *terrain-palette* thread, which adds defs + worldgen `classify` bands.
- **Water animation** — deferred. v1 uses a single static water frame.
  Animation is its own small feature (a real-time frame-stepping system, since
  animations ride real-time, not the sim clock).
- **Worldgen logic** (rock formations, connectivity) — the deferred *Plan 2*
  thread.
- **Autotiling / terrain edges** (`autotiles.png`) — separate future work.
- **README rewrite** — the README is stale (predates libGDX) but fixing it is
  out of scope here.

## Components

### 1. Content — terrain `:color`

`resources/defs/terrain.edn`: each terrain entry gains a `:color [r g b]` base
(three doubles in `[0,1]`). Starting palette (all live-tunable via
`(reload-defs!)`):

| Terrain | `:color` (approx) | Note |
|---|---|---|
| `:grass`  | `[0.36 0.62 0.30]` | bright green |
| `:dirt`   | `[0.55 0.43 0.28]` | warm tan |
| `:gravel` | `[0.60 0.60 0.62]` | light grey |
| `:water`  | `[0.20 0.45 0.75]` | bright blue (under the opaque water sprite) |
| `:stone`  | `[0.40 0.40 0.42]` | rock grey |
| `:wall`   | `[0.30 0.30 0.32]` | dark grey |

`sim.defs`: add `(s/def ::color (s/coll-of (s/and number? #(<= 0.0 (double %)
1.0)) :kind vector? :count 3))` and include `::color` in `::terrain-entry` as
**`:opt-un`** (a terrain missing `:color` still validates; the layer falls back
to a neutral grey). Add a `terrain-color` accessor (in `sim.defs`, mirrored by a
`sim.tile` wrapper like the other terrain lookups) returning the `[r g b]` or a
neutral fallback.

### 2. Sprites — `sim.render.sprites`

- Repoint ground terrains to their **"(no bg)" detail cells** (cols e/f/g in
  `tiles.txt`): `:grass [4 7]`, `:dirt [4 8]`, `:gravel [4 9]`.
- Add `:animated "32rogues/animated-tiles.png"` to `sheet-files`. Map `:water`
  to `[:animated 0 10]` — the **first frame of the water-waves row**
  (`animated-tiles.txt` row 11 → 0-indexed row 10, col 0). `sprites-test` asserts
  the cell is in-bounds for the sheet, catching an off-by-one if the row index is
  wrong.
- Keep `:stone [0 1]` and `:wall [0 2]` (opaque rock/wall cells).
- `terrain-region` keeps its grass fallback.

### 3. Terrain layer — `sim.render.layers.terrain`

New signature `(draw world batch tile-size pixel)`. Per tile, uniformly:

1. Set batch tint to the terrain's `:color`; stamp a base color quad with the
   `pixel` texture (the `zones.clj` `cell-rect` / `.draw batch pixel x y w h`
   pattern, same `(height-1-y)` Y-flip).
2. Reset tint to white; draw the detail sprite (`sprites/terrain-region`) on top.

Transparency does the branching: "(no bg)" tufts (grass/dirt/gravel) let the
bright base show; opaque sprites (water/stone/wall) cover it. No per-terrain
special-casing in the loop. The base quad under an opaque sprite is one cheap
wasted draw — acceptable, keeps the loop flat. Reset batch color to white at the
end (caller contract).

Update the call site `sim/screens/play.clj:51` from
`(terrain/draw world batch tile-size)` to
`(terrain/draw world batch tile-size pixel)` (`pixel` is already destructured
from the ctx at `play.clj:38`).

### 4. Clear color — `sim.render.gdx`

Lift the `glClearColor` at `gdx.clj:134` from `0.05 0.05 0.08` to a lighter
neutral void tone (e.g. `0.12 0.13 0.16` — tunable). Now that every tile has a
base color quad, the clear color shows only *beyond* the map bounds, framing the
bright map rather than bleeding through it.

### 5. Attribution — `CREDITS.md`

New repo-root `CREDITS.md` crediting **32rogues © 2024 Seth Boyles**
(<https://sethbb.itch.io/32rogues>). License: commercial/non-commercial use
permitted, modification allowed, no redistribution/resale; credit appreciated
(not required). Low-key — one file, no inline code/UI links.

## Testing (headless)

- **`test/sim/defs_test.clj`** — assert every terrain def has a `:color` that is
  a 3-vector of doubles in `[0,1]`; assert `terrain-color` returns it, and
  returns the neutral fallback for an unknown terrain. This also guards the new
  spec (an out-of-range or wrong-shape `:color` fails `load!`).
- **`test/sim/sprites_test.clj`** — extend the cell-map validation to cover the
  new `:animated` sheet entry and the repointed "(no bg)" ground cells (the test
  validates `terrain->cell` against the `.txt` and that cells are in-bounds for
  their sheet).
- The terrain `draw` stays **GL-untested**, consistent with every render layer
  (only its pure color lookup is covered, above).
- **Manual verify:** `(generate-world! {:seed 42})`, open the window, eyeball
  the brightness, tune the palette live with `(reload-defs!)`.

## Files

| File | Change |
|---|---|
| `resources/defs/terrain.edn` | add `:color` to each terrain |
| `src/sim/defs.clj` | `::color` spec on `::terrain-entry`; `terrain-color` accessor |
| `src/sim/tile.clj` | `terrain-color` wrapper (delegates to defs) |
| `src/sim/render/sprites.clj` | "(no bg)" ground cells; `:animated` sheet; water cell |
| `src/sim/render/layers/terrain.clj` | base-quad + detail draw; new `pixel` arg |
| `src/sim/screens/play.clj` | pass `pixel` to `terrain/draw` |
| `src/sim/render/gdx.clj` | lighter clear color |
| `CREDITS.md` | new — attribution |
| `test/sim/defs_test.clj` | `:color` validation + accessor tests |
| `test/sim/sprites_test.clj` | new sheet + cell coverage |
| `CLAUDE.md` | note the brightened terrain render + `:color` content field |

## Forward compatibility

- The `:color` field and the base-quad layer are exactly what the deferred
  *terrain-palette* thread needs: new terrains (marsh, sand, deep water) just add
  a def with `:color` + a sprite cell — the layer renders them with zero changes.
- Static water → animated water is additive: an `:animated` sheet is already
  loaded; the future real-time frame-stepper picks the water frame by wall-clock
  time instead of always frame 0.
