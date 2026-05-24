# Tile Inspect + Entity Selection — Design Spec

**Date:** 2026-05-24
**Status:** Approved, ready for implementation planning
**Topic:** A RimWorld-style hover inspect panel plus click-to-cycle entity selection

## Context

The game renders terrain, items, trees, and pawns, but there is no way to read
a tile's details or to select most things. Today:

- `sim.ui.hud` draws a bottom status bar (30px, `bar-h`) under a fixed UI
  camera; it owns screen-space widget layout and already "eats" clicks on the
  bar so they don't leak to the world.
- `sim.input` proxies an `InputAdapter`, handling click/drag/wheel/keys. Its
  `mouseMoved` is currently unused. `screen->tile` unprojects a screen pixel to
  a tile through the world camera (correct under any pan/zoom).
- `sim.ui-state` holds view state (`:camera`, `:selected`, `:debug?`) — plain
  data, never serialized. `:selected` currently holds a *pawn* id only.
- `sim.command` is the single namespace that touches both the world and
  ui-state atoms; `left-click!` currently selects a pawn.
- `sim.render.layers.pawns` tints the selected pawn yellow — its own docstring
  calls this "a stopgap until a proper world-space selection box lands."
- `sim.tile/terrain-info` exposes `{:char :move-cost :passable?}` per terrain
  keyword; `sim.entity/entity-at`/`items-at`/`pawns`/`trees` query occupants.

## Goals

1. **Hover inspect panel** — a fixed pane in the bottom-right (above the status
   bar) showing the tile under the cursor as **minimal conceptual lines**, each
   line one concept, right-aligned, truncated to a max length:
   - **Terrain line:** type + move-speed % on one line (`Grass 100%`). If the
     terrain is impassable, omit the % (`Stone (impassable)`).
   - **Entity lines:** one per *selectable* entity on the tile (`Tree`,
     `Apple`, `Dave`).
2. **Click-cycle selection** — left-click cycles `:selected` through the
   selectable entities on the clicked tile (repeated clicks advance and wrap);
   a different tile starts at its first; an empty tile clears selection.
   *Selectable* = things that matter (items, trees, pawns; future
   animals/monsters) — never the base terrain.
3. **World-space selection marker** — a box outline around the selected
   entity's tile, for any selectable kind, replacing the pawn-tint stopgap.

## Non-goals (YAGNI)

- **Action menus / pop-ups.** Pawns and other actionable things will later get
  pop-up menus of important actions; that is a separate future feature. This
  spec is **informational only** — no action buttons, and the panel consumes no
  clicks. Selection here is the *substrate* those future menus will read
  (select-then-act), but no actions are built now.
- **A right-side panel.** Future informational panels may live on the right
  edge; out of scope here. The bottom-right pane is the only panel now.
- **Selected-entity inspect detail.** The panel always reflects the *hovered*
  tile, not the selected entity (decided during brainstorming). A richer
  per-entity detail view is future work.
- **Dynamic fields (growth rate, fertility, health).** Trees are inert; no such
  mechanics exist. The panel shows only data that exists today; new mechanics
  append lines later for free.
- **Pixel-accurate truncation.** Truncation is by character count (the default
  libGDX font is not monospace); a fixed char cap is the v1 rule.

## Architecture

Three separable parts over a shared pure core. The design mirrors the
established split: a pure, headless-tested data layer (`sim.inspect`) and dumb
GL renderers (`inspect-panel`, `selection` layer) — the same discipline as
`debug-layer` (pure `path->segments` + untested `draw`).

### New / edited files

| File | Change | Purpose |
|---|---|---|
| `src/sim/inspect.clj` | new | PURE: `describe-tile`, `selectable-at`, `selectable-kinds`, + helpers. Headless-tested. |
| `src/sim/ui_state.clj` | edit | add `:hover` + `hover`/`set-hover!` |
| `src/sim/input.clj` | edit | override `mouseMoved` → `ui/set-hover!` |
| `src/sim/command.clj` | edit | `left-click!` → cycle selection |
| `src/sim/ui/inspect_panel.clj` | new | GL: draw the bottom-right concept-line pane |
| `src/sim/render/layers/selection.clj` | new | pure `selection-box-rects` + GL `draw` (world-space box) |
| `src/sim/render/layers/pawns.clj` | edit | drop the `selected-tint` stopgap + `selected-id` arg |
| `src/sim/render/gdx.clj` | edit | wire `mouseMoved`; add selection layer (world block) + inspect panel (UI block); update pawns call |
| `test/sim/inspect_test.clj` | new | `describe-tile`, `selectable-at`, truncation, impassable |
| `test/sim/command_test.clj` | new (or extend) | cycle-select logic |
| `test/sim/selection_layer_test.clj` | new | `selection-box-rects` geometry |

GL `draw` fns (panel, selection layer, pawns) stay untested, matching the
terrain/items/pawns/debug convention. All pure logic is tested headless.

## Part 1 — Hover panel

### `sim.inspect` (pure)

```clojure
(def selectable-kinds #{:pawn :item :tree})   ; future: :animal :monster

(defn selectable-at [world [x y]] ...)  ; -> entities at tile whose :kind is
                                        ; selectable, SORTED BY :id (stable)

(defn describe-tile [world [x y]] ...)  ; -> vector of concept-line strings,
                                        ; or nil if [x y] is out of bounds
```

`describe-tile` rows:
- **Terrain line:** `"<Type> <speed>%"` when passable, else `"<Type> (impassable)"`.
  - Type = capitalized terrain keyword name (`:grass` → `"Grass"`).
  - speed % = `round(100 / move-cost)` (grass 1.0 → 100, dirt 1.15 → 87,
    gravel 1.30 → 77, water 2.5 → 40).
  - passability from `tile/terrain-info`.
- **Entity lines:** for each entity from `selectable-at`, a label:
  - pawn → its `:name`
  - item → capitalized `:material` (e.g. `"Wood"`)
  - tree → `"Tree"`
- Every line truncated to `max-line-len` chars (const, ~28) with a trailing
  `...` when cut.

Bounds check uses `tile/in-bounds?`; off-map → `nil` (so `sim.input` can store
raw coords without a `sim.tile` dependency).

### `sim.ui-state`

Add `:hover` (a `[tx ty]` vector or nil) with `hover`/`set-hover!`. View state,
like `:camera` — never serialized.

### `sim.input`

Override `mouseMoved [screen-x screen-y]`: unproject via the existing
`screen->tile`, `(ui/set-hover! [tx ty])`, return `false` (never consume — it
must not block other handling). `sim.input` already depends on `sim.ui-state`,
so calling `ui/set-hover!` directly adds no new coupling (same precedent as
backtick→debug).

### `sim.ui.inspect-panel`

`draw` reads `(ui/hover)`, calls `inspect/describe-tile`; if nil, draws nothing.
Otherwise renders the lines right-aligned in the bottom-right, just above the
30px status bar, on a translucent rounded-ish rect (reusing the 1px pixel
texture + the HUD font). Panel height grows with line count. **Consumes no
clicks** (purely visual). Drawn under the fixed UI camera, in the same block as
the HUD bar.

## Part 2 — Click-cycle selection

`sim.command/left-click!` (the one ns touching both atoms) becomes:

```
sels = (inspect/selectable-at world [tx ty])   ; sorted entities
ids  = (map :id sels)
cur  = (ui/selected)
next = cond
         (empty? ids)            -> nil                 ; empty tile: clear
         (cur is in ids)         -> next id after cur, wrapping
         else                    -> first id            ; new tile: first
(ui/select! next)
```

Repeated clicks on one tile cycle through its entities and wrap. Right-click
(orders) is unchanged. The HUD still eats clicks on the bar first (unchanged).

`sim.command` requires `sim.inspect` (no cycle: `inspect` requires only
`entity`/`tile`).

## Part 3 — World-space selection marker

`sim.render.layers.selection`:

```clojure
(defn selection-box-rects [[x y] tile-size grid-height] ...)
;; -> 4 thin rects [[px py w h] ...] outlining the tile, with the (height-1-y)
;;    Y-flip, same convention as debug-layer. Pure, tested headless.

(defn draw [world batch tile-size pixel]
  ;; look up (ui/selected) -> entity -> :pos; if pos present, draw the 4 rects
  ;; tinted via the 1px pixel texture. No-op if nothing selected or :pos nil.
  ...)
```

`sim.render.layers.pawns`: remove `selected-tint` and the `selected-id`
parameter; pawns always draw untinted. Selection feedback is now uniform across
all kinds via the box.

`sim.render.gdx`:
- Wire `mouseMoved` into the input processor (via `make-processor`; call
  `ui/set-hover!` directly inside the proxy, consistent with existing direct
  `ui` calls).
- World block: after the entity layers, add `(selection/draw w b tile-size px)`
  (before or alongside the debug overlay; both are world-space markers).
- UI block: after `(hud/draw …)`, add `(inspect-panel/draw b f px w)`.
- Update the `pawns-layer/draw` call to drop the selected-id arg.

Note: the `render` proxy body is captured at window creation, so these
compositor edits take effect on the next process launch (not live hot-reload) —
the documented `run-loop`/proxy exception in CLAUDE.md.

## Testing strategy (headless, no GL)

- **`sim.inspect`**: off-map → nil; a grass tile → `["Grass 100%"]` (+ entity
  lines); an impassable tile → `["Stone (impassable)"]` (no %); move-speed % for
  dirt/gravel/water; truncation past `max-line-len` ends with `...`;
  `selectable-at` returns only selectable kinds, sorted by id, excludes terrain,
  and is empty on a bare tile.
- **`sim.command` cycle**: on a tile with [tree, pawn] sorted by id — first
  click selects the first; second click advances; click past the last wraps;
  clicking an empty tile clears; clicking a different populated tile selects its
  first. Drive via the world + ui-state atoms (restore them in a `finally`).
- **`selection-box-rects`**: 4 rects, correct positions/thickness, Y-flip
  applied, matching the tile's world pixel rect.

GL `draw` fns (inspect-panel, selection, pawns) are not unit-tested.

## Staging

- **Plan A — hover panel.** `sim.inspect` (incl. `selectable-at`), `ui-state`
  `:hover`, `input` `mouseMoved`, `sim.ui.inspect-panel`, gdx wiring (mouseMoved
  + panel draw). Tests for `inspect`. End to end: hovering a tile shows the
  bottom-right concept-line panel.
- **Plan B — selection cycling + marker.** `command/left-click!` cycle (reusing
  `selectable-at`), `sim.render.layers.selection`, remove the pawn tint, gdx
  wiring (selection layer + pawns call). Tests for cycle logic + box geometry.
  End to end: clicking cycles selection, shown by a world-space box.

## Open questions / deferred

- Max line length constant — pick ~28 chars; tune in the REPL.
- Entity-line cap — tiles rarely hold many selectable entities; if a cap is
  ever needed, append a `"+N more"` line. Not built now.
- Panel styling (colors, padding, exact width) — follow the HUD bar's palette;
  refine live.
- Action menus, right-side panels, per-entity detail views — future features
  that read the `:selected` substrate built here.
