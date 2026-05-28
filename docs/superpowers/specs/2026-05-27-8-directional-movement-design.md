# 8-Directional Movement with Smooth Animation

**Date:** 2026-05-27
**Status:** Approved → implementing

## Goal

Make pawns move and path like RimWorld: diagonal steps allowed, and the sprite
*glides* between cells instead of teleporting one tile per tick. The grid stays
canonical — pawns always *logically* occupy an integer cell. "Off the grid" is
really "decouple the **drawn** position from the **logical** cell," which is the
sim-time vs. real-time split this project already enshrines.

## Premise correction

RimWorld does NOT take pawns off the grid. It stacks two tricks on a grid that
never goes away:

1. **8-directional pathing** — A* may step to diagonal neighbors; logical
   position still snaps cell-to-cell. Diagonals cost ×√2 so they aren't a free
   shortcut, and a "no cutting diagonally between two blocked corners" rule
   applies.
2. **Smooth animation** — purely a *render* lie. The drawn `(x,y)` is
   interpolated between the cell the pawn is leaving and the one it's entering,
   based on movement progress. The simulation never goes continuous.

## Decisions (settled during brainstorming)

- **Scope:** all three — diagonals + sub-cell movement timing + smooth render.
- **Smoothness lives sim-side:** the pawn stores integer-tick progress; render
  reads it and lerps. Deterministic, saves for free, matches RimWorld. (NOT
  render-side prev/current snapshot blending.)
- **Speed settings:** *compatibility only* this spec — no speed code, but the
  model must not preclude it. It doesn't: everything is denominated in **ticks**,
  so a future clock tick-rate multiplier scales movement/needs/bands together
  for free. **Invariant: movement state is denominated in ticks, never
  wall-clock.**
- **Cost model — unified tick-currency:** terrain `move-cost` *is* the traversal
  cost. A* minimizes Σ(move-cost × diagonal-factor); movement spends the same
  quantity × a per-pawn base. Routing and travel-time share one number, so they
  can't disagree.
- **D1 — corner rule:** block a diagonal step when *either* flanking cardinal
  cell is impassable (strict, no clipping past a wall corner).
- **D2 — `:pos` timing:** `:pos` flips to the **destination** cell at move
  *start*; the move record holds the cell being left; render lerps `from → pos`.
  Matches RimWorld occupancy (a moving pawn occupies the cell it is entering for
  reservation/collision). Consequence: a mid-move pawn is selectable on its
  *leading* cell.

## Design

### Piece 1 — 8-directional A* (`sim.pathfinding`, pure)

- Neighbors: `neighbors-4` → `neighbors-8` (already exists in `sim.tile`).
- **Shared cost fn `traversal-cost grid from to`** → double: `move-cost(to) ×
  (diagonal? √2 : 1)`, or `+Infinity` if `to` is impassable. A* uses it as the
  per-step cost; movement (Piece 2) multiplies it by the pawn's base ticks.
- Heuristic: `manhattan` → **octile** `(dx+dy) + (√2−2)·min(dx,dy)`. Required for
  admissibility once diagonals are legal (Manhattan over-estimates → non-optimal
  paths). Admissible because the cheapest passable terrain is `move-cost 1.0`
  (grass is the floor).
- **Corner rule:** when expanding a diagonal neighbor, skip it if either flanking
  cardinal cell is impassable.

Independent and shippable on its own.

### Piece 2 — sub-cell movement timing (`sim.entity` + `sim.job`, pure)

- **Pawn gains `:move-ticks`** (base ticks to cross one cardinal grass cell;
  hardcoded ~15 in `make-pawn`, forward-compatible with a future per-pawn speed
  stat as a multiplier). Construction-time content stays in `make-*` per the
  existing seam.
- **Job gains a `:move` record** while gliding: `{:from [x y] :to [x y]
  :elapsed N :cost N}`, all integer ticks. No `:move` ⇒ pawn is settled. It
  lives in the job (no job ⇒ no movement ⇒ no move state), keeping every
  `walk-toward` mutation under `[:job …]`.
- **`segment-cost`** = `max(1, round(move-ticks × traversal-cost(grid,from,to)))`.
  Water (`move-cost 2.5`) → a believable ~1 s slog; diagonals → ×√2 more ticks.
- **`walk-toward` becomes a progress accumulator** (same `[result world]`
  signature and `:arrived/:walking/:failed` results — the job FSMs are
  untouched):
  - no path → compute (or `:failed`); set `:path`, `:path-index 0`, `:move nil`.
  - `:move` present → `elapsed++`; when `elapsed ≥ cost`, the segment completes:
    if at the last cell → `:arrived` (clear `:move`); else immediately start the
    next segment (no dead tick).
  - no `:move`, not at end → start a segment: `:pos = to`, `:path-index = next`,
    `:move {…:elapsed 0 :cost (segment-cost …)}`.
- `next-phase` also nils `:move` (defensive; arrival already clears it).

### Piece 3 — remove the AI speed gate (`sim.ai`, pure)

`advance-job`'s 15-tick `moves-this-tick?` gate *complected* deliberation cadence
with walk speed. Delete `move-period`/`moves-this-tick?`; `advance-job` calls
`job/advance` **every tick** so `:move.elapsed` accumulates smoothly. Speed now
lives entirely in `segment-cost`.

### Piece 4 — smooth render (`sim.render`, real-time view)

- **New pure ns `sim.render.interp`** with `draw-pos entity tile-size
  grid-height` → `[px py]` bottom-left world pixels, Y-flipped. Lerps
  `from → pos` by `elapsed/cost` when the entity has `[:job :move]`; else snaps
  to `:pos`. The `progress` float is computed here (render), never stored — the
  sim stays integer-only and deterministic.
- **Pawns layer** draws the sprite at `draw-pos`.
- **Selection layer** uses `draw-pos` for the box's bottom-left so it stays glued
  to the gliding sprite. `selection-box-rects` is refactored to take world-pixel
  `[px py]` (the Y-flip moves into `draw-pos`), which also removes its duplicated
  flip math.

## Saves / errors / determinism

- **Saves:** `:move` / `:move-ticks` are plain map data → nippy round-trips them
  (`save.clj` freezes `(dissoc world :schedule)`). Old saves load settled.
- **Errors:** no-path still `:failed`; a stale `:move` after load just isn't
  in-flight. Unknown terrain still falls back to grass via `sim.defs`.
- **Determinism:** integer-tick accounting + a √2 constant; the interpolation
  float is render-only. Same-seed runs stay bit-identical.

## Testing (headless)

1. **`pathfinding_test`** (new): diagonal path beats the 4-dir route; corner not
   cut; octile returns the optimal path; impassable goal → nil.
2. **`job_test`** (add): `segment-cost` (cardinal/diagonal/terrain); `:pos` flips
   after exactly `cost` ticks; multi-segment traverses in summed-cost ticks;
   arrival fires; existing haul/eat/go-to still complete.
3. **`interp_test`** (new): `draw-pos` endpoints + midpoint + snap-when-settled.
4. **`selection_layer_test`** (update): px-based `selection-box-rects`.

GL `.draw` stays untested, as with the other layers.

## Build order

1. Diagonals (independent).
2. Sub-cell timing + AI-gate removal (independent of diagonals).
3. Smooth render (needs #2).
