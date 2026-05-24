# Debug overlay layer — design

Status: **design approved, not built.** A dev-facing rendering layer that
draws debugging info (pathfinding routes first; more later) over the world,
toggled on/off by a single flag.

## The problem it solves

Pawn paths already exist as data — every walking pawn carries its A* route at
`(get-in pawn [:job :path])` — but nothing draws it. When a pawn paths weirdly
there's no way to *see* the route in the window; you fall back to the REPL
`show-path` (terminal ASCII). We want an in-window overlay you can flip on to
inspect routing live, with room to grow (AI state, tile coords, needs) behind
the same switch.

## Core idea: one more pure layer, gated by a view flag

A debug overlay is just another render layer — a pure `(draw world batch ...)`
projection, composed after `pawns` so it sits on top. What's new is a single
boolean that gates it. Because the overlay reads state the sim already stores,
this is purely additive: zero touches to sim code, jobs, or existing layers.

```
 world layers (world cam):  terrain → items → pawns → debug?
                                                        └─ only when (ui/debug?)
```

## State: a `:debug?` flag in `sim.ui-state`

Whether the overlay shows is a **view** concern, not world state — same
reasoning that keeps the camera and selection out of `sim.world` (so it never
hits a save file or a future headless server). It lives next to `:selected`:

```clojure
(defn debug? [] (:debug? @ui-state))           ; nil/false by default → off
(defn toggle-debug! []                         ; returns the new boolean
  (:debug? (swap! ui-state update :debug? not)))
```

`update :debug? not` is reload-safe by construction: the `ui-state` atom
survives `defonce` across reloads and may predate the new key, so `not` on
`nil` yields `true` — the toggle just works on a live session, no reset needed.

## The layer: `sim.render.layers.debug`

Two parts, split so the geometry is testable without a GL context:

**Pure core —** `(path->segments path tile-size height)` → a vector of
`[x y w h]` rects. For each consecutive tile pair it emits one axis-aligned
rect: a thin line between the two tile centers. Paths are 4-connected
(`tile/neighbors-4`, never diagonal), so every segment is horizontal or
vertical — no diagonal-line math. Uses terrain's `(height-1-y)` Y-flip so the
line registers exactly with the sprites. A `nil` path or a 1-tile path
(start == goal) yields no segments.

**Draw —** `(draw world batch tile-size pixel)`:
- returns immediately unless `(ui/debug?)`;
- for every pawn with a path, draws its segments plus a small square at the
  goal tile, using the **shared 1×1 pixel texture** (`@pixel` in `gdx`) tinted
  a debug color (cyan), stretched per rect — the same solid-rect trick the HUD
  uses, no `ShapeRenderer`;
- resets the batch tint to white when done, the same discipline the pawns
  layer already follows so later draws aren't tinted.

v1 draws **all** pawns' paths in one tint. Selected-pawn emphasis (brighter
tint for `(ui/selected)`) is a trivial later add — deliberately omitted now.

## How it wires into existing code

- **`sim.render.gdx` `render()`** — one line in the world-camera block, after
  `pawns`, passing the existing pixel texture:
  `(debug/draw w b tile-size px)`. Drawing under the world cam means the
  overlay pans and zooms with the map.
- **`sim.input` `keyDown`** — add a branch: the backtick key
  (`Input$Keys/GRAVE`) calls `(ui/toggle-debug!)` and returns `true`. Called
  **directly**, not injected like `on-toggle-pause`: `input` already depends on
  `ui-state` (for zoom/pan), so this adds no new cross-namespace dependency.
  The injection pattern existed only to keep `input` decoupled from the
  *clock*; it doesn't apply here.
- **`dev/user.clj`** — a `(debug!)` helper calling `(ui/toggle-debug!)`,
  printing/returning the new state, for REPL-driven toggling.

## Hot-reload caveat

The backtick key and the new `keyDown` branch will **not** hot-reload into a
running window — the live input proxy was built from the old class at
`create`, so editing the proxy body needs a window restart (`(halt!)` then
`(go!)`), same rule as `run-loop!`. The flag logic (`sim.ui-state`) and the
layer (`sim.render.layers.debug`) *do* reload live via var late-binding.

## Testing

- `path->segments` is pure → unit-test it: a known L-shaped path produces the
  expected axis-aligned rects; `nil` and single-tile paths produce `[]`.
- `toggle-debug!` is pure state → unit-test the `nil → true → false` cycle.
- `draw` itself (GL calls) stays untested by unit tests, as the other layers
  are — the testable logic was pulled out into `path->segments`.

## YAGNI notes

- **One master flag, not a per-feature registry.** Future visualizations (AI
  state, needs bars, tile coords) become new private draw-fns called from the
  same `draw`, all under the one `:debug?` switch. If individual toggles are
  ever wanted, the single namespace grows into that without rework — don't
  build the registry up front.
- **No selected-pawn emphasis in v1.** All paths, one color. Add emphasis when
  pawn counts make the overlay cluttered enough to need it.
- **Backtick is the chosen key** (conventional debug/console key); trivially
  swapped to F1 if it conflicts with anything later.
