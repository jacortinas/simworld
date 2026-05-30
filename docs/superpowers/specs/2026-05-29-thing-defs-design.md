# Thing-Defs — Data-Driven Entity Types — Design

*Date: 2026-05-29 · Status: approved-pending-spec-review*

## Context & goal

The content/state split (`2026-05-25-content-state-split-design.md`) migrated
*use-time* content into the Def DB (terrain, materials, need-decay) but
deliberately left **construction-time content** hard-coded in the `make-*`
constructors:

- `sim.entity/make-pawn` → `:needs {:food 1.0 :rest 1.0 :recreation 1.0}`,
  `:ticker-type :never`, `:move-ticks 15`, empty `:traits`/`:skills`
- `sim.entity/make-item` → `:ticker-type :long`
- `sim.entity/make-tree` → `:ticker-type :never`

That spec deferred this for one stated reason: construction-time reads would
make "constructing any entity require defs-loaded-first — a load-order coupling
that bites tests, the REPL, and worldgen."

**Goal:** close that seam by introducing **thing-defs** — a per-*type*
construction template registry (RimWorld's `ThingDef` ↔ `Thing` split). An
entity instance references its type by a `:def` keyword and carries only mutable
state; the type's construction-time content lives in EDN. This makes entity
*types* (pawns, items, and later buildings/animals) data-driven, the foundation
those future categories plug into.

The deferral reason no longer holds (see *Load-order & purity*): `sim.defs`
depends on nothing in `sim`, so the require graph guarantees defs load before
any `make-*` call — the same "load-order safety is free" property the use-time
migration relied on.

## Decisions settled during brainstorming

1. **Granularity: per-*type* thing-defs** (not per-`:kind`). A def per entity
   type (`:colonist`, `:tree`, `:wood`, …), so the model unlocks type variety
   and is the foundation buildings/animals plug into — not merely a lift of three
   constructors into data.
2. **Items and materials stay orthogonal.** `materials.edn` remains a shared
   *stuff* property table (weight, later beauty/flammability), referenced by item
   thing-defs via `:material` and reusable by future buildings. The item axis
   ("what kind of thing") and the material axis ("what it's made of") do not
   collapse into one entry.

## The model

A fourth Def category, `:thing`, holds **construction templates** — one entry
per entity type. An entity instance gains a `:def <id>` back-reference and keeps
its `:kind` (the cheap category axis used by `entity/pawns`, `entity/items`,
`entity/trees`).

```
defs/things.edn  (immutable content)        entity instance (mutable state)
:colonist {:kind :pawn ...}            →     {:id 7 :def :colonist :kind :pawn :pos [3 4] ...}
```

`:kind` sits *below* the def: `:colonist` and a future `:raider` would both be
`:kind :pawn`, so every existing `(= :pawn (:kind %))` query survives untouched.

## `resources/defs/things.edn`

```clojure
{:colonist {:kind :pawn :ticker-type :never :move-ticks 15
            :needs {:food 1.0 :rest 1.0 :recreation 1.0}
            :traits #{} :skills {}}
 :tree     {:kind :tree :ticker-type :never}
 :wood     {:kind :item :ticker-type :long :material :wood}
 :food     {:kind :item :ticker-type :long :material :food}
 :stone    {:kind :item :ticker-type :long :material :stone}}
```

Item def-ids stay `:wood`/`:food`/`:stone` — identical to their material
keywords (the current 1:1) — so **every existing call site keeps working**
(`worldgen` calls `make-item :wood`, etc.). The orthogonal axis is the def's
`:material` ref; a future `:steel-sword` would be `{:kind :item :material
:steel}`. No registry collision: `:thing`, `:material`, and `:terrain` are
separate categories, so the keyword `:stone` appearing in all three is fine
(`{:thing {:stone …} :material {:stone …} :terrain {:stone …}}`).

`:traits #{}` / `:skills {}` are carried in the def (not the wrapper) so a
future `:raider {:skills {:shooting 5}}` is a pure data edit. `:job`/`:carrying`/
`:carried-by` are **not** content — they are runtime scaffolding (always nil at
birth) added by the typed wrappers.

## `sim.defs` changes

Follows the established category pattern; small and additive.

- `default-sources` gains `:thing ["defs/things.edn"]`.
- New specs:
  - `::kind` — `keyword?` (**open**: adding `:building` is a pure data edit, the
    whole point of the feature).
  - `::ticker-type` — `#{:never :rare :long}` (**closed**: this set is the
    engine's scheduler bands, and a typo would silently mis-schedule an entity).
  - `::move-ticks` — positive number; `::needs` — map of keyword → double in
    `[0.0, 1.0]`; `::material` — keyword; `::traits` — set of keyword;
    `::skills` — map of keyword → number.
  - `::thing-entry` — `(s/keys :req-un [::kind ::ticker-type] :opt-un
    [::move-ticks ::needs ::material ::traits ::skills])`.
  - Register `:thing ::thing-entry` in `entry-spec`.
- New lookup `(thing k)` → entry map or `nil`.

## `sim.entity` changes

A generic core with the three existing constructors as thin delegating wrappers,
preserving every current signature and call site:

```clojure
(defn make-thing
  "Construct an entity instance of thing-def `def-id` at `pos`. Reads the
   immutable def for construction-time content (kind, ticker-type, needs,
   move-ticks, material, traits, skills) and stamps the engine fields
   (id, def back-ref, pos). Throws if `def-id` is unknown."
  [def-id pos]
  (let [d (or (defs/thing def-id)
              (throw (ex-info (str "Unknown thing-def: " def-id) {:def-id def-id})))]
    (-> (select-keys d [:kind :ticker-type :move-ticks :needs :material :traits :skills])
        (assoc :id (next-id!) :def def-id :pos pos))))

(defn make-pawn [name pos] (-> (make-thing :colonist pos) (assoc :name name :job nil :carrying nil)))
(defn make-item [type pos] (assoc (make-thing type pos) :carried-by nil))
(defn make-tree [pos]      (make-thing :tree pos))
```

- `sim.entity` gains `(:require [sim.defs])`.
- `make-item`'s argument is now the item thing-def id (== material keyword for
  the current 1:1 content); the docstring is updated to say so.
- `:material` is copied from the def onto the item instance. This is still rule-1
  compliant — a *keyword* reference, not an embedded def map — so the existing
  `(:material item)` readers (`sim.think`, terminal `sim.render`, etc.) stay
  untouched.
- `select-keys` is the merge mechanism so `things.edn` stays pure content (lists
  only designer-tunable keys); `make-thing` stamps engine fields and the wrappers
  add runtime scaffolding. Content and mechanism never mix in one map.

### Fail-fast asymmetry (the principle that makes construction-time reads safe)

Use-time lookups (`terrain`, `material`) **degrade gracefully** — a dangling
reference in a *running* world must not crash. But `make-thing` **throws** on an
unknown def-id, because constructing an undefined type is a *programmer error at
the call site*, and a silent fallback would spawn the wrong entity. Same
registry, opposite failure policy, selected by *when* the read happens.

## Load-order & purity

`sim.entity → sim.defs` introduces **no cycle**: `sim.defs` requires only
`clojure.edn`, `clojure.java.io`, `clojure.spec.alpha`. Because every namespace
that requires `sim.entity` transitively loads `sim.defs` first, the top-level
`(load!)` runs before any `make-*` call — in tests, the REPL, and worldgen
alike. This **overturns the deferral reason** recorded in the content/state-split
spec; that note is updated to point here.

Determinism is unaffected: defs are immutable after load, so reading them at
construction is a constant read — exactly like the terrain reads already present
in the pure `tick` and pathfinding.

## Save / back-compat

`:def` is purely **additive**:

- Old saves load fine — their entities simply lack `:def` (nothing reads it yet;
  it is a forward-looking back-reference), and they still carry their baked-in
  `:needs`/`:ticker-type`/`:move-ticks` from construction.
- `schedule/reindex` on load still reads `:ticker-type` off each entity
  (unchanged).
- The never-saved invariant holds: the `:thing` registry lives in `sim.defs`,
  never in the world map; `sim.save` does not touch it.

## Testing (headless, pure — no GL)

- **Load + spec:** `things.edn` loads to the expected `:thing` registry shape;
  a malformed entry (bad `:ticker-type`, non-keyword `:kind`) throws `ex-info`
  with a useful message; every *shipped* entry is `s/valid?`.
- **Construction:** `make-thing :colonist [x y]` yields `:kind :pawn`,
  `:ticker-type :never`, `:move-ticks 15`, the starting `:needs`, and
  `:def :colonist`; `make-thing :wood [x y]` yields `:kind :item`,
  `:ticker-type :long`, `:material :wood`, `:def :wood`.
- **Behavioral equivalence (migration guard):** the existing
  `entity-test/ticker-type-defaults` passes unchanged; a pawn/item/tree built
  post-migration matches the pre-migration content for the keys callers read.
- **Fail-fast:** `make-thing :nonexistent` throws.
- **Never-saved invariant:** a `save!` → `load!` round-trip produces a world with
  no `:thing`/`:defs` key, and an entity with `:def` round-trips intact.

## Out of scope / future (seams left)

- **Content-diversity pass (the planned immediate follow-up).** Once the system
  lands, a separate effort adds a broader RimWorld-flavored roster of thing types
  — more pawn types, item/resource types, plants, and the like — as pure
  `things.edn` data (plus any `materials.edn`/`needs.edn` entries they reference).
  Zero code change is the success criterion: if a new type needs a code touch,
  that touch is its own scoped seam, not part of the content pass. This spec
  delivers the *mechanism*; the diversity is its first payoff.
- **Graphic references on thing-defs + asset-pipeline scaling (its own future
  effort).** Sprite selection is currently *code*, not content:
  `sim.render.sprites` hard-codes `terrain->cell`/`material->cell`/`pawn-cell`
  and a fixed 4-sheet `sheet-files`, and `region` assumes uniform 32px cells
  stretched to `tile-size`. The data-driven endgame mirrors the content/state
  split: a thing-def carries a `:graphic` reference (a cell or a named sprite)
  and the render layers resolve `entity → def → graphic` generically, so a new
  type ships its image as data — the zero-code-change criterion the
  content-diversity pass depends on. Scaling to many images / mixed source
  resolutions is a separate design pass: move from hand-transcribed
  `[sheet col row]` to a libGDX `TextureAtlas` (named regions, arbitrary source
  sizes), and decouple a sprite's native pixel size from its tile footprint via a
  `Graphic` `:draw-size`/`:offset` (RimWorld's `graphicData`/`drawSize`;
  multi-cell objects). This spec deliberately leaves sprite selection in render
  code; `:graphic` is the obvious extension field, not built here.
- **Building thing-defs + build-time "stuff" material choice** (RimWorld's
  per-instance material selection). This step *enables* it; it does not build it.
- **Cross-category ref validation at load** (checking `:material`/`:kind`
  against other categories). Open keywords for now; lookups already degrade.
- **Def inheritance / abstract parents** (RimWorld's `Abstract="True"`). Plain
  entries only.
- **Construction-time content beyond the current seam** (e.g. per-type render
  sprite, stack limits) — added as data when a reader needs it.

## Files touched

- **New:** `resources/defs/things.edn`
- **Modified:**
  - `src/sim/defs.clj` — `:thing` category in `default-sources` + `entry-spec`,
    the thing specs, the `thing` lookup
  - `src/sim/entity.clj` — `make-thing` core, wrappers delegate, require
    `sim.defs`, updated `make-item` docstring
  - `test/sim/defs_test.clj` — thing-def load/spec/lookup coverage
  - `test/sim/entity_test.clj` — `make-thing` + `:def` back-ref coverage
  - `CLAUDE.md` — record the closed seam (content/state-split decision,
    files-to-know)
  - `docs/superpowers/specs/2026-05-25-content-state-split-design.md` — update the
    "Thing-defs … deferred" seam note to point here as resolved
