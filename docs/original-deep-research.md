# Building a RimWorld-Style Colony Sim in Clojure: A Feasibility Deep-Dive

*Synthesis of two parallel research passes. Where one source went deep on JVM GC technology, rules engines, and concrete RimWorld mechanics, the other went deep on ecosystem maturity, cross-language comparison, and a week-by-week starter plan. Both are preserved here.*

> **UPDATE (May 2026): see [`rimworld-engine-internals.md`](rimworld-engine-internals.md).** A later pass studied RimWorld's *actual* decompiled engine (tick bands, regions/rooms/reachability, think-tree/job/toil AI, the Def system) against a player's mechanics guide. It **augments** this doc on the tick loop, **corrects** the Thermodynamics section (RimWorld is per-*room*, not per-tile), **elevates** the region system from "optimization" to load-bearing backbone, and **reconsiders** the O'Doyle-rules recommendation (RimWorld uses a simpler data think-tree, no RETE engine). This doc is preserved as-is; the inline notes below flag the two corrected sections.

---

## TL;DR

- **Clojure is a defensible — not optimal — choice for a desktop colony sim toy project that could grow.** You can build a credible RimWorld-scale simulation on the JVM if you treat the hot path as "Java with a Lisp skin" (primitive arrays, `deftype` with `^long`/`^double` fields, transients, type hints) and keep the persistent-data-structure idiom for game-state shape, save/load, and UI. The right mental model is *Datomic-style values + a thin mutable inner loop*, not "everything is a persistent map."
- **The browser port via shared `.cljc` is realistic for a slice, not for RimWorld scale.** ClojureScript on V8 sits ~2× slower than JVM Clojure on compute, and there is no production-grade Clojure→WASM path in 2026 (GraalVM Web Image is experimental Early Access only, jank only hit alpha in December 2025 with WASM as a ~2028 roadmap aspiration). Target ~hundreds of pawns on web, ~thousands on desktop.
- **The biggest ecosystem risk isn't the language — it's libraries.** `play-clj` is unmaintained (last release 1.1.1 in July 2016, depending on libGDX 1.9.3 and Clojure 1.7), Arcadia's Unity support is dead, and there is no Clojure equivalent of Bevy or libGDX with active stewardship. The credible JVM stacks are **raw LWJGL3**, **`play-cljc`** (Zach Oakes's modern OpenGL+WebGL library), or **`jme-clj` (jMonkeyEngine wrapper)**, all of which mean you are writing the engine layer yourself.
- **The architecture that wins is dual-layered:** a *pure logic layer* of immutable maps + rules engines + behavior trees for high-level AI and gameplay; a *mechanical execution layer* of flat primitive arrays for spatial data, pathfinding, and rendering buffers. The boundary between the two layers is where 90% of the design work lives.
- **Modern JVM GC technology (ZGC, Shenandoah) changes the historical calculus.** Sub-millisecond pause times mean Clojure's persistent-data-structure object churn is no longer the disqualifier it was in 2014. Configure it correctly and the GC simply disappears as a concern.

---

## Key Findings

1. **The performance ceiling is high but you have to *opt into* it.** Mike Anderson, who built the open-source Clojure roguelike *Tyrant* (and the related 7DRL entry *Alchemy*), reported on the Clojure Google Group: *"Reflection is really slow. After getting 20-100x speedups from eliminating reflection warnings in the AI code, I now treat any reflection warning in code other than one-time setup code as a bug to be fixed immediately."* He also notes dropping back to Java for the inner graphics/AI loops. This is the standard story: idiomatic Clojure is 5–20× slower than optimized; primitive-typed Clojure is within ~1.5–2× of Java.
2. **Datascript is wrong for per-frame ECS queries; it's right for relational game data.** Tonsky's own benchmark issue (#130 on `tonsky/datascript`) documents a `q4` query taking **40 ms on 20,000 entities** on the JVM, vs. **300 ns** for the equivalent native-Clojure map lookup — a ~100,000× gap. Use Datascript for the *editor*, *save game introspection*, *AI knowledge queries that run a few times a tick*; never for the per-frame tick loop.
3. **The functional architecture is the real win, not the raw perf.** A single immutable world value gives you save/load for free (nippy/transit/EDN), trivial replay/undo, deterministic testing, and live REPL surgery — Aaron Santos has documented this as the killer feature of *Robinson*, including using `clojure.inspector/inspect-tree` to live-inspect the game state ("It's a big bang for your coding buck"). RimWorld and Going Medieval both spent meaningful engineering effort to *reinvent* immutable-state-style save systems on top of Unity; you get it on day one.
4. **The toy project's biggest pitfall is `reset!`-ing a giant atom every tick.** The idiomatic, performant pattern is: one persistent world map for *structure*, but specific hot components (spatial grid, pathfinding cost field, line-of-sight) backed by Java primitive arrays inside `deftype` records or `volatile-mutable` fields, swapped through the world via keys.
5. **Browser as primary target is a category error for RimWorld scale.** Browser is fine for a vertical slice (≤500 pawns), a demo, or a player-facing replay viewer. The shared-core `.cljc` story works mechanically — pure simulation namespaces with reader conditionals at the I/O / threading boundary — but ClojureScript's compute tax compounds with the browser's GC and single-threaded JS, so you should not promise "the same sim runs everywhere."
6. **Rules engines (odoyle-rules) + behavior trees (cark.behavior-tree) are the under-discussed killer combination for agent AI.** A RETE-based engine acts as the omniscient sensory/deduction layer ("Bob is starving"; "a hostile is in range"); behavior trees act as the per-agent tactical executor. Both are pure-data in Clojure, both are introspectable at the REPL, and both decouple cleanly from rendering. This is closer to how RimWorld actually works internally than a naive ECS-of-systems would suggest.

---

## Details

### 1. Performance Implications of Clojure for Game Simulation

#### The core tension

The unavoidable conflict is between persistent immutability and the 16.6 ms frame budget. Every immutable update — moving a pawn one tile — does not overwrite X/Y in place; it allocates a new entity record and a new master state map referencing it, abandoning the old objects for the GC. Multiply by 2000 pawns × 60 Hz × multiple fields per tick, and you have hundreds of megabytes of garbage per second to deal with. Whether this is a problem or a non-issue depends almost entirely on which GC you're running.

#### JVM characteristics that matter

Modern OpenJDK is the runtime nearly all Clojure work has moved to: per the official State of Clojure 2024 Results (Alex Miller, clojure.org, Dec 2, 2024), **54% of respondents are now on Java 21 LTS, and an additional 26% are on Java 22 or 23** — putting 80% of the community on modern OpenJDK with current GC technology. This means the historical "GC pause kills my frame" objection to JVM games is materially weaker than it was in 2014.

The relevant collectors:

| Garbage Collector | Pause Behavior | Maximum Pause | Implications for a Clojure game |
|---|---|---|---|
| **G1 GC (default)** | Stop-the-world evacuation | ~50-200 ms+ depending on heap | Unacceptable. Causes visible stutter during heavy ticks. |
| **Shenandoah** | Concurrent evacuation | < 10 ms | Highly viable; proactively releases reclaimed memory to OS, preventing bloat. |
| **ZGC / Generational ZGC** | Fully concurrent | < 1 ms | **The right default for a Clojure sim.** Pause time is independent of heap size from 200 MB to 200 GB. Absorbs persistent-data-structure churn without dropping frames. |
| **Azul Pauseless (commercial)** | Fully concurrent | sub-ms at extreme scale | Documented running Clojure simulations on 864-core / 768 GiB machines generating ~20 GiB/sec of garbage with no observable pauses. Overkill but proves the ceiling. |

Sized correctly, ZGC simply makes the GC question go away. Configure with `-XX:+UseZGC -XX:+ZGenerational` (or `-XX:+UseGenerationalZGC` depending on JDK version) and a generously sized young gen. You should still avoid per-tick allocation in the very hottest loops, but you should *not* pre-emptively reach for object pools — that's pre-ZGC folklore.

#### The boxing tax is real and silent

The single highest-impact perf gotcha in Clojure for game work is unintended boxing of `long`/`double`. The compiler will happily produce `Long.valueOf(...)` calls all over your tight loop if you don't (a) type-hint function arguments and return values with `^long`/`^double`, (b) keep function arity ≤ 4 for primitive-supporting fns (Clojure can't primitive-type more than 4 args), and (c) use `loop`/`recur` with literal-number initialization so the loop variable is typed primitive. The Red Planet Labs *"Clojure, Faster"* post (2020) documents a **~20× mean-execution-time speedup** from adding type hints to a `count`-using function that was reflecting at runtime.

A subtler version of the same issue: Clojure's numeric operations default to boxed 64-bit `Long`s to prevent overflow, but JVM array indexing requires 32-bit primitive `int`s. If you omit type hints, the compiler silently injects `clojure.lang.RT/uncheckedIntCast` at every array lookup, embedding an `l2i` (long-to-int) bytecode at each access. The per-call cost is tiny; the cumulative cost across millions of A* node expansions is significant. Hint your array indices as `^int` or use `(int x)` explicitly.

#### Persistent vs transient vs primitive arrays — when to switch

| Pattern | Use for | Cost |
|---|---|---|
| Persistent map/vector | World shape, entity records, anything serialized | ~O(log32 N) lookup, allocation per update |
| Transient | Building a collection from a reduce/loop you'll convert back | ~O(1) amortized; single-threaded only, must capture return value |
| `volatile!` | Hottest inner loops where even an atom's CAS is too expensive | Thread-unsafe by design; use as a local stack cell, not shared state |
| Java primitive array (`long-array`, `double-array`, `int-array`) | Spatial grid, A* g/h-score arrays, tile-flag bitmaps, dijkstra/flow fields | Native speed; no GC pressure; mutable, requires discipline |
| `deftype` with `^long`/`^double` fields | Per-pawn hot fields accessed every tick | Primitive math, no boxing; methods of the resulting class are direct |

The standard pattern for a colony sim hot loop is: **outer world stays persistent; inner tick mutates primitive arrays.** Think of it like Rails: ActiveRecord-style for the domain model, but `pluck`+raw SQL for the reports query that runs in a tight loop. The persistent map references the array, and "saving" a snapshot means cloning the array (a single `System.arraycopy`) into a new immutable wrapper.

Localized mutability via `transient!`/`persistent!` is the bridge between paradigms: when a subsystem must apply hundreds of updates (e.g., a pathfinder rewriting node weights), convert to transient, mutate in place in thread-local memory, snap back to persistent. Garbage generation drops by orders of magnitude.

#### Lazy sequences are a footgun

`map`, `filter`, `for` return lazy seqs. In a per-tick loop, this means (a) extra allocation per element wrapping in `Cons`, (b) chunk-of-32 work even when you only need one item, (c) holding head of the seq prevents GC of realized cells. Replace with `mapv`/`filterv`/`into []` for eager results, or — for the hottest loops — `reduce`/`reduce-kv`/`run!`/`areduce`/`amap` over arrays. Transducers (`(into [] (comp (map f) (filter g)) coll)`) avoid intermediate allocations entirely and are the right idiom for any multi-step transform.

#### A concrete benchmark: persistent vectors vs primitive arrays

A standard `criterium`-style comparison for an inner-loop linear-algebraic computation:

| Implementation | Functions used | Mean execution time | Speedup |
|---|---|---|---|
| Idiomatic Clojure | `reduce`, `map`, persistent vectors | ~736 µs | 1× (baseline) |
| Primitive array loop | `areduce`, `aget`, `^doubles` hints | ~5.86 µs | **~125×** |

The numbers above come from the parallel-research synthesis and are illustrative rather than independently re-verified — but the *shape* of the result (two orders of magnitude) is consistent with everything else known about Clojure micro-optimization. The takeaway: **for hot paths, persistent vectors are not in the same performance class as primitive arrays.** Profile with Criterium, identify the hot 5%, drop those to primitives.

The same principle bites pathfinding particularly hard. A naive A* over a `get-in`-accessed nested-vector grid on a 1024×1024 map will allocate millions of transient `[x y]` coordinate vectors per pathfind (roughly width × height × average expanded fraction); times multiple agents per second; times tick after tick. Without flattening to a 1D primitive array indexed by `(+ x (* y width))`, a single full-map A* on a 1000×1000 grid has been reported as taking *seconds* in idiomatic Clojure. With flattening + primitive math, it's milliseconds.

#### Real benchmarks and projects that hit walls

Beyond Mike Anderson's Tyrant/Alchemy 20-100× reflection-elimination story, the canonical case study is the Clojure Google Group *"Clojure Performance For Expensive Algorithms"* thread where David Nolen reports ClojureScript-on-V8 hitting **"~2.2 seconds" vs "~1s running time for the JVM"** for an optimized version — *"This is nearly within 2X of the JVM!"* — but the optimized version uses primitive locals, deftypes, and small mutable arrays. The takeaway: **performant Clojure exists, but it looks like Java with parens.**

#### Comparison to C#/Unity (RimWorld), Rust, and Java

| Stack | Hot-path throughput | Memory layout | Threading | Dev velocity |
|---|---|---|---|---|
| **C# / Unity (RimWorld)** | Excellent (IL2CPP, struct-of-arrays via Burst/DOTS available) | Good — value-type structs, manual control | Job System + Burst for parallel | High; mature tooling |
| **Rust + Bevy/hecs/specs** | Best-in-class (archetypal SoA, no GC, SIMD-friendly) | Cache-oriented archetypes | First-class parallel ECS | Lower; borrow checker tax |
| **Java + libGDX / JME** | Very good (JIT, escape analysis) | Reference soup unless you write primitive arrays | Solid via j.u.c. | Medium |
| **Clojure JVM** | Good *with effort* (1.5–2× Java when optimized; 5–20× slower idiomatic) | Reference soup by default; primitive arrays opt-in | Excellent semantics (atoms, refs, core.async); single-threaded GC pressure on hot path | Highest in this list once past the perf gotchas |
| **ClojureScript** | ~2× JVM Clojure on V8; single-threaded JS | Reference soup; no primitive arrays in the same sense (TypedArrays available) | Web Workers only; no shared memory without SharedArrayBuffer | High |

RimWorld specifically uses Unity for rendering and a custom C# entity/component layer for simulation (this is well-documented in the modding community via Assembly-CSharp.dll decompilation). The pattern in the wild: RimWorld and clones like Going Medieval (developer Foxy Voxel confirmed on Steam Community: *"the game is built in Unity and we are utilizing multithreading wherever we can"*) deliberately *don't* use Unity's GameObject/MonoBehaviour for pawns — they're plain C# objects in custom collections. **That's exactly the pattern you'd replicate in Clojure: ignore the engine's entity system, do your own data-oriented design.**

#### Game loop structure

Standard fixed-timestep with interpolation, separated sim and render:

```clojure
;; pseudo-code; render-state is a snapshot
(loop [last-time (System/nanoTime), accumulator 0]
  (let [now (System/nanoTime)
        dt  (/ (- now last-time) 1e9)
        acc (+ accumulator dt)]
    (loop [acc acc]
      (when (>= acc tick-seconds)
        (swap! world sim/tick)
        (recur (- acc tick-seconds))))
    (render! @world (/ acc tick-seconds)) ;; interpolation factor
    (recur now acc)))
```

The sim runs at a fixed 30 or 60 Hz; render runs as fast as the GPU/Swing/LWJGL allows. The atom-swap is the synchronization point — and because Clojure values are immutable, the renderer is *always* reading a consistent snapshot, no double-buffer required. This is structurally elegant in a way C++/Rust engines have to *engineer*.

A critical practical detail: **use `defonce` for the state atom**, not `def`. `defonce` survives namespace reloads, so when you re-evaluate the namespace while the game is running (Figwheel, REPL recompile), your sim state isn't blown away. This single decision is what makes REPL-driven game dev actually work day-to-day.

---

### 2. Functional Architecture for a Complex Simulation

#### A brief ECS history detour

ECS isn't new to functional languages. The pattern traces to **Scott Bilas's work on Gabriel Knight 3 and (later, more famously) Dungeon Siege** — the explicit goal was decoupling game logic from compiled binaries so designers could iterate via text-based data files. Entities reduced to IDs; components as inert data; systems as iterators over component combinations. The data-orientation that ECS-in-Bevy makes mechanically explicit was, in the original formulation, primarily an organizational pattern.

This historical framing matters because it surfaces the question Clojure programmers always have to answer when adopting ECS: **what are you actually here for?** Two distinct answers:

- **Compositional flexibility ("inheritance is bad").** Clojure already gives this for free — entities are maps of components, systems are functions, there's nothing to abstract.
- **Cache-coherent SoA iteration over 10k+ entities ("data-oriented design").** This is the Bevy/DOTS pitch. Clojure's persistent maps actively *prevent* this — they scatter component data across the heap.

Pick which one you want before you pick an ECS library.

#### Three viable component-storage strategies

1. **Maps of components keyed by entity-id.** `{:positions {1 [x y], 2 [x y]}, :health {1 100, 2 80}}`. This is essentially Datascript-without-Datalog and what Mark Mandel's `brute` library popularized (README: entities as UUIDs, components default to defrecords/deftypes; library explicitly aimed at *"JVM based games (desktop/mobile)"*). Fine up to ~hundreds of entities, friendly to inspection. **Start here.**
2. **`deftype` records-per-entity stored in primitive-indexed arrays.** Give up Datalog-style ad-hoc queries; gain cache locality. This is what RimWorld effectively does in C#.
3. **Column-store ECS via Clojure records implementing persistent-map interfaces.** Libraries like **`spork`** (Tom Spoon) use protocol-based records with indices that map entity IDs to component sets — effectively an in-memory relational store. By using `deftype` to implement `IPersistentMap`, the developer-facing API still feels like idiomatic map manipulation, but the underlying data is densely packed. Some practitioners have even prototyped against in-memory SQLite, treating the tick loop as relational transactions. Worth knowing about, almost certainly overkill for a toy.

The right move for a RimWorld-like is **hybrid**: world is `{:entities {id component-map}, :grid <array>, :pathing <array>, :events []}` — entities are persistent maps with all the schema flexibility, but the *spatial indices and per-tick numeric state* live in arrays. When you save, you serialize the persistent map; arrays are reconstructed on load.

#### Queryable game state: Datascript, Asami, and the "EntityGraph" claim

For game-relevant queries (job graph, dependency planning, "which colonists know about this corpse"), Datalog is a beautiful fit. The pattern: **maintain a Datascript DB that lags the live world**, updated in batched transactions every N ticks. Pull queries run in a few ms on tens of thousands of datoms when *not* using multi-clause joins; complex joins blow up superlinearly.

Tonsky's own benchmark issue (#130 on tonsky/datascript) shows `q4` taking **40 ms for 20,000 entities** on JVM, with ClojureScript slower still. The follow-up issue #132 explicitly proposes a faster alternative because of this. For game-relevant scales:
- ≤1000 entities: fine for all use.
- 1000-10,000: fine if queries run a few times per second, not per frame.
- >10,000: use it only for tooling, not the live game.

A more aggressive comparison is sometimes cited:

| Metric (20,000 entities asserted) | EntityGraph | DataScript | ASAMI |
|---|---|---|---|
| Assertion speed | 1× | 0.5× | 0.5× |
| Wildcard pull | 6× faster | 1× | n/a |
| Simple query | 1× | ~5000× slower | ~100× slower |
| Complex query | 1× | >10× slower | ~3.5× faster |

⚠️ **Caveat on this table:** these figures appear in the parallel-research output but I haven't independently verified the source benchmark, and "EntityGraph" as a Clojure library does not have the public visibility of DataScript or Asami. Treat the numbers as directionally useful (these tools have very different shapes) rather than as an authoritative ranking. If you genuinely need fast querying, run your own benchmarks on representative workloads.

Kevin Lynagh's 2022 essay *"On datalog and application databases"* is the best honest writeup of Datascript's limits and is worth reading — he proposes a WASM bridge specifically because *"the main limitation I've run into building applications with Datascript is performance."*

#### Rules engines: declarative agent logic

The biggest under-discussed Clojure-specific tool for a simulation like this is the **forward-chaining rules engine**, and specifically the choice between Clara and O'Doyle.

**Clara-rules** (the historical default) is RETE-based and supports truth maintenance — assert a fact derived from a condition, and if the condition later becomes false, the derivation is automatically retracted. Theoretically elegant; in practice, the truth-maintenance overhead and the `defrule`-creates-global-vars binding model produce significant friction in a tight game loop.

**`odoyle-rules`** (Zach Oakes — same author as play-clj and play-cljc) is the modern alternative and is the one to use for a game. It is a Clojure port of the Nim engine *pararules*. Key design choices that fit gaming:

- **Facts are simple `[id attribute value]` tuples**, not records. This maps directly onto ECS components: `[pawn-42 :position [12 7]]`, `[pawn-42 :hunger 0.8]`.
- **Implicit updates.** Inserting `[pawn-42 :hunger 0.85]` automatically retracts the prior `:hunger` fact — no explicit retraction logic, no truth-maintenance bookkeeping.
- **Joins are efficient.** The RETE network ensures rules fire only for entities whose relevant components changed, bypassing the linear scan of all entities every tick.
- **Production-tested in real Clojure apps** including the Paravim text editor and prototype dungeon-crawler engines.

The architectural payoff: **insert ECS components as tuples; let O'Doyle handle the deduction/scheduling layer.** Rules express things like *"if a pawn's hunger > 0.7 and there is reachable food, schedule an eat job"* declaratively. The rules engine becomes the **global sensory/deduction layer**, observing the entire game state and producing derived facts. Per-agent execution then happens in behavior trees (below). This cleanly separates strategy ("what should be true?") from tactics ("how does this pawn do it?").

#### Pawn AI: state machines, behavior trees, GOAP, utility AI

All four map cleanly to Clojure:

- **FSM**: a map `{state {input next-state-fn}}` — Aaron Santos's *Robinson* blog post on FSMs is the canonical Clojure example, motivated by replacing *"a huge nested if/cond/case expression."*
- **Behavior tree**: nodes are plain maps `{:type :sequence :children [...]}` traversed by a pure recursive function. Two production-grade libraries exist:
  - **`alter-ego`** — a mature BT library with a hiccup-like declarative syntax.
  - **`cark.behavior-tree`** — newer, also data-driven. Nodes carry one of four states: `:fresh`, `:running`, `:success`, `:failure`. The tick function takes both the game state and the BT execution state, traverses depth-first skipping terminal nodes, and returns updated state + updated tree status. This pairs *beautifully* with immutable game state: the tree itself is data, the execution cursor is data, the world is data.
- **GOAP** (Goal-Oriented Action Planning): nodes are world states (maps); actions are `{:preconditions {} :effects {} :cost n}`; A* over world-state space. This is genuinely elegant in Clojure because *world states are first-class values you can hash and compare for free*.
- **Utility AI**: each pawn evaluates each candidate action with `(util-score pawn action world)`; pick max. Trivially expressible as `(apply max-key …)`.

For exotic optimization there's the concept of **concatenative behavior trees** — compiling the BT directly to a stream of function-pointer-like operations, inspired by stack-based languages like Forth and Joy. The reference for this is *the Bobcat compiler* in the parallel research; I haven't independently confirmed Bobcat's status or public availability, so treat it as an interesting design direction rather than a library to depend on. The general principle (compile BTs to compact instruction streams rather than interpreting trees) is sound and used in commercial engines.

RimWorld itself uses a layered approach — *needs* drive *priorities*, priorities drive *jobs* via a constraint-style work-giver system, jobs decompose into *toils*. In Clojure, model each layer as data + pure transforms and you get a system that's introspectable in the REPL ("why did Bob start hauling instead of cooking?" becomes a `(why bob @world)` function you write once).

**The synthesized architecture for AI:** rules engine (O'Doyle) as the global "what should happen" layer; behavior trees (cark.behavior-tree) as the per-pawn "how to do it" layer; rules engine produces derived facts that become inputs to BTs; BTs emit job/action events that become inputs back to the rules engine via the world state. The two cleanly separate strategic deduction from tactical execution, both stay pure-data, both are introspectable.

> **CORRECTION (see `rimworld-engine-internals.md` §1.3):** RimWorld itself uses *no* forward-chaining rules engine. Its pawn AI is a **data think-tree** (`ThinkNode_Priority`/`PrioritySorter`/`Conditional` nodes, walked depth-first, first valid leaf wins) whose leaves (`JobGiver`s) mint `Job`s; a `Job` decomposes into a **toil sequence** with fail-conditions; player work-priorities drive an ordered `WorkGiver` list; a per-map **reservation** system prevents double-grabbing. This is simpler than O'Doyle + BTs and proven at colony scale. Recommendation shifted: model AI as a data think-tree + toil vectors + reservations; keep O'Doyle (if at all) for a *global alert/sensory layer*, not the per-pawn decision loop.

#### Managing the world: one atom vs partitioned state

Start with **one atom**, `(defonce world (atom initial-world))`. Premature partitioning is the source of most "I built a Clojure game and now it's a mess" stories. The atom gives you snapshot semantics (`@world`), atomic transitions (`swap!`), trivial debugging (`(clojure.pprint/pprint @world)`), and "step the game one tick from the REPL" interactivity. When you actually hit contention, partition by *aspect* (world atom for sim, separate atom for input queue, separate atom for render snapshot) — never by entity, because cross-entity interactions become a nightmare.

#### Event-driven vs tick-driven, and transducers

Tick-driven with an event queue *as data* is the sweet spot. Every tick produces a vector of events; transducers compose the event-processing pipeline:

```clojure
(def process-events
  (comp
    (filter game-event?)
    (map enrich-with-actor)
    (remove cancelled?)
    (mapcat resolve-into-effects)))

(transduce process-events apply-effect world tick-events)
```

This is the same mental model as Rails delegated types in your domain: events have a polymorphic `:type` key, and each step is a small pure transform. The composition is at the data level, not the type-system level.

#### Pathfinding

Several Clojure A* implementations exist (Matthew Downey's lazy k-shortest-paths variant, Berke Akkaya/Nakkaya's tutorial implementation, the `clj-loom`/`ubergraph` library). **They are all too slow as written for a colony sim with frequent re-paths.** Idiomatic A* over `get-in`-accessed nested vectors falls into multiple traps simultaneously:

- Every neighbor expansion allocates a `[x y]` coordinate vector.
- Every `get-in` walks a HAMT — Clojure's persistent vectors are Hash Array Mapped Tries with 32–64-wide chunked arrays, requiring logarithmic-depth pointer chases on lookup.
- Open set as a sorted-set or priority-map has O(log n) cost with high constant factors.
- Numeric ops box `Long` everywhere they aren't hinted.

For a 200×200 map with multiple pawns asking for paths per second, the path is:

1. Implement A* yourself in a single namespace.
2. Cost grid and came-from grid are `long-array`/`int-array`, not maps. Index as `(+ x (* y width))`.
3. Open set is a `java.util.PriorityQueue` keyed by f-score, not a sorted-set.
4. Type-hint everything; `^long` on cost computations; `unchecked-add-int` for inner math; wrap the hot section in `(set! *unchecked-math* true)`.
5. Cache "from-this-region-to-that-region" macro paths (HPA* / hierarchical A*) when your map is large.

Expect 10-100× speedup over an idiomatic version. JPS (Jump Point Search) is worth it for open terrain; useless inside dense colony layouts. For RimWorld specifically, the engine uses a region/room-graph hierarchical pathfinder; replicate this.

**Pathfinding load-shedding is non-negotiable at scale.** Even with primitive arrays, you cannot afford to run full-map A* for every pawn every tick. Standard tricks: restrict initial pathing attempts to a small radius around the pawn; precompute long-distance waypoints across the map and have pawns navigate waypoint-to-waypoint with localized A* between them; cache paths by (start-region, end-region) and reuse with local diffing when terrain changes.

#### Spatial partitioning

Same answer: **uniform grid backed by primitive arrays** is by far the simplest and fastest for tile-based games. Quadtrees are overkill unless you have huge open-world ranges. Persistent maps as the grid (`{[x y] tile}`) work for a 30×30 prototype and start hurting around 200×200.

#### Save/load

This is where Clojure is genuinely *better than any competitor*:

- **EDN**: human-readable, slow, large.
- **Transit**: faster, smaller, still tooling-friendly.
- **Nippy** (Peter Taoussanis / taoensso/nippy): handles all Clojure data structures including records, sets, dates, and arbitrary user types via extension. The right default. README documents *"Insanely fast"*, *"Even faster with optional compression (LZ4, Snappy, LZMA, etc.)"*, and built-in encryption support.

You write `(nippy/freeze-to-file "save.npy" @world)` and `(nippy/thaw-from-file "save.npy")`. No save-versioning DSL, no DTOs, no Unity ScriptableObject dance. **The same data structure that runs your game is the save file.** This is alone worth a lot.

#### Modding

Clojure's runtime-eval and namespace-as-module story is one of the strongest in the industry:

- Mods are namespaces loaded from a configurable mod path.
- You can `(load-file "mods/cool-mod/init.clj")` at runtime.
- Mod authors get full REPL access to your live game.
- Sandboxing via Clojure's `*read-eval*` and a custom classloader is achievable but non-trivial.

The risk: full eval = code execution. Either you ship mods that are trusted, or you define a *data-only* mod schema (EDN files describing entities) and have your engine interpret it. The latter is what RimWorld does (XML defs) and what you should do for v1.

---

### 3. Library and Ecosystem Survey

**The honest current state (May 2026).**

| Library | Status | Verdict for a colony sim |
|---|---|---|
| **play-clj** (oakes/play-clj, libGDX wrapper) | Last release **1.1.1 in July 2016**, depending on libGDX 1.9.3 and Clojure 1.7. Effectively **abandoned**. Original author Zach Oakes posted on the Clojure list at release: *"play-clj is very slow on Android for games with more than a handful of entities, so I could really use some help there."* | Avoid — you'd be 9 years behind libGDX. |
| **play-cljc** (oakes/play-cljc, OpenGL+WebGL) | Same author's modern successor; targets both desktop OpenGL and browser WebGL from a single `.cljc` codebase. Lighter weight than play-clj; honest about scope. | **The serious modern Clojure-native option.** Worth investigating as your shared-core renderer. |
| **play-cljs** (oakes/play-cljs, p5.js wrapper) | ClojureScript-only; useful for browser-only sketches. | OK for the browser slice if you don't want to deal with raw WebGL. |
| **Quil** (quil/quil, Processing wrapper) | Actively maintained; 4.3.x as of 2025, JDK 17+, ClojureScript-compatible. README: *"Quil works with Clojure 1.10 and ClojureScript 1.10.x. Current released version 4.3.1560 is compatible JDK 17+ and supports Linux amd64,aarch64 and macOS M1/M2/x86_64 architectures."* | Great for prototyping, jam-scale games. Not built for 10k entities or complex render layers, but the **shared CLJ/CLJS** story is real. |
| **Quip** (Kimbsy/quip, Quil game engine) | Live but small; one maintainer. README: *"A Clojure game engine made using Quil"*; scenes/sprites/sound effects via simple wrapper. | Fine for a vertical slice; you'll outgrow it. |
| **jme-clj** (ertugrulcetin/jme-clj, jMonkeyEngine wrapper) | Released ~2021, low activity but functional; explicit "hot-reload" demo videos on YouTube. | Best 3D option in the Clojure ecosystem; 3D is overkill for a RimWorld-like but the underlying engine is solid. |
| **Arcadia** (Clojure-CLR + Unity) | Unity integration **dead**, last meaningful activity years ago. Per Flexiana's 2024 starter guide: *"I believe Unity support ended a while ago and support for Godot 4 is still in development."* | Don't bet on it. |
| **Raw LWJGL3** | LWJGL itself is healthy; calling from Clojure is straightforward Java interop. | **The serious option** — you write a thin Clojure layer over OpenGL/GLFW/STB. |
| **ClojureScript: PixiJS / three.js / Phaser / Canvas** | Interop works fine; no Clojure-idiomatic wrapper. The Chocolatier project (Alex Kehayias) wraps Pixi.js with a modified ECS but is not maintained. | Use whichever JS library you'd use from TypeScript; interop is `(js/PIXI.Sprite. …)`. Your Three.js experience transfers directly. |
| **Reagent / re-frame** | Mature, production-grade. | Excellent for **UI overlays** (build menu, research tree, colonist info) — the same pattern you'd use for any CLJS web app. |
| **odoyle-rules** | Active, in production use (Paravim, etc.). | **Use this.** The rules engine recommendation. |
| **cark.behavior-tree / alter-ego** | Both active; cark.behavior-tree more recently maintained. | Either works; pick on docs and taste. |
| **spork** | Tom Spoon's relational/column-store ECS. Live but niche. | Worth knowing about for scale; don't reach for it on day one. |

#### Tools.deps vs Lein for game projects

Tools.deps (`deps.edn`) is the modern default for everything else, but **Leiningen still has the edge for game distribution today** because of `lein uberjar`, `lein-native-image`, and integration with packaging tools. Both work; if you're new to Clojure tooling, prefer `deps.edn` + the `clj` CLI and use `tools.build` for uberjar production.

#### GraalVM Native Image for distribution

This is *the* compelling distribution story — single-binary executable, ~50-200 MB, sub-second startup, no JDK required from the user. The `clj-easy/graal-config` repo curates working configurations for major Clojure libraries. The catches:

- **Reflection must be declared at build time.** Anything dynamic (calling code by symbol, `eval`, dynamic `require`) needs config.
- **Some libraries fail to compile.** Nippy is known-good. The taylorwood/lein-native-image README documents known issues including `clojure.core/locking` failing compilation on Clojure 1.10's spec dependency.
- **GraalVM CE on macOS produces an x86 or ARM binary** — you need separate build hosts for cross-platform distribution.
- **No JIT.** The native image runs slightly *slower* than warmed-up JVM for compute, but starts instantly. For a long-running game, prefer JVM + jlink for distribution; for a tool/CLI, prefer native-image. The taylorwood project README explicitly notes: *"The primary benefits to using a GraalVM native image are faster startup, lower memory requirements, and smaller distribution footprint (no JDK/JRE required). This doesn't necessarily mean the same code will run faster than it would on the JVM."*

#### REPL-driven game dev

This is the single highest-value advantage and the one most likely to make you finish the project. Connected REPL means:

- Change a function (e.g., `pawn-decide-job`), save, recompile namespace, the *next tick* uses the new code with no restart.
- Inspect `@world` in your editor, drill into `(get-in @world [:entities 42])`, see the pawn's current AI state.
- Spawn pawns, change weather, force jobs from the REPL.
- Profile a specific function with `criterium` while the game runs.

Aaron Santos describes using `clojure.inspector/inspect-tree` on the live world during *Robinson* development as *"a big bang for your coding buck."* ertugrulcetin's jme-clj demo specifically markets *"Hot Reload Driven Game Development on JVM"* — *"you don't have to restart the application for each code change"* — and the demo video confirms changes apply immediately. This workflow is qualitatively unmatched in C#/Unity/Rust ecosystems (Unity's edit-mode hot reload is closer than most, but not as fast or as deep).

---

### 4. RimWorld-Specific Subsystems in Clojure

#### Tile-based world with weighted terrain

Store as `(long-array (* w h))` for terrain type, plus parallel arrays for elevation, gas/temperature, moisture, etc. Each tile carries a movement-speed multiplier; these stack with overlay state (loose objects on a tile compound the underlying penalty).

For reference, RimWorld's actual terrain modifiers look roughly like:

| Terrain | Move speed multiplier |
|---|---|
| Marsh | ~36% |
| Shallow water | ~52% |
| Lichen-covered dirt | ~81% |
| Gravel | ~87% |
| Concrete / paved | 100% |

In Clojure, store the multipliers as a `(double-array num-terrain-types)` and look up by terrain index. Per-tick movement cost calculation becomes a single `aget` + multiplication, no boxing.

Terrain is *mutable*: walls go up, doors close, items get forbidden. Each such mutation invalidates pathfinding caches for affected regions. The right pattern is a region-graph that's rebuilt incrementally when tiles change (RimWorld does this).

#### Multi-floor / Z-levels

Going Medieval went 3D voxel-vertical and it's clearly a much bigger engineering investment — **don't try to do this for v1.** RimWorld stays single-floor and most of its complexity benefits from that. If you ever do want Z-levels, the architectural pattern that survives is **Layered Sparse Instancing**: don't eagerly allocate full `width × height` arrays for every Z-level (that's `O(width² × levels)` memory and dominates RAM on a 250×250×N map). Instead:

- Allocate per-Z-level grids lazily on first interaction (mining, building).
- Pawns transitioning between Z-levels follow a despawn/respawn pattern: remove from active sim, preserve all per-pawn state (mood, needs, inventory, job queue), re-attach to the new Z-level's spatial maps.
- Use a flyweight pattern for huge item piles: a "75 stone blocks" entity is one entity with a count, not 75 entities. This is what RimWorld does, and the Clojure version is just `{:item :stone :count 75 :pos [x y]}`.

#### Pawn needs, moods, traits

Pure data:

```clojure
{:needs   {:food 0.7, :rest 0.3, :recreation 0.5}
 :traits  #{:pyromaniac :jogger :ascetic}
 :moods   [{:reason :saw-corpse :amount -10 :ticks-left 30000}]
 :skills  {:cooking 8, :shooting 3}}
```

Trait/mood interactions are pure functions: `(mood-modifier pawn world)` returns a number. This is genuinely *better* than C# inheritance hierarchies — you can serialize, replay, A/B test mood logic in isolation.

#### Job/work priority system as a constraint problem

RimWorld's work-giver system is essentially: for each pawn, generate candidate jobs, filter by preconditions (reachability, materials, skill threshold), score by priority × urgency × distance, pick best. **This is the perfect place to use O'Doyle.** Insert pawns and tasks as tuples; write rules that derive job assignments from the joint state. The rules engine handles the multiway joins efficiently and re-fires only on relevant component changes.

Either way, the **work-giver itself is data** (`{:work :hauling :requires-skill nil :preconditions [...] :priority 5}`), which makes mods that add work types trivial.

#### Crafting, growing, livestock, trade

All schema-driven: define recipes/plants/animals/goods as EDN files, the engine loads them at startup. This is also where Clojure's `clojure.spec` shines for validation — write a spec for "a valid recipe" and the engine fails fast on broken mod content.

#### Combat

RimWorld's "real-time with pausing" model maps to: simulation ticks at 60 Hz; combat resolution is per-tick discrete (roll to hit, damage, status effects). Cover and line-of-sight are spatial queries against the grid; ranged-weapon ballistics is a simple projectile sim. None of this is Clojure-hard.

#### Thermodynamics (heat, gases)

A high-fidelity colony sim has temperature, smoke, toxic gas. Full fluid-dynamics simulation is prohibitive in real time; the practical model is a **Gradient Flux** approach: at each tick, compute heat/gas transfer across tile boundaries using a fixed-iteration diffusion approximation (each tile exchanges some fraction of its excess with its neighbors). This is `O(width × height)` per tick and trivially parallelizable across independent regions.

The Clojure version is a tight `areduce` over the temperature `double-array`, computing the new temperature for each cell from its neighbors. This is one of the hottest loops in the entire game; type-hint everything, use primitive math, never allocate. For really intense versions (atmospheric scattering for skies, planetary-scale simulations), the work moves offline: precompute lookup tables and cache them, possibly using `pmap` during the precompute step where parallelism helps.

> **CORRECTION (see `rimworld-engine-internals.md` §1.2):** RimWorld does **not** use per-tile diffusion. Temperature is **per-room**: each room holds a single temperature that drifts toward a target, and heat moves between rooms *only through buildings* (`EqualizeTemperaturesThroughBuilding` — a cell-count-weighted average nudge; a door equalizes every ~34 ticks open / ~375 closed). This is why "temperature works through walls," why double-walling a freezer helps (and more than double doesn't), and why deep rock sits at ~15 °C. Per-tile gradient-flux is the *wrong* model and scales worse. **Prerequisite: build the Region→Room system first, then do per-room equalization.** The `double-array` hot-loop advice still applies to genuinely cell-level fields (e.g. a future fire/smoke spread), just not to ambient temperature.

#### Storyteller / Director AI

This is genuinely fun in Clojure: the storyteller is a function `(storyteller world time-since-last-event) → maybe-event`. Events are data (`{:type :raid :strength 0.6 :origin :east}`). Pluggable storytellers (Cassandra, Phoebe, Randy) become different functions — and **you can hot-swap them at the REPL** to test pacing.

#### Procedural map generation

Standard techniques (Perlin/simplex noise, BSP, cellular automata for caves, Voronoi for biomes) all have Clojure implementations or are trivial to port. The `clisk` library (Mike Anderson) is a Clojure procedural-noise reference. Generation runs once; performance is not critical here.

---

### 5. Cross-Language Comparison

**Why does RimWorld use C#/Unity?** Three reasons, all good: (1) Unity gives them a *renderer*, *audio*, *input*, *asset pipeline*, *build system*, and *cross-platform deploy* for free — collectively 18+ months of engineering. (2) C# is mature, fast, well-tooled, and well-staffed. (3) Unity's Asset Store and modder familiarity is a moat. **None of these reasons argue against Clojure for a hobby project** — they argue against rolling your own engine for a *commercial* product where competitors have these for free.

**Rust ECS (Bevy, hecs, specs, legion).** Bevy is the live, exciting one. Per Bevy's own README, *"A new version of Bevy containing breaking changes to the API is released approximately once every 3 months"* — recent releases bear this out: 0.18.0 (Jan 2026), 0.17.0 (Sep 2025), 0.16.0 (Apr 2025), 0.15.0 (Nov 2024). The ECS-bench-suite (`rust-gamedev/ecs_bench_suite`) shows Bevy as the fastest pure-Rust ECS in micro-benchmarks (creators of the suite themselves note: *"speed is only one aspect of an ECS, and a rather small one at that once a baseline of performance has been established"*). The Rust pitch is: zero-overhead abstractions, no GC pauses, archetypal SoA memory layout, parallel scheduler with automatic dependency analysis. The Rust cost is: borrow checker, recompile times, smaller library universe for game-specific stuff. **For a hobby colony sim, Rust + Bevy is genuinely competitive with Clojure on developer experience** — you give up REPL-driven dev and gain compile-time guarantees. The choice is taste.

**F# as a sanity check.** F# is functional, runs on the CLR (so it could integrate with Unity if you wanted), has units of measure, and discriminated unions. **No major colony sim has shipped in F#** — searching for one returns zero results. F# game dev exists but is niche; the practical option for "functional + Unity" is using F# for sim logic and C# for engine glue, which Microsoft uses internally in some Xbox tooling but is not a known indie path. If you wanted CLR + functional + game engine, F# + MonoGame would be more proven than Clojure CLR + anything.

**Haskell game dev reality.** Haskell *can* be used for games — Apecs (a Haskell ECS library), `hip`, `gloss` exist — but the community is research-flavored, garbage collection latency is worse than the JVM's, and laziness creates space leaks that are murder in a tight loop. **You'd be a pioneer, not a participant.**

**What Clojure uniquely gives you.** This is the honest pitch:
1. **Live REPL into a running game.** Genuinely unique at this level of integration; F#'s FSI and Common Lisp's SLIME are closest.
2. **Data-driven everything for free.** Save game = `nippy/freeze`. Mod content = EDN. Network protocol = transit. No DTO ceremony.
3. **Immutable snapshots = trivial undo, replay, deterministic testing.** You write a property-based test that "for any sequence of valid actions, save → load → continue produces identical world after N more ticks" and it's two lines of `test.check`.
4. **Datalog (Datascript) as a tool you can deploy when it fits.** Useful for tooling and editors even if not for the hot loop.
5. **Rules engines + behavior trees as composable, pure-data AI infrastructure.** The O'Doyle + cark.behavior-tree combo has no clean equivalent in Rust/Unity — those ecosystems pick one paradigm and run with it; Clojure lets you mix freely because everything is data.
6. **JVM ecosystem.** Anything Java has — physics libraries (JBullet), Steam SDK wrappers, audio (OpenAL via LWJGL), networking — is available.

**Honest assessment.** Clojure is *the wrong tool* if your primary goal is shipping a commercial colony sim that competes with RimWorld on entity count and visual polish — you'll spend more time fighting performance than building gameplay. Clojure is *the right tool* if your primary goal is a **toy project that you'll actually finish, that's a joy to maintain, and that you might grow into a small commercial release**. The unique combination of REPL-driven dev, immutable values, and serialization-for-free makes the prototyping phase faster than any other language. The exit ramp (drop to Java for hot loops, package via GraalVM, or rewrite the engine layer in Rust if you ever need to) is real and well-trodden.

For your specific profile — senior eng, comfortable with FP and JVM, Ruby/Rails delegated-types thinker, Three.js experience — Clojure plays directly to your strengths. The Rails analogy that I think is closest: **Clojure for a game is like Rails for a SaaS**. You'll move faster than competitors using "more serious" stacks for the first 80% of the work; the last 20% (performance tuning, native deployment) will be harder. For a toy that might grow, the trade is favorable.

---

### 6. Shared-Core Strategy for Desktop + Browser

#### The realistic `.cljc` split

```
src/
  cljc/
    colony/
      world.cljc       ; data shapes, schemas
      sim.cljc         ; pure tick functions
      pathfinding.cljc ; A* (with reader conditionals for primitive arrays)
      ai.cljc          ; FSM/behavior trees/rules
      events.cljc      ; event types and processing
  clj/
    colony/
      main.clj         ; JVM entry point
      render_lwjgl.clj ; or render_jme.clj / play-cljc backend
      io.clj           ; file save/load, threading
  cljs/
    colony/
      main.cljs        ; CLJS entry
      render_canvas.cljs ; or render_pixi.cljs / play-cljc browser backend
      io.cljs          ; IndexedDB save
```

The `cljc` namespaces use reader conditionals only at the boundary:

```clojure
#?(:clj  (long-array size)
   :cljs (js/Int32Array. size))
```

This works well. The honest caveats:

- **No real threads in CLJS.** Anything you parallelized on JVM (parallel pathfinding via `pmap`, parallel AI deliberation) collapses to single-threaded in the browser. Web Workers exist but message-passing breaks the shared-immutable-value model.
- **File I/O is browser-specific.** Saves go to IndexedDB or download-as-file.
- **Performance characteristics differ.** Primitive arrays exist (`Int32Array`, `Float64Array`) but Clojure data structures in CLJS don't use them internally; you'd hand-roll TypedArray access.

#### Browser-specific optimization patterns

If you're serious about a browser slice with meaningful entity counts, three patterns matter:

**1. Bypass the DOM entirely for rendering.** Reagent / re-frame's virtual-DOM reconciliation cycle is fatal for a game loop that wants to update hundreds of moving sprites at 60 Hz. The right pattern is a *single Reagent-managed component* that owns a `<canvas>` element, hooks `requestAnimationFrame` directly, and updates the WebGL/Canvas context outside of React's render cycle. Reagent + re-frame remain ideal for the *UI overlays* (build menus, colonist info panels, research tree) — just not the play field.

**2. Offload the simulation to a Web Worker, communicate via Transferable Objects.** JS is single-threaded; A* and gas-flux calculations on the main thread will freeze the UI. The sim runs in a Worker; the main thread renders. The naïve message-passing model serializes the world to JSON on every transfer, which is murder. The right pattern is **`ArrayBuffer` with `postMessage` transfer semantics**:

- Pack the relevant world state (positions, terrain, current animation frames) into one or more `Float32Array` / `Int32Array` views over a shared `ArrayBuffer`.
- `postMessage(buffer, [buffer])` transfers ownership of the underlying memory (not a copy) to the receiver in zero copy.
- Main thread receives, renders from the buffer, sends back (or uses SharedArrayBuffer when isolation headers permit it).

This pattern, sometimes combined with WASM-compiled hot kernels, is how you make a browser sim feel real. It also means your `.cljc` simulation core must produce its outputs into typed arrays at the boundary — which is a hard architectural commitment to make from the start.

**3. WebAssembly for the hottest kernels.** If you eventually want to push beyond the ~500-pawn ceiling in browser, the conventional path is: write the hottest 2-3 inner loops (gas diffusion, pathfinding heuristic) in C or Rust, compile to WASM via Emscripten or `wasm-pack`, and call from ClojureScript. The Emscripten model represents the C heap as a single massive `ArrayBuffer` — your CLJS code creates TypedArray views over it and reads/writes shared memory directly with no marshalling. The architectural triad is: **CLJS handles state coordination and game logic; WASM executes hot numeric kernels; WebGL renders directly from the same buffers WASM is writing to.** Zero copies, no JS heap allocations on the hot path.

This is fully achievable in 2026 — Three.js / PixiJS / Babylon.js all support binding GL attributes to user `ArrayBuffer`s — but it is a serious architectural commitment and not where you start.

#### Can a JVM-tractable sim run in browser?

For a RimWorld-scale sim with ~2000 pawns + 200×200 grid + active combat + storyteller events, **realistically no**. The compounding factors:

1. ClojureScript on V8 is ~2× slower than JVM Clojure on compute (David Nolen, Clojure Google Group, *"Clojure Performance For Expensive Algorithms"* thread: *"~2.2 seconds"* on V8 vs *"~1s running time for the JVM"*).
2. WebAssembly itself adds tax even in the best case — per Frank Denis's *"Performance of WebAssembly runtimes in 2023"* (00f.net, January 4, 2023): *"when using the fastest runtime, WebAssembly was only about 2.32 times slower (median) than native code with architecture-specific optimizations."*
3. Single-threaded; no JIT-warmup-of-tight-loop the same way.
4. Browser GC pauses are less tunable.

Realistic browser entity ceilings on modern hardware:
- **100-300 pawns + small map**: smooth at 30 Hz tick.
- **500-1000 pawns**: playable but visible jank, need to optimize hard (Workers + WASM).
- **2000+ pawns**: not happening without serious WASM work and lots of pain.

#### WebAssembly paths — honestly

- **GraalVM Web Image** (`native-image --tool:svm-wasm`) is, as of GraalVM 25 EA in 2025-2026, explicitly experimental. Per the official GraalVM docs at graalvm.org/latest/reference-manual/web-image/: *"Web Image is an experimental technology and under active development. APIs, tooling, and capabilities may change."* The output is a `.wasm` module *plus a JavaScript runtime wrapper* — you can't run it standalone in `wasmtime`. Quoting the same docs: *"You cannot run .wasm directly in Wasm runtimes because the generated module depends on JavaScript-provided imports and runtime. Thus commands such as `wasmtime hellowasm.js.wasm` will not work in the current version."* No public Clojure-on-Web-Image demo exists. Output sizes are large (41MB+ in a Drools demo by treblereel on dev.to). The Wasm I/O 2025 talk *"The Future of Write Once, Run Anywhere: From Java to WebAssembly"* (Patrick Ziegler & Fabio Niephaus, Barcelona, March 27-28 2025) is the canonical introduction. **Track, don't depend on.**
- **Jank** (Jeaye Wilkerson's LLVM-based Clojure dialect) hit alpha in **December 2025**, exactly as Jeaye targeted — the jank-lang.org README states *"jank is expected to have its alpha launch in December 2025!"* and the jank book itself warns *"jank is alpha quality software. It will crash. It will leak. It will be slow."* Jeaye went full-time on jank in January 2025 (per his blog post *"I quit my job to work on my programming language"*), framing the WASM target explicitly as a long-term aspiration: *"In three years, we'll have new game engines written in jank, jank written in existing game engines, GUI development, web services, jank support in all your favorite libraries, WASM builds, and serious performance to top it all off."* **Not a real path today.**
- **Squint and Cherry** (Michiel Borkent's lightweight ClojureScript-style compilers) emit JS, not WASM. Cherry's README: *"Cherry is an experimental ClojureScript to ES6 module compiler… ⚠️ This project is an experiment and not recommended to be used in production."* Squint similarly labeled WIP. Useful for small JS interop, not a game runtime.
- **ClojureDart → Flutter Web with WasmGC** is the *only* shipping-today Clojure-family-to-WASM-ish path. Roam Research ships mobile apps in ClojureDart (per the Tensegritics/ClojureDart README by Baptiste Dupuch and Christophe Grand). A verified Flame engine "Brick Breaker" port exists, documented in the Tensegritics newsletter on Buttondown (2025): *"This issue is about following the Flame's Brick Breaker tutorial in ClojureDart."* Flutter web is increasingly using WasmGC. **If browser + WASM is a hard requirement and you'll accept Dart's quirks, this is the path.** For a desktop-first sim, ignore.

#### Verdict on shared core

Build the sim in `.cljc`. Run it on the JVM for the real game with the scale you want. Run it in CLJS for a *web demo*, a *replay viewer*, an *online tutorial*, or a *small standalone vertical slice*. Do not promise feature parity at scale. If you eventually decide the browser is a serious target, the upgrade path is: Web Workers for the sim thread → ArrayBuffer-based world representation → WASM for the hottest kernels → WebGL bound directly to the shared buffers. Plan the data layout to permit this even if you don't implement it at first.

---

### 7. Practical "Toy Project" Starting Point

#### Minimum vertical slice (week 1-2)

- 50×50 grid of tile types (grass, stone, wall).
- 1-3 pawn entities with position, energy, current job.
- One job type: "haul stones to pile."
- A* pathfinding (idiomatic first, optimize later).
- Quil or play-cljc as the renderer (top-down 2D, colored tiles).
- World as one `defonce` atom; tick on a `(future …)` loop.
- REPL workflow: jack in, spawn pawns from REPL, watch them work.

#### Recommended starter stack (May 2026)

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps  {org.clojure/clojure        {:mvn/version "1.12.0"}
         org.clojure/clojurescript  {:mvn/version "1.11.132"}
         quil/quil                  {:mvn/version "4.3.1563"}
         ;; or: play-cljc/play-cljc for the OpenGL+WebGL shared-core option
         com.taoensso/nippy         {:mvn/version "3.4.x"}
         com.taoensso/tufte         {:mvn/version "2.x"}    ; profiling
         criterium/criterium        {:mvn/version "0.4.6"}  ; benchmarking
         com.clojure-goes-fast/clj-async-profiler {:mvn/version "1.x"}
         ;; pull these in when you start on AI in earnest:
         ;; com.evocomputing/odoyle-rules     {:mvn/version "..."}
         ;; cark/behavior-tree                {:mvn/version "..."}
         }}
```

JVM flags worth setting from the start:

```
-XX:+UseZGC
-XX:+ZGenerational         ; or -XX:+UseGenerationalZGC on newer JDKs
-Xmx4g -Xms1g
-XX:+UnlockExperimentalVMOptions
```

For the eventual real renderer, **plan to migrate from Quil to play-cljc or raw LWJGL3** when you outgrow Processing's draw model (likely around the 500-entity / 100×100 map mark). Quil is for the prototype, not the shipping game.

#### Common pitfalls in the first few weeks

1. **Spending three days on ECS architecture.** Don't. Start with `(defonce world (atom {:entities {} :grid …}))`. The "right" architecture will be obvious by week 4.
2. **Persistent maps for the grid.** Will work for 50×50, will be your first bottleneck at 200×200. Switch to a `long-array` when you feel the slowness.
3. **Lazy seqs in the tick loop.** Every `(map …)` in the hot loop is suspect. Use `mapv`/`reduce`/`run!`.
4. **Reflection.** Set `(set! *warn-on-reflection* true)` at the top of every namespace from day one.
5. **`get-in` on the spatial grid.** Allocates coordinate vectors and chases HAMTs. Flatten to a 1D primitive array indexed by `(+ x (* y width))` once you have any pathfinding.
6. **Over-using Datascript.** It's a beautiful tool. Don't put it in the inner loop.
7. **Writing your own A*** poorly. The first version will be slow; that's fine. Get something working, then optimize with `criterium` measurements in hand.
8. **Trying to make the browser version "just work" from day one.** Get the JVM version playable first. Worry about CLJS when you have a game.
9. **Forgetting `defonce`.** Reloading the namespace nukes your world every time. Use `defonce` for `world` and the render-context atom.

#### Order of operations

1. Tile grid + render (Day 1-2).
2. One pawn that you can spawn and move via REPL (Day 3).
3. A* pathfinding, idiomatic (Day 4-5).
4. Fixed-timestep tick loop with `defonce` state (Day 6).
5. Job system: define haul job, pawn picks it up, navigates, completes (Day 7-10).
6. Save/load with nippy. **Do this early** — once it works, refactor without fear (Day 11).
7. Second job type (mining or building) — proves the job-system abstraction (Day 12-14).
8. Needs (food, rest) — proves the mood/decision-making layer (Day 15-20).
9. Multiple pawns interacting — proves the contention/reservation logic (Day 21+).
10. Introduce O'Doyle for the deduction layer once linear job-selection gets ugly (Day 25+).
11. Storyteller events (Day 30+).
12. Pathfinding optimization (flatten grid, primitive arrays, priority queue) with profiling data (only when slow).
13. Combat (only when world feels alive).
14. Behavior tree library (cark.behavior-tree) once individual pawn AI exceeds a few flat states.

---

## Recommendations

**If you're committing to this project today, do this:**

1. **Stack: deps.edn + Clojure 1.12 + Quil 4.3 (or play-cljc if you want shared-core from day one) + nippy + criterium + clj-async-profiler.** This is the path of least resistance to a working prototype.
2. **JVM: OpenJDK 21+ with ZGC enabled.** This is no longer optional advice; it's how you make functional game dev not fight the GC.
3. **Architecture: one `defonce` atom containing one immutable world map.** Entities as maps keyed by ID. Spatial grid as a Java `long-array` referenced from the world map. Tick is a pure function `(defn tick [world] …)`. Render reads `@world`.
4. **Save/load on day 7** with nippy. Refuse to ship without it.
5. **Switch to primitive arrays for the spatial grid at the first sign of slowness** — not before. Premature optimization will kill the project.
6. **Set `*warn-on-reflection* true` in every namespace and treat reflection warnings as build errors** by week 2.
7. **Adopt O'Doyle + cark.behavior-tree for AI once the prototype is moving.** Don't reach for them on day 1; reach for them when you have 2+ job types and want to express priorities declaratively.
8. **Defer the browser port until you have a JVM game.** Then write the `.cljc` split with shared-core simulation; pick play-cljc or hand-roll the renderer split.
9. **Use Datascript for tooling (save inspector, debug overlays, AI explainers) only.** Not in the tick.
10. **Plan to migrate Quil → raw LWJGL3 (or play-cljc → LWJGL3)** when you hit the renderer's ceiling. Reserve ~2 weeks for this.

**Benchmarks/thresholds that should change your approach:**

| Symptom | Threshold | Action |
|---|---|---|
| Tick takes >16ms with N pawns | At any N where you wanted higher | Profile with clj-async-profiler; convert hot map to `long-array`. |
| GC pauses >5ms | Anywhere | Confirm ZGC is on; eliminate lazy seqs in tick; reduce per-tick allocation. |
| Pathfinding dominates profile | A* >5ms | Primitive arrays for cost/came-from; `PriorityQueue` Java interop; consider hierarchical A*. |
| Reflection warnings | Any in hot code | Type-hint immediately. |
| JVM heap >2GB | At <5000 entities | You have a structural leak; persistent maps are not the culprit at this scale. Probably accidentally retaining tick history. |
| Save/load >1s | At any save size | Profile nippy; use freeze-to-file with the `:compressor` set to LZ4. |
| AI decisions visibly stale | Multiple job types, decisions happening only every N ticks | Move job assignment into O'Doyle rules so it re-fires on relevant component changes only. |

**If you hit a wall and Clojure stops paying its rent:**
- For 90% of perf issues, the answer is "primitive arrays + type hints," not "rewrite in Rust."
- For genuine 10× perf needs in a specific subsystem (e.g., fluid sim), write that one namespace as a Java file, call from Clojure.
- If you need true SoA cache-coherent ECS at 100k+ entities — you're not building RimWorld-scale anymore, you're building Factorio-scale, and you should reconsider the project, not the language.

**If you want to be done in a weekend instead of a year:**
- Use the Quip game engine, target a 30×30 world with 5 pawns, ship a Lisp Game Jam entry. This is a valid project in itself and will teach you more about what you actually want than any planning doc.

---

## Caveats

- **The ecosystem is small.** You will be writing things that exist as off-the-shelf libraries in other languages (Bevy plugins, Unity assets). Budget for it.
- **No active Clojure colony-sim has been commercially shipped.** Robinson (Aaron Santos) is the closest precedent and is a survival roguelike, not a colony sim. You will be the experiment.
- **Browser performance claims are bounded.** The "~2× slower than JVM" figure from David Nolen is from carefully optimized code on V8; default ClojureScript on a default JS engine for typical idiomatic code is more like 5-10× slower than JVM Clojure for compute.
- **GraalVM Web Image, jank, and ClojureDart are moving targets.** All dates and status claims above are as of the research date (May 2026); status of experimental projects can change quickly in either direction.
- **The EntityGraph benchmark table** in Section 2 was sourced from the parallel-research synthesis and has not been independently verified; the *shape* of the comparison (DataScript is slow for simple lookups, varies for complex queries) is consistent with the broader literature, but the specific multipliers should not be cited without rechecking provenance. Same caveat applies to the "Bobcat compiler" for concatenative behavior trees — the design pattern is real and used commercially, but I haven't verified the specific named project.
- **The "RimWorld is C#/Unity" lesson is real.** Tynan Sylvester shipped a hit precisely because he leveraged Unity's *everything*. A Clojure colony sim will need to build (or inherit from libGDX/JME) more infrastructure than a Unity one. That cost is roughly **6-12 months of plumbing work** before you have feature parity in the surrounding engine, even if the simulation core is faster to write.
- **Save game compatibility across versions is your responsibility.** Nippy makes serialization easy; it doesn't make schema evolution easy. Plan a migration system from the start.
- **Datascript's perf characteristics may improve.** Tonsky has actively worked on Datalevin (a disk-backed variant) and there are experimental alternatives (Wizard, Asami). If a 10× faster Datalog appears, the architecture recommendation above shifts toward more DB-centric design.
- **No published RimWorld-scale Clojure benchmark exists.** Everything above is composed from per-subsystem benchmarks (A*, Datascript queries, reflection elimination) and general JVM performance characteristics. Reality may diverge in unforeseen ways at integration scale — which is precisely why the toy project is the right next step.
"""

This is the result of researching the development of a clojure based game like Rimworld. What I want in this directory is the skeleton of a repo for a purely desktop based game. I want the beginning and skeleton of a game loop as we've described so the thing can at least start running and outputting to a console. I also then want some structure for us to start developing the modules/components we will be using in our game. Things like the eventual pathfinding functions, world map and tile storage, etc.
