# Think-Tree + Eat + Wander — Design

*Date: 2026-05-25 · Status: approved-pending-spec-review*

## Context & goal

Idle pawns currently only random-wander (`sim.ai/redeliberate`); every real job
is player- or REPL-assigned. To get a living gameplay loop, pawns need to choose
their own behavior. This is build-plan step 4 (`docs/rimworld-engine-internals.md`
§1.3/§4): model AI as a **data think-tree** (priority nodes walked depth-first,
first valid leaf wins) whose leaves are **JobGivers** that mint a job.

"Both loops via a think-tree" is two specs' worth of work, split along a
dependency line: **eat** is self-contained (food items already exist on the
map), while **haul-to-stockpile** needs a new stockpile-zone concept. **This
spec builds the think-tree spine + eat + wander** (the survival loop);
stockpiles + the haul leaf are the next spec.

## Design decisions (settled in brainstorming)

1. **Wander becomes a `go-to` job** to a random nearby passable cell — not the
   current instant one-tile `random-step`. Every think-tree leaf uniformly mints
   a job; movement is smoother; reuses the existing `:go-to` (no new job type).
2. **Eat fills the food need to 1.0** on consume (def-driven nutrition is a
   future seam).
3. **The hunger threshold is content** in `resources/defs/needs.edn`
   (`:food {:seek-below 0.3}`), read at deliberation time.
4. **The tree is a Clojure `def`** whose nodes reference preds/givers by
   *keyword*, resolved via registries — so it is EDN-ready (moddable later)
   without serializing function objects now.

## Architecture

A new **`sim.think`** namespace: the `default-tree` data, a pure `walk`/`deliberate`
walker, and two registries (`preds`: keyword → `(world pawn) -> bool`; `givers`:
keyword → `(world pawn) -> job-or-nil`). `sim.ai/redeliberate` stops hard-coding
wander and instead walks the tree, routing any resulting job through the one
assignment chokepoint, `sim.job/assign`.

```
sim.think → sim.entity, sim.job, sim.defs, sim.tile, sim.reservation
sim.ai    → sim.think (+ sim.job, sim.entity)
sim.job   → (gains the :eat job type)
```

No cycles: `think → job → reservation → entity`; `ai → think`; nothing depends
back on `sim.ai`/`sim.think`.

## The tree + walker

```clojure
(def default-tree
  {:type :priority
   :children [{:type :conditional :pred ::hungry?
               :child {:type :job-giver :give ::eat}}
              {:type :job-giver :give ::wander}]})

(defn- walk [world pawn node]
  (case (:type node)
    :priority    (some #(walk world pawn %) (:children node)) ; first valid child wins
    :conditional (when ((preds (:pred node)) world pawn)
                   (walk world pawn (:child node)))
    :job-giver   ((givers (:give node)) world pawn)))         ; -> job or nil

(defn deliberate
  ([world pawn] (deliberate world pawn default-tree))
  ([world pawn tree] (walk world pawn tree)))
```

`deliberate` returns the first job a leaf yields, or nil. Adding a behavior =
add a node + register one giver; the walker never changes. This is RimWorld's
`ThinkNode_Priority`, walked depth-first, first valid leaf wins. The keyword
indirection keeps the *structure* inert data while *behavior* lives in code
(RimWorld's XML-references-class-names model) — the same literal can later move
into a `think-tree.edn`.

## The two leaves

- **`::hungry?` pred:** `(< (get-in pawn [:needs :food] 1.0) seek-below)`, where
  `seek-below = (:seek-below (defs/need :food))` (default 0.0 if absent).
- **`::eat` giver:** if hungry, among ground food items (`:material :food`,
  `:pos` set) the pawn **can reserve** (`reservation/can-reserve?`), pick the
  **nearest by Manhattan distance** (cheap; not per-candidate A* — pathing
  happens once, when the job executes), return `(job/eat item-id)`, else nil.
- **`::wander` giver:** return `(job/go-to cell)` for a random passable cell
  within a small radius (e.g. 5) of the pawn, or nil if none (pawn stands still
  that deliberation).

### New `:eat` job

```clojure
(defn eat [food-id]
  {:type :eat :state :pending :priority :normal :source :auto-assigned
   :item-id food-id :phase :go-to-food :path nil :path-index 0})
```

`advance :eat`:
- item gone (despawned/eaten) → `:failed`.
- `:go-to-food` → `walk-toward` the item's pos; arrived → `:consume`; no path →
  `:failed`.
- `:consume` → remove the food entity (`entity/remove-entity`), set the pawn's
  `:food` need to 1.0, log `:job/eat`, mark `:complete`.

## Reservation synergy

`:eat` is **auto-assigned (never forced)** and **reserves its food**, so
`sim.reservation/reserved-targets` extends to `#{:haul :eat} → [item-id]`.
Deliberation runs sequentially per pawn within a tick, so pawn A claims food X
and pawn B's giver sees X claimed and picks Y; `assign`'s reservation gate is
the airtight backstop for auto jobs. Two hungry pawns never fight over one food
item.

`:eat` needs **no execution-time guard** (unlike haul's `:pickup` guard):
because it is auto-only, the assign gate fully prevents double-assignment. The
contrast is the clean statement of step 3's two enforcement points —
*forced-capable* jobs need the execution guard; *auto-only* jobs are covered by
the assign gate.

## `redeliberate` (the integration point)

```clojure
(defn redeliberate [world pawn]
  (if (:job pawn)
    world
    (if-let [job (think/deliberate world pawn)]
      (job/assign world (:id pawn) job)   ; auto (not forced) -> reservation gate applies
      world)))
```

`random-step` is removed from `sim.ai` (the wander giver supersedes it). The
existing rare-band sub-throttling in `sim.simulation/redeliberate-idle-system`
is unchanged — it still decides *when* idle pawns deliberate; this changes *what*
they decide.

## What is explicitly NOT in this spec

- **Starvation consequences** (health/death at food 0) — needs a health/mood
  system. This spec delivers the seek→eat→refill behavior; the punishment for
  neglect is future.
- **Constant/reflex tree** (interrupt a running job for urgent needs) —
  redeliberation fires only when idle, so a wandering pawn finishes its short
  stroll before noticing hunger. Future.
- **`PrioritySorter`** (float-ranked children) — only ordered priority now.

## Testing (headless, pure — no GL)

- **Walker mechanics:** priority returns the first valid child; conditional
  gates on its pred; job-giver yields/nils — via small hand-built trees over
  controlled worlds.
- **`::hungry?`:** true below `seek-below`, false above.
- **`::eat` giver:** hungry + free food nearby → eat job; no food → nil; all
  nearby food already reserved → nil.
- **`:eat` job FSM:** go-to-food → consume refills `:food` to 1.0, removes the
  item, completes, logs `:job/eat`; food vanishes mid-eat → fail.
- **`reserved-targets` :eat** → `[item-id]`.
- **Integration:** a hungry idle pawn gets an eat job via `redeliberate`; two
  hungry pawns + one food → exactly one eats, the other targets elsewhere or
  wanders.
- **No regression:** the existing job/simulation suite stays green
  (wander-as-go-to does not disturb the needs-decay / pathfinding tests).

## Files touched

- **New:** `src/sim/think.clj`, `test/sim/think_test.clj`
- **Modified:** `src/sim/job.clj` (`:eat` constructor + `advance` defmethod),
  `src/sim/ai.clj` (`redeliberate` walks the tree; drop `random-step`),
  `src/sim/reservation.clj` (`reserved-targets` covers `:eat`),
  `resources/defs/needs.edn` (`:food :seek-below`), `test/sim/job_test.clj` +
  `test/sim/reservation_test.clj` (eat cases), `CLAUDE.md` (think-tree decision
  + files-to-know)

## Out of scope / future (seams left)

Def-driven nutrition (`materials.edn :food :nutrition`); the think-tree as an
EDN file; per-pawn / modded trees; the constant/reflex interrupt tree;
`PrioritySorter`; starvation consequences; haul + stockpile zones (the next
spec).
