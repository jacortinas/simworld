# Stockpile Zones + Placement Mode — Design

*Date: 2026-05-25 · Status: approved-pending-spec-review*

## Context & goal

Auto-haul (the "colonists gather loose items into a pile" loop) needs a
*destination*: a stockpile. This spec builds the way to create one — a reusable,
RimWorld-style **drag-rectangle placement mode** — plus the stockpile zone data
model and its rendering. Pawns hauling *into* stockpiles is the next, pure-sim
spec (a third think-tree leaf).

The placement mode is deliberately built as reusable infrastructure: "drag a
rectangle, get a set of cells" is the same mechanic a future building-placement
tool will use, so the drag/commit core is kept independent of the stockpile
payload.

## Decisions (settled in brainstorming)

1. **Scope:** zones + placement mode + rendering now; auto-haul into them is the
   next spec. Two focused features.
2. **Mode entry:** a **hotkey** (`Z` enters stockpile-zone mode; left-drag
   paints; `Esc` or right-click exits). No new HUD button — a proper Architect
   menu is future.
3. **World vs view split:** `:zones` lives in the **world** (drives behavior in
   spec B, must be saved). The in-progress drag rectangle lives in **ui-state**
   (a preview, never saved) — the same split as `:selected`/`:hover`.

## Architecture

```
sim.zone (NEW, pure)          — zone data model + rectangle geometry (headless-tested)
sim.ui-state                  — + :mode (:select | :zone-stockpile) and :drag (in-progress rect)
sim.input                     — mode-aware left-drag: paint instead of select; Z enters, Esc/RMB exit
sim.command                   — commit-stockpile! (the one world+ui bridge, as today)
sim.render.layers.zones (NEW) — pure rects + GL fill (selection-layer pattern), drawn just above terrain
```

## Data model (`sim.zone`, pure)

```clojure
;; world gains:  :zones [] , :next-zone-id 1
;; a zone:       {:id 1 :kind :stockpile :cells #{[x y] ...}}

(cells-in-rect a b)        ; => #{[x y]} inclusive, normalized (drag any direction)
(add-stockpile world a b)  ; => world' : new zone from the rect's in-bounds, PASSABLE,
                           ;    un-zoned cells; adds NOTHING if that set is empty
(stockpile-cells world)    ; => #{[x y]} union across zones (spec B's haul reads this)
(cell-zoned? world cell)   ; => bool
(zones world)              ; => seq of zone maps
```

- **Multiple distinct zones**, each with an `:id` from the world's
  `:next-zone-id` counter (deterministic, save-stable) — so "move/place new
  ones" works in a later spec.
- A cell belongs to **at most one** zone. A drag skips already-zoned cells and
  impassable cells (no stockpiles on water/walls). The grid is consulted in
  `add-stockpile` (which has the world); `cells-in-rect` stays pure geometry.
- An empty resulting cell-set creates **no** zone.

## The drag flow (`sim.input`, mode-aware)

- **`Z`** → `ui/set-mode! :zone-stockpile`. **`Esc`** or **right-click** → back
  to `:select` (clears any in-progress drag).
- **left `touchDown`** (after the existing HUD eats-click check):
  - `:select` mode → today's `command/left-click!` (cycle-select).
  - `:zone-stockpile` mode → start a drag: `ui/set-drag! {:start tile :current tile}`.
- **left `touchDragged`** in zone mode → `ui/set-drag!` updates `:current` to the
  hovered tile (live preview).
- **left `touchUp`** in zone mode → `command/commit-stockpile! start current`
  (which `swap!`s `zone/add-stockpile`), then clear the drag.

The existing middle-drag pan and the proxy's local `drag` atom (screen coords)
are untouched — the zone preview is separate state in ui-state so the render
layer can read it. `Z`/`Esc` are added to `keyDown` (alongside `SPACE`/`GRAVE`);
WASD/arrow panning is polled in the render loop, so there is no key conflict.

## ui-state additions

`:mode` (default `:select`) and `:drag` (`{:start [tx ty] :current [tx ty]}` or
nil), with helpers `mode`/`set-mode!`, `drag`/`set-drag!`/`clear-drag!`. Note:
`reset-camera!` currently `reset!`s the whole atom — it will be adjusted to
preserve (or re-establish) the new keys, or the new keys default safely when
absent (the `defonce`-survives-reload precedent).

## Rendering (`sim.render.layers.zones`)

Same pure-geometry / untested-GL discipline as `sim.render.layers.selection`:

- `zone-cell-rects world tile-size grid-height` → `[x y w h]` fill rects for
  every stockpile cell (the `(grid-height-1-y)` Y-flip).
- `drag-preview-rects ui-drag tile-size grid-height` → the in-progress
  rectangle's cells.
- `draw` fills committed zones (translucent green) and the live preview
  (translucent, brighter), reusing the shared 1×1 pixel texture and resetting the
  batch tint to white when done. Composed **right after `terrain/draw`** so flora
  / items / pawns render on top of the floor overlay.
- **Mode indicator:** the HUD draws a short label ("Stockpile zoning — drag to
  place, Esc to cancel") when `mode ≠ :select`, so the stateful mode is visible.
  Reuses the existing HUD `BitmapFont`.

## Testing (headless, pure)

- `cells-in-rect`: normalized + inclusive; both drag directions; single-cell drag.
- `add-stockpile`: zones only in-bounds passable cells; skips water/wall and
  already-zoned cells; empty result adds nothing; successive drags accumulate
  distinct zones with increasing ids.
- `stockpile-cells` / `cell-zoned?` / `zones` queries.
- Render geometry (`zone-cell-rects`, `drag-preview-rects`) Y-flip math, mirroring
  `selection-layer-test`.
- ui-state `:mode`/`:drag` helpers.
- Save round-trip: a world with zones survives `save!` → `load!`.
- **Untested (project pattern + background-session limit):** the GL `draw`, the
  input-proxy drag wiring, and the HUD label are not headless-testable; the
  actual in-window drag UX cannot be verified from this session and must be
  eyeballed in the running app.

## Files touched

- **New:** `src/sim/zone.clj`, `src/sim/render/layers/zones.clj`,
  `test/sim/zone_test.clj`, `test/sim/zones_layer_test.clj`
- **Modified:** `src/sim/ui_state.clj` (`:mode`/`:drag` + helpers),
  `src/sim/input.clj` (mode-aware drag + `Z`/`Esc`), `src/sim/command.clj`
  (`commit-stockpile!`), `src/sim/world.clj` (`:zones`/`:next-zone-id` in
  `initial-world`), `src/sim/render/gdx.clj` (compose the zones layer),
  `src/sim/ui/hud.clj` (mode label), `CLAUDE.md` (placement-mode + zone decision
  + files-to-know)

## Out of scope / future (seams left)

- **Auto-haul into stockpiles** — the next spec (pure-sim think-tree leaf reading
  `stockpile-cells`).
- **Move / delete / resize / erase** existing zones (this spec is create-by-drag
  only); **per-zone item filters**; the **Architect menu** HUD; **building
  placement** (the drag core is built so a building tool reuses `cells-in-rect` +
  the mode machinery).
