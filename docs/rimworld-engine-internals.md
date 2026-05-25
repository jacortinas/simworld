# RimWorld Engine Internals & Revised Plan

*Synthesis of three inputs, May 2026:*
1. *`original-deep-research.md`* — our feasibility study (how to build the machine in Clojure: GC, perf idioms, library survey).
2. *The "sono" beginner's guide* ([Steam guide 2779784000](https://steamcommunity.com/sharedfiles/filedetails/?id=2779784000), current to RimWorld 1.4) — a *player-facing* guide. It describes the engine's **observable outputs** and their numeric tuning, not its internals.
3. *Fresh decompilation research* (this doc) — how RimWorld's actual `Verse` engine produces those behaviors, sourced from the decompiled source, the modding wiki, and Ludeon dev blogs.

---

## 0. The reframe — what each input actually is

- The **original research** answered *"can we build this in Clojure, and how do we keep it fast?"* It is strong on language mechanics (primitive arrays, ZGC, lazy-seq footguns, `.cljc` split) and honest about the ecosystem. It is **thin on RimWorld's actual simulation architecture** — it gestures at "RimWorld uses a region/room pathfinder, replicate it" and "needs→priorities→jobs→toils" without the concrete mechanisms, numbers, or build ordering.
- The **sono guide** is not an engine doc at all. It is the **requirements spec we never wrote**: the observable behaviors a credible clone must reproduce, with real numbers (mood drifts toward a setpoint over ~20 s; walls support roof in a 6-tile radius; deterioration halts under a roof; a map >275² "breaks the AI"). Every one of those is an *emergent output* of an engine system.
- **This doc is the missing middle**: the engine systems that turn (1)'s Clojure substrate into (2)'s observable behavior — and what that means for our plan.

**Headline:** the original research was right about Clojure and wrong about almost nothing — but it *under-specified* three architectural decisions that are far cheaper to make now than to retrofit. Those three are below.

---

## 1. The three things that change how we think

### 1.1 Tiered tick bands + hash-bucket staggering — *the biggest change*

We currently tick the **whole world uniformly** at 30 Hz: `sim.simulation/tick` runs `step-pawns` over **every pawn every tick** (need-decay + `ai/decide` for all of them), and `decay-needs` fires 30×/second per pawn.

RimWorld does **not** do this. `TickManager` holds **three tick lists** keyed by each thing's `tickerType`:

| Band | Cadence | Real time @1× | In-game | What lives here |
|---|---|---|---|---|
| **Normal** | every tick (60 TPS) | 16.6 ms | — | pathing pawns, projectiles, fire, motes, active combat |
| **Rare** | every **250 ticks** | ~4.2 s | ~6 h | needs decay, mood/thoughts, **temperature equalization**, most building comps |
| **Long** | every **2000 ticks** | ~33 s | ~1 day | plant growth, filth, deterioration, slow ecology |

And the killer trick: **hash-bucket staggering**. A `TickList` isn't one flat list — it's `interval` buckets (250 or 2000). Each thing is hashed into a bucket (`abs(hash) % interval`); each tick only the bucket where `TicksGame % interval == bucketIndex` runs. So **~1/250 of all rare-tickers execute on any given tick** — flat amortized cost, no periodic spike. Per-thing "once a second" work on the Normal band uses the same idea by hand: `(TicksGame + thing.HashOffset()) % 60 == 0`.

> Your `ai/moves-this-tick?` (`(clock + pawn-id) mod 15`) **already discovered staggering** — but only for movement, *inside* a uniform full-world tick. The architectural move is to generalize that instinct into tiered bands so most entities aren't visited at 30 Hz at all.

**Why it's load-bearing now:** per-tick cost scales with *active* entities, not total ones. A 200-pawn colony with thousands of plants/filth tiles pays for the ~handful pathing this tick, not the lot. The uniform-tick trap (our current model) bills a plant that grows once a day at 30×/s; it works at toy scale, then falls off a cliff as the map fills — *and* every entity ticks the same frame, so you also get periodic spikes with zero amortization. This is the single highest-leverage decision and it's cheap to adopt while the sim is small.

**Game-time anchors** (useful for tuning need-decay rates): **2,500 ticks = 1 hour; 60,000 = 1 day; 450,000 = 1 quadrum (15 d); 1,800,000 = 1 year (60 d)**. Speed settings don't change what a tick *is* — they run more `tick` calls per real second (1×→60, 2×→180, 3×→360 TPS), with a wall-clock budget so a slow frame degrades gracefully. Pause = multiplier 0; render/input keep running. (We already separate sim-time from real-time — see `CLAUDE.md`; RimWorld confirms the model.)

### 1.2 Regions → Rooms → Reachability are the spatial backbone — and they gate *temperature*, not just pathfinding

Our `CLAUDE.md` open question already files "region graph for cheap reachability + hierarchical pathing" under *"Later."* The research says: **it's not later, it's the backbone**, and it has a hard dependency order:

```
PathGrid (cell costs) → Regions → Rooms → Reachability → smart PathFinder → Temperature
(Roof support is independent — needs only the building/roof grids.)
```

- **Regions** — the map is partitioned into chunks of **max 12×12** (`Region.GridSize = 12`), snapped to a 12-cell grid and subdivided by impassable cells. **Doors are their own 1-cell "Portal" regions.** Regions link into a graph via edge-spans. Tile changes mark **dirty** cells; only regions touching them regenerate (`RegionAndRoomUpdater`) — incremental, never a full rebuild.
- **Rooms** — built *on top of* regions: contiguous region groups bounded by walls/doors. Because doors are Portal regions, the room flood-fill stops at them — that's what "encloses" a room. Building/destroying one wall dirties only local cells, so room detection is incremental too.
- **Reachability** — answers *"can A reach B?"* by **BFS over the region graph**, *not* a full A*, memoized in a `ReachabilityCache` keyed by (start room, dest room, traverse params). This is the performance keystone: a **failed** pathfind would otherwise expand the entire reachable map before giving up. Region BFS rejects impossible jobs in microseconds.

**The contradiction this surfaces:** our original research's "Thermodynamics" section recommends a **per-tile gradient-flux diffusion** (`areduce` over a temperature `double-array`). **RimWorld does not do per-tile diffusion at all.** Temperature is **per-room**: each room holds *one* temperature that drifts toward a target; heat moves between rooms only *through buildings* via `EqualizeTemperaturesThroughBuilding` (a cell-count-weighted average nudge). Walls and doors run this themselves — a door equalizes every **34 ticks open / 375 ticks closed**. That single mechanism explains every temperature behavior in the guide:
- *"Temperature works through walls"* → the slow per-tick leak through each wall.
- *"Double-walling a freezer helps; more than double doesn't"* → two walls ≈ half the leak rate + thermal mass; diminishing returns past that.
- *"Default max temp under a mountain is 15 °C"* → rock rooms equalize toward a fixed deep-rock target.

So **rooms are a prerequisite for temperature**, not an optimization of it. If we build per-tile diffusion first (as the original doc suggests), we build the wrong model *and* one that scales worse. **Build rooms, then per-room equalization.**

**Pathfinding** (confirms + sharpens the original): A* on the cell grid with a `FastPriorityQueue` and a flat calc-grid (exactly the primitive-array advice we already have). Concrete costs worth stealing: move **13 ticks cardinal / 18 diagonal**, blocked-door **+50**, door-to-bash **+300**, outside-allowed-area **+600**, pawn-collision **+175**. Heuristic is octile distance, **weighted** (inadmissible — fast, not strictly shortest) scaling 1.0→2.8 with distance, and it **switches to a region-based heuristic** once opened nodes cross a threshold. Reachability gating first, weighted A* second, region heuristic last.

**Map-size truth:** there's no hard 275² wall. Cost is **quadratic in cells** (250²=62.5k, 400²=160k), per-pawn path cost ~3× higher at 400² vs 250², and needs/heuristics are *tuned* for small maps so pawns abandon faraway jobs — the "AI breaks" is quadratic cost meeting small-map tuning. Lesson: pick a default map size deliberately and treat region/reachability as the thing that buys headroom.

### 1.3 AI is a *data think-tree + job/toil + reservations* — not a RETE rules engine

Our original research's marquee AI recommendation was **O'Doyle (forward-chaining RETE rules) + behavior trees**. RimWorld is **simpler and more direct**, and it's worth reconsidering whether we need a rules engine at all:

- A pawn's brain is a **`ThinkTreeDef` — XML data**, not code. Node types: `ThinkNode_Priority` (try children in order, first valid wins — the "if burning → if drafted → if mental → if work" ladder), `ThinkNode_PrioritySorter` (children report a float, highest first — how work competes with joy/rest), `ThinkNode_Conditional*` (guards), `ThinkNode_Subtree` (splice). The tree is **walked depth-first each time the pawn needs a job**; the first valid leaf wins.
- Leaves are **JobGivers** that mint a **`Job`** (pure data: a def + targets + flags). The general work leaf, `JobGiver_Work`, reads the pawn's **WorkGiver** list *ordered by the player's 1–4 priorities* — that indirection is how player priorities are *data*, not code.
- A `Job` decomposes into a **JobDriver = a sequence of Toils** (atomic steps: `Goto`, `Wait(240)`, `Reserve`, `StartCarry`, `Finalize`), each with a complete-mode and chained **fail conditions** (`FailOnDespawnedNullOrForbidden`, …).
- A **main** think tree (run on job-end) is split from a cheap **constant** tree checked every few ticks mid-job for reflexes (flee explosion, drop burning apparel) that can preempt.
- **Reservations** stop two pawns grabbing the same target: `CanReserve`/`Reserve` keyed on (target, job, layer), checked *during* work-scanning so a contested target is never even handed out.

**How this maps to our code (and what to drop):**
- Our `ai/decide` is a growing `cond`. That `cond` **is** a hand-inlined `ThinkNode_Priority`. The move is to make the tree **data** — `{:type :priority :children [{:type :conditional :pred ::has-job? :child …} {:type :job-giver :fn ::give-haul} …]}` — walked by one pure `(try-issue node world pawn) → result-or-nil`. Adding a job type becomes "add tree data + one giver fn," never editing the walker.
- Our 4-phase haul FSM **is** exactly one JobDriver. Generalize it into a **toil vector** `[{:goto :item} {:pickup :item} {:goto :dest} {:drop}]` with an index cursor — our immutable world makes a toil step just `(advance-toil world pawn) → world'`, and the `yield`-iterator / mutable-tracker machinery RimWorld needs simply doesn't exist for us.
- **Reservations** become a `{:reservations {target {:pawn :job}}}` map in the world: `can-reserve?` is a pure read, `reserve` returns `world'`, release-on-job-end is just dropping a fact. This is *cleaner* than RimWorld's mutable manager — and it's needed the moment two pawns can want the same item (our `CLAUDE.md` "Day 21" item).
- **Do we still want O'Doyle?** Probably **not as the core loop.** RimWorld proves a priority/sorter tree + scanners is sufficient for colony-scale AI without a rules engine. Keep O'Doyle in our back pocket for *cross-cutting deductions that don't fit a per-pawn tree* (global alerts, "is the colony under threat," work-availability indexes) — i.e. the **sensory/alert layer**, not the decision layer. That's a meaningful de-scoping of the original plan.

---

## 2. Reconciliation table — original research vs. RimWorld reality

| Topic | Original research said | RimWorld actually does | Verdict |
|---|---|---|---|
| **Tick loop** | One uniform fixed-timestep `swap! world tick` at 30/60 Hz | Tiered Normal/Rare(250)/Long(2000) lists + hash-bucket staggering | **AUGMENT (biggest change)** — adopt bands early |
| **Temperature** | Per-tile gradient-flux diffusion over a `double-array` | Per-**room** equalization; heat moves through buildings only | **CORRECT** — build rooms first, drop per-tile diffusion |
| **Regions/reachability** | "Replicate RimWorld's region pathfinder" (filed as optimization/"Later") | Load-bearing backbone; gates pathfinding **and** temperature | **ELEVATE** — move ahead of scaling pathfinding/temperature |
| **Pawn AI** | O'Doyle RETE rules + behavior trees | Data think-tree (priority/sorter) + JobGiver/WorkGiver + toils; **no rules engine** | **RECONSIDER** — think-tree-as-data is simpler; O'Doyle → optional alert layer |
| **Job → execution** | Behavior-tree nodes | `Job` (data) → `JobDriver` = **toil sequence** with fail-conditions | **REFINE** — model jobs as toil vectors; our haul FSM is already one |
| **Reservations** | (not addressed) | Per-map reservation manager; `CanReserve` during scanning | **ADD** — needed at 2+ pawns; trivial as a world map |
| **Pathfinding internals** | Primitive arrays, `java.util.PriorityQueue`, HPA* endgame | Confirmed; + concrete costs (13/18 move, door +50, etc.) & weighted+region heuristic | **CONFIRM + add numbers** |
| **Save/load** | nippy the whole immutable world | Scribe deep/reference multi-pass — a workaround for mutable cyclic OOP graphs | **CONFIRM as a strict win** — *if* we obey the rule below |
| **Content/modding** | Data-only EDN mod schema; "everything is a Def" | DefDatabase + XML defs + inheritance + cross-ref resolution + XPath PatchOperations | **SHARPEN** — make the content/state split explicit & early |
| **Map size** | (not addressed) | Quadratic cost; ~275² is soft tuning limit, not a wall | **NEW** — pick default size deliberately |

---

## 3. The two rules that make immutable Clojure a strict upgrade

RimWorld's two hardest engineering problems are **save/load** (the Scribe deep-vs-reference, 4-pass `LoadingVars→ResolvingCrossRefs→PostLoadInit` machinery) and **content moddability** (DefDatabase + cross-ref resolution). Both largely **evaporate** for us — but only if we obey two rules from day one:

1. **Entities reference each other and defs by *id/keyword*, never by embedding.** Store `:target-id 42`, not the pawn map; `:material :steel`, not the steel def. Then the world is an *acyclic* map: nippy writes it directly, no `Scribe_References`, no cross-ref pass, no cycles. "Resolution" is `(get-in world [:entities 42])` at use time. (This also answers our open `:items`-vs-`:entities` question: unify or split as you like, but **reference by id**.)
2. **Separate the immutable content DB (Defs, from EDN, never saved) from the mutable game state (entities, saved via nippy).** Defs = `{:thing-def {:steel {…}} :job-def {…}}` loaded at startup, merged across mods with `merge`/`meta-merge`, inheritance via `(merge parent child)`. State = the world atom. Get this boundary right and you get for free: tiny saves (state only), forward-compatible saves (defs can change as long as ids hold), trivial mod merging, and zero cyclic-graph pain. **Conflate them and you re-create RimWorld's hardest problem with none of its tooling.**

This is the single most important early decision for keeping content data-driven and saves painless.

---

## 4. Revised build plan & dependency order

**Where we are:** Layers 1–2.5 (tiles, pawns w/ needs+skills, A*, `:go-to`/`:haul` jobs, debug log) + libGDX rendering. Uniform 30 Hz tick; `decide` is a cond; content is hard-coded; one world atom (good).

**Recommended ordering** (★ = changed/elevated vs. the original "order of operations"):

1. **★ Tick bands + staggering** — refactor `tick`: classify work into Normal / Rare(250-equiv) / Long. Move `decay-needs` off the per-tick path onto a rare band; keep pathing on Normal. Generalize `moves-this-tick?` into a band/bucket scheduler. *Do this early — it's the cheapest now and the foundation everything else sits on.* (Pick our own intervals; we run 30 Hz, so a "rare" band of ~125 ticks ≈ RimWorld's ~4 s feel.)
2. **★ Content/state split + a tiny Def DB** — even with 2 job types, introduce `sim.defs` (EDN-loaded content) and enforce id-references in state. Cheap now, structural later. Validates with `clojure.spec`.
3. **Reservations** — a `{:reservations …}` world key + pure `can-reserve?`/`reserve`/`release`, checked when assigning jobs. Unblocks "multiple pawns interacting" without double-grabs.
4. **★ Think-tree-as-data** — convert `decide`'s cond into a data priority tree + a walker; convert the haul FSM into a toil vector with fail-conditions. Adding job types stops touching core code.
5. **★ Regions → Rooms → Reachability** — incremental region graph rebuilt on tile change (we already plan step-validation + dirty-on-build in `CLAUDE.md`); rooms on top; reachability cache to reject impossible jobs cheaply. *Must precede scaling pathfinding and temperature.*
6. **Pathfinding hardening** — reachability gate → weighted A* → region heuristic; the primitive-array/`PriorityQueue` work from the original doc, now with concrete cost numbers.
7. **Temperature (per-room)** — only after rooms exist. Per-room setpoint drift + through-building equalization. Then roof support (independent; `RoofMaxSupportDistance ≈ 6.9`).
8. **Storyteller / wealth** — wealth = Σ item+building(½)+pawn value; threat points interpolate from wealth × difficulty × adaptation; a pluggable `(storyteller world dt) → maybe-event` (the original doc's hot-swappable-at-REPL idea stands).

**Load-bearing vs nice-to-have:** Regions/Rooms/Reachability, tick bands, reservations, and the content/state split are **backbone**. The region *heuristic* inside A*, roof support, double-wall thermal nuance, and O'Doyle are **nice-to-have / later**.

---

## 5. The sono guide as a requirements checklist

The guide's value is the **observable spec with numbers**. Each row is an engine system we now understand; "Status" is where our sim stands.

| Observable behavior (from guide) | Engine system | Our status |
|---|---|---|
| Mood drifts toward a setpoint over ~20 s; freezes when unconscious | Need/mood on the **Rare** band; mood-offset thoughts | Needs exist; decay is per-tick (move to rare band) |
| Walls/columns support roof in a **6-tile** radius | Roof-support flood-fill (`≈6.9`) | Not built (post-rooms) |
| Temperature works through walls; double-wall freezer; 15 °C under mountain | **Per-room** equalization through buildings | Not built — **don't build per-tile** |
| Items deteriorate outside; **stops under a roof**; meals spoil faster | Long-band deterioration + roofed check | Items exist; no deterioration |
| Work priorities 1–4, left-to-right tiebreak, blank = never | **WorkGiver** list ordered by player priority | Player-forced jobs exist; no priority list yet |
| Pawns won't path to the same cell; one pawn per task | **Reservation** + destination reservation | Not built — **add (step 3)** |
| Raids scale with **wealth**; statues add wealth | WealthWatcher → storyteller threat points | Not built |
| Map **275²** is the sweet spot; bigger "breaks AI" | Quadratic path cost + small-map-tuned needs | Pick default size deliberately |
| Terrain move multipliers (marsh ~36%, gravel ~87%, paved 100%) | PathGrid cell costs | A* exists; wire per-terrain cost into `pathGrid` |
| Bills run top-down; count / forever / do-until-X | Recipe/bill data on a workbench | Not built (job-system extension) |

---

## 6. One-paragraph answer to "does this change how we think?"

**Yes, in three specific ways, and no in the fundamentals.** The Clojure thesis holds entirely — immutable world + primitive-array hot paths + REPL-driven dev is still the right bet, and our save/load story is *better* than RimWorld's. What changes is the *simulation architecture*: (1) stop ticking the whole world uniformly — adopt tiered, hash-staggered tick bands now, while it's cheap; (2) treat Regions→Rooms→Reachability as the load-bearing spatial backbone that must exist before we scale pathfinding *or* temperature, and build temperature **per-room**, not per-tile (a direct correction to the original doc); (3) model AI as a **data think-tree + toil sequences + reservations** rather than reaching for a RETE rules engine — RimWorld proves the simpler structure scales, which de-risks and de-scopes our AI plan. And underpinning all of it: enforce **"reference by id, content separate from state"** from day one, which is what converts RimWorld's two hardest problems into things Clojure gives us nearly for free.
