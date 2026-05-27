# Auto-haul: the gather think-tree leaf

*Design spec, 2026-05-27. Closes Layer 4 (the autonomous survival/work loop) by
giving idle pawns a reason to move loose items into stockpiles.*

## Goal

Pawns should autonomously carry loose ground items into a stockpile zone, with no
player order. This is the `give-haul` JobGiver — the pending piece of step 4 in
`docs/rimworld-engine-internals.md` §4 ("convert `decide`'s cond into a data
priority tree … adding job types stops touching core code"). Everything it needs
already shipped: stockpile zones (`sim.zone`), reservations (`sim.reservation`),
the data think-tree (`sim.think`), and the 4-phase `:haul` job FSM (`sim.job`).

## What this is NOT

- **Not a new job type.** `job/haul` and its `:go-to-item → :pickup →
  :go-to-dest → :drop` FSM already exist and are unchanged.
- **Not a reservation change.** The minted `:haul` job carries `:item-id`, so
  `reservation/reserved-targets` already claims the item and `job/assign`'s AUTO
  gate stops a double-grab. The *destination cell* is deliberately NOT reserved
  (see "Decisions").
- **Not a walker change.** The new leaf self-guards by returning `nil`, exactly
  like `give-eat`/`give-wander`. `sim.think/walk` is untouched.

## The component: `give-haul`

A new job-giver in `sim.think`, signature `(world, pawn) -> job-or-nil`,
registered under `::haul` in the `givers` map. Mirrors `give-eat`.

```
give-haul (world, pawn):
  loose = items where
            :pos is set                              (grounded, not carried)
            AND NOT (zone/cell-zoned? world :pos)     (not already stored)
            AND (reservation/can-reserve? world :id pawn-id)  (not another's claim)
  cells = (zone/stockpile-cells world)
  when (seq loose) AND (seq cells):
    item = nearest loose to PAWN  (Manhattan distance, ties by :id)
    dest = nearest cell to ITEM   (Manhattan distance, ties by [x y])
    (job/haul (:id item) dest)
  else nil
```

- **Item selection mirrors `give-eat`**: nearest candidate to the pawn by
  Manhattan distance, ties broken by `:id` for determinism. Pathing is deferred
  to job execution (no per-candidate A*), so the giver stays cheap.
- **Destination**: the nearest stockpile cell to the chosen item, ties broken by
  the `[x y]` vector for determinism.
- **New dependency**: `sim.think` requires `sim.zone` (which depends only on
  `sim.tile` — no cycle; `think` already requires `entity`/`job`/`reservation`/
  `tile`/`defs`).

## Tree placement

Haul is *work* — it slots between eat (survival) and wander (idle fallback):

```clojure
(def default-tree
  {:type :priority
   :children [{:type  :conditional
               :pred  ::hungry?
               :child {:type :job-giver :give ::eat}}
              {:type :job-giver :give ::haul}    ; NEW — work before idling
              {:type :job-giver :give ::wander}]})
```

No conditional wrapper: `give-haul` returns `nil` when there is nothing to haul
(no loose items, or no stockpile), so the priority node falls through to wander
on its own.

## Decisions

- **Allow stacking; do not reserve the destination cell.** (Chosen 2026-05-27.)
  Two pawns hauling different items may pick the same stockpile cell and their
  sprites overlap. Acceptable for now. The RimWorld-accurate one-item-per-cell
  behavior — extending `reserved-targets` so a `:haul` job also claims its
  `[x y]` destination cell — is a clean follow-up when items need to not-stack.
- **Food is haulable.** Any grounded item is fair game, including
  `:material :food`. This is RimWorld-correct (idle pawns gather food into
  storage) and does not break eating: a hungry pawn out-prioritizes haul in the
  tree and eats the nearest food wherever it physically sits.

## Forward compatibility with the work-priority system

`give-haul` is, in RimWorld terms, the first **WorkGiver** of the `:hauling`
**WorkType**. Its `(world, pawn) -> job-or-nil` shape is exactly a WorkGiver
scanner, so when the priority system lands (WorkType defs + a per-pawn
`{:work-type priority}` matrix + a single `JobGiver_Work`-style `give-work` leaf
that walks registered work-givers in priority order), `give-haul` is **re-homed,
not rewritten**: moved from a top-level tree leaf into the work-giver registry
under `:hauling`. The reservation `can-reserve?` check inside the scan already
matches RimWorld's "reserve during scanning" behavior. The migration is additive
(see the session discussion 2026-05-27); nothing here needs to change for it.

## Why it is self-terminating

An item *on* a stockpile cell is excluded from `loose`, so a hauled item is never
re-selected — no haul-it-back-and-forth loop. And because the item is loose
precisely because it is NOT on a stockpile cell, the destination is always a
different cell than the item's current position — no zero-distance no-op haul.
This is what makes the leaf safe to fire on every rare-band deliberation.

## Edge cases (all fall through to wander)

| Situation | Behavior |
|---|---|
| No stockpile zone exists | `stockpile-cells` empty → `nil` → wander |
| Item already on a stockpile cell | excluded from `loose` → not re-hauled |
| Item reserved by another pawn | `can-reserve?` false → excluded |
| Carried item (`:pos nil`) | excluded by the `:pos` filter |
| Hungry pawn | eat out-prioritizes haul → eats first |

## Testing (headless, `test/sim/think_test.clj` style)

Drive the real `default-tree` over controlled 12×12 worlds, asserting on the
job `deliberate` yields:

1. **haul-giver yields a haul job** — idle pawn + loose item + stockpile → `:haul`
   with the right `:item-id` and a `:destination` that is a stockpile cell.
2. **picks nearest loose item** — two loose items, nearer one chosen.
3. **picks nearest destination cell** — one item, two stockpile cells, nearer
   cell chosen as `:destination`.
4. **no stockpile → wander** — loose item but no zone → `:go-to`.
5. **already-stored item → wander** — the only item sits on a stockpile cell.
6. **reserved item skipped** — another pawn already hauling it → `:go-to`.
7. **priority: hungry still eats** — hungry pawn with loose item + stockpile →
   `:eat` (eat > haul).
8. **priority: fed pawn hauls** — fed pawn with loose item + stockpile → `:haul`
   (haul > wander).

## Files touched

- `src/sim/think.clj` — add `give-haul`, register `::haul`, insert the tree
  node, require `sim.zone`.
- `test/sim/think_test.clj` — add the eight tests above.
- `CLAUDE.md` — update the Layer 3 line ("NOT yet built: auto-haul") and the
  think-tree decision note to reflect the shipped haul leaf.
