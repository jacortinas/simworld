# Content/State Split + Tiny Def DB — Design

*Date: 2026-05-25 · Status: approved-pending-spec-review*

## Context & goal

Game content is hard-coded across the sim today: `sim.tile/terrain` (move-cost,
passable?), `sim.entity/item-defs` (weight, char), and the lone
`sim.simulation/need-decay-per-rare` scalar. These are *content* — values a
designer or modder would tune ("add a terrain," "make a material heavier") —
embedded in code.

**Goal:** introduce the **content/state split** from
`docs/rimworld-engine-internals.md` §3 — an immutable, EDN-loaded **Def
database** kept strictly separate from the mutable, saved game state — and move
the use-time content into it. This is step 2 of the revised build plan
(`docs/rimworld-engine-internals.md` §4), chosen now because it is "cheap now,
structural later": getting the boundary right while the content is tiny is what
keeps saves small/forward-compatible and content data-driven, and avoids
re-creating RimWorld's hardest problem (cyclic save graphs + cross-ref
resolution) by accident.

## Background: the two rules that make this a strict win

From §3, immutable Clojure turns RimWorld's two hardest problems (save/load and
content moddability) into near-freebies **only if** we obey two rules from day
one:

1. **Reference defs and entities by id/keyword, never by embedding.** State holds
   `:material :wood`, not the wood def; the grid holds `:grass`, not the terrain
   def. The world stays an acyclic map nippy writes directly.
2. **Separate the immutable content DB (Defs, from EDN, never saved) from the
   mutable game state (entities, saved via nippy).**

The codebase is *already* largely compliant with rule 1 — the grid stores
terrain keywords and items store a `:material` keyword, looked up at use time.
This step makes rule 2 explicit and lifts the def tables out of code, without a
rip-and-replace.

## Scope decision: use-time content only

Not every hard-coded number is a def. **Content** (designer/modder-tunable game
data) belongs in defs; **mechanism** (engine constants) does not. A second, more
decisive cut is *when the value is read*:

- **Use-time reads** (pathfinding, render, a tick) happen long after startup, so
  reading them from the loaded registry is always safe.
- **Construction-time reads** (inside `make-pawn`/`make-item`/`make-tree`) would
  make "constructing any entity" require defs-loaded-first — a load-order
  coupling that bites tests, the REPL, and worldgen (the documented "source
  order matters at cold start" gotcha).

**This step migrates only use-time content**, where the require graph guarantees
load order with no new temporal dependency:

| Value | Read at | This step |
|---|---|---|
| `tile/terrain` (move-cost, passable?, char) | use-time (pathfinding/render/inspect) | **MIGRATE** → `terrain.edn` |
| `entity/item-defs` (weight, char) | use-time (terminal render) | **MIGRATE** → `materials.edn` |
| `need-decay-per-rare` | use-time (decay system, a tick) | **MIGRATE** → `needs.edn` |
| pawn starting `:needs` | construction-time (`make-pawn`) | **LEAVE** (seam) |
| `:ticker-type` defaults per kind | construction-time (`make-*`) | **LEAVE** (seam) |

Leaving the construction-time values as documented seams avoids signing up for
RimWorld's "DefDatabase loaded before any Thing spawns" ordering guarantee for
three values that almost never change.

## Design

### Architecture

A new **`sim.defs`** namespace owns an immutable content registry: a `defonce`
atom populated from bundled EDN at namespace-load. This mirrors the established
pattern in this codebase — `sim.schedule`'s system registry is a `defonce` atom
that `sim.simulation` fills via top-level calls at load. Defs **never enter the
world map and are never saved.** Sim code reads them through small lookup fns.

```
sim.defs            ; NEW — registry atom + load + lookup + spec.
                    ;       Depends on NOTHING in sim (only clojure.edn / io / spec).
  ├─ resources/defs/terrain.edn     ; move-cost, passable?, char
  ├─ resources/defs/materials.edn   ; weight, char
  └─ resources/defs/needs.edn       ; per-need decay rate

sim.tile       → sim.defs   ; terrain-info/passable?/move-cost bodies read defs
sim.simulation → sim.defs   ; decay reads defs/need-decay
sim.render     → sim.defs   ; the one item-defs reader (legacy terminal renderer)
```

No cycles: `sim.defs` requires only `clojure.edn`, `clojure.java.io`,
`clojure.spec.alpha`. `resources` is already on the classpath (`deps.edn`
`:paths`), so no build change is needed; EDN is read via `io/resource
"defs/<file>.edn"`.

**Load-order safety is free.** Every reader requires `sim.defs`, so `sim.defs`
(and its top-level `load!`) runs before any reader's first call. This is exactly
why construction-time content was left out — it is read earlier and in more
contexts than the require graph cleanly covers.

### The `sim.defs` API

```clojure
;; Registry — defonce so the atom identity survives :reload; content is
;; repopulated from EDN on every ns load (edit EDN + reload picks it up live).
db                          ; (atom {:terrain {...} :material {...} :need {...}})

;; Table lookups (use-time reads; graceful fallback, matching today's terrain-info)
(terrain k)    => entry-map ; unknown key -> :grass entry (preserves current behavior)
(material k)   => entry-map
(need k)       => entry-map

;; Convenience on top of (need k)
(need-decay k) => double    ; (:decay (need k)) or a documented fallback constant (0.0125)

;; Loading + validation
(load!)        ; reset! db from the bundled EDN sources, spec-validating each entry
(reload-defs!) ; REPL helper (dev/user.clj) — re-run load! without reloading the ns
```

`load!` takes a **sequence** of EDN sources and `merge`s them (later wins) — the
seam for future mod-merging. No inheritance / XPath PatchOperations now (YAGNI;
see Out of scope).

### Registry shape

```clojure
{:terrain  {:grass {:char \. :move-cost 1.0  :passable? true}
            :dirt  {:char \, :move-cost 1.15 :passable? true}
            ...}
 :material {:wood  {:char \w :weight 0.7}
            :stone {:char \s :weight 1.0}
            :food  {:char \f :weight 0.3}}
 :need     {:food       {:decay 0.0125}
            :rest       {:decay 0.0125}
            :recreation {:decay 0.0125}}}
```

`needs.edn` is keyed per-need — faithful to RimWorld's per-`NeedDef` fall rates
and the natural EDN shape — but all three ship at `0.0125`, the current uniform
rate, so decay is **numerically identical** to today. Differentiated rates
become a data edit, not a code change.

### Migration mechanics (behavior-preserving)

- **`sim.tile`**: delete the `terrain` def. `terrain-info`/`passable?`/
  `move-cost` keep their exact signatures but read `(defs/terrain k)`. **Zero
  call-site changes** in `sim.pathfinding` / `sim.ai` / `sim.command` /
  `sim.worldgen` / `sim.inspect` — they all already go through these wrappers.
- **`sim.entity`**: delete `item-defs`. `make-item` is untouched (it only
  *stores* the `:material` keyword — construction stays def-free, so `sim.entity`
  gains **no** dependency on `sim.defs`).
- **`sim.render`** (legacy terminal renderer, REPL path-viz only): one line,
  `(entity/item-defs (:material item))` → `(defs/material (:material item))`.
- **`sim.simulation`**: delete `need-decay-per-rare`; `decay-needs` looks up each
  need's rate via `defs/need-decay`, defaulting gracefully so a need lacking a
  def still decays. EDN ships all three at `0.0125` → decay unchanged.

`:char` is legacy (the terminal renderer only; sprites drive real rendering via
`sim.render.sprites`). It is carried into the EDN so the terminal renderer keeps
working with no behavior change; it is not load-bearing for the game.

### Validation (the clojure.spec deliverable)

Specs for each entry shape, checked **at load** — a malformed EDN entry throws
`ex-info` carrying `s/explain-str` (fail-fast, clear message):

- `::terrain-entry` — `:move-cost` pos-double (req), `:passable?` boolean (req),
  `:char` char (opt)
- `::material-entry` — `:weight` pos-double (opt), `:char` char (opt)
- `::need-entry` — `:decay` double in `[0.0, 1.0]` (req)

Rule 1 ("reference by id in state") is upheld *structurally* — defs live outside
the world; state only ever holds keywords — and reinforced by a save round-trip
test asserting `:defs` never appears in a saved world. A full world-state spec is
**out of scope** (a separate effort); lookups keep their graceful fallback so a
dangling reference degrades rather than crashes.

### The purity caveat (accepted, documented)

`tick` now transitively reads a global atom (the decay rate; and pathfinding
already reads terrain defs mid-tick). Because defs are **immutable after load**,
this is morally a constant read: determinism holds (two same-seed runs stay
identical), and it is exactly RimWorld's `DefDatabase` model. This is documented
in `CLAUDE.md` as the one deliberate exception to "tick reads only its world
arg," endorsed by the choice of the global-registry location over a `:defs`
world key.

## Testing (headless, pure — no GL)

- **Behavioral equivalence (the migration guard):** `tile/move-cost` and
  `tile/passable?` return identical values to the old hard-coded table for all
  six terrains; pawn needs after N rare ticks are identical (within float
  tolerance) to pre-migration decay.
- **Load + spec:** well-formed EDN loads to the expected registry shape; a
  malformed entry (e.g. negative `:move-cost`, non-boolean `:passable?`) throws
  with a useful message; every *shipped* EDN entry is `s/valid?`.
- **Lookup:** known keys resolve to their entry; an unknown terrain key falls
  back to `:grass` (matches `terrain-info` today).
- **Never-saved invariant:** a `save!` → `load!` round-trip produces a world with
  no `:defs` key (defs are not part of state).
- **Reload ergonomics:** `reload-defs!` after editing an EDN value reflects the
  new value through the lookup fns (atom identity preserved via `defonce`).

## Out of scope / future (seams left)

- **Thing-defs for ticker-type + starting-needs** (construction-time content) —
  RESOLVED in `2026-05-29-thing-defs-design.md`: the `:thing` def category +
  `sim.entity/make-thing`. The load-order coupling was a non-issue (the require
  graph loads `sim.defs` before any construction).
- **Mod-merge beyond plain `merge`** and **def inheritance / XPath
  PatchOperations** — the `load!` source-sequence seam is left; the machinery is
  not built.
- **Full world-state clojure.spec** — entry-shape validation is built; a spec
  over the entire saved world is a separate effort.
- **Differentiated per-need decay values** — the per-need EDN shape is ready;
  values are uniform for now.

## Files touched

- **New:** `src/sim/defs.clj`, `resources/defs/terrain.edn`,
  `resources/defs/materials.edn`, `resources/defs/needs.edn`,
  `test/sim/defs_test.clj`
- **Modified:** `src/sim/tile.clj` (terrain table → defs lookup; require
  `sim.defs`), `src/sim/entity.clj` (remove `item-defs`), `src/sim/render.clj`
  (`item-defs` → `defs/material`), `src/sim/simulation.clj` (decay rate →
  `defs/need-decay`; require `sim.defs`), `dev/user.clj` (`reload-defs!` helper),
  `CLAUDE.md` (content/state-split decision + files-to-know + purity caveat)
