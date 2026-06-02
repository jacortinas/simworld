# sim, RimWorld-style colony simulation

Clojure 1.12 on JVM. Desktop-only. The project lives in `sim/` with `sim.*`
namespaces (the directory rename happened early, never use `colony.*`).

## Loop & timing model (vocabulary, use these terms)

- **Game loop**: the textbook per-frame cycle `input → update → render`. We
  SPLIT it across two threads (world atom = hand-off), so no single namespace
  *is* "the game loop":
  - **Render/frame loop**: libGDX thread (`sim.render.gdx`): input + draw,
    every frame. Always runs; never paused.
  - **Sim clock**: `sim.clock`: the UPDATE step only, advancing world-state
    at a fixed 30 Hz. Pausable.
- **Simulation / tick**: one fixed-timestep world update,
  `sim.simulation/tick` (pure `world → world'`). The sim clock drives it.
  `tick` = `advance-clock → schedule/run` (see tick bands below).
- **Tick bands & staggering**: `sim.schedule` tiers updates into `:normal`
  (every tick), `:rare` (125 ticks ~4.2s), `:long` (1000 ticks ~33s), keyed by
  each entity's `:ticker-type`. Rare/long use a physical bucket index in
  `(:schedule world)` so only ~1/interval of a band runs per tick (true
  O(active)). Pawns are `:ticker-type :never`, ticked every tick by normal
  systems; their needs/idle work self-throttle via `schedule/due?`. The index
  is DERIVED, not persisted; `schedule/reindex` rebuilds it on load. (RimWorld's
  TickManager model, see `docs/rimworld-engine-internals.md` §1.1.)
- **sim-time vs real-time**: pause freezes *sim-time* (pawns/jobs/needs);
  *real-time* (render, input, future animations/menus) keeps running. Anything
  that must animate while paused (sprite frames, camera tweens, menus) lives on
  real-time, not the clock.
- **Liveness vs sim-time**: `running?` (clock thread alive: `start!`/`stop!`)
  is a different axis from `paused?` (clock advancing: `pause!`/`resume!`).
- **Screens (implemented):** `sim.app` is the app state machine ABOVE the clock
  (`@app` holds `:screen`): MainMenu -> Worldgen (async generation on a future) ->
  Play, plus a PauseMenu overlay. `next-app-state` is the pure, unit-tested
  transition fn; the effectful wrappers own `clock/start!`/`stop!` and the libGDX
  input-processor swap, so "program started" is not "simulation running." Screens
  live in `sim.screens.*`; `sim.core` boots at MainMenu with the clock stopped.
  See `docs/screens-design.md`.

## What's working

- **Layer 1, core sim.** Tiles (linearized grid), pawns with needs/skills,
  8-directional A* pathfinding (octile heuristic, diagonal ×√2 cost, no
  corner-cutting), `:go-to` jobs. Pawns GLIDE between cells (sub-cell movement
  timing, see the movement decision below), not teleport. 30 Hz fixed-timestep
  tick loop; rendering is fully decoupled (see below).
- **Layer 2, items + haul jobs.** `:haul` job is a 4-phase FSM
  (`:go-to-item → :pickup → :go-to-dest → :drop`). `job/advance` returns a
  *world* (not pawn) so jobs can mutate multiple entities atomically.
- **Layer 2.5, debug log.** `sim.log` namespace; `(:log world)` is a
  bounded ring (500 entries). Logged at chokepoints: every event, every
  pickup/drop, every `mark-state` terminal transition. Lives in the world
  so it travels with `save-game!`.
- **Rendering, libGDX, 32px sprites.** `sim.render.gdx` owns the window (own
  thread + GL context) and reads `@sim.world/world` every frame. Pure layer
  fns compose under one SpriteBatch, drawing 32rogues sprites preloaded as
  Textures by `sim.render.sprites` (Nearest-filtered for crisp pixel art):
  `render.layers.terrain` / `.items` / `.pawns`. `tile-size` is 32. The terrain
  layer stamps a per-terrain `:color` base quad (content in `terrain.edn`, via
  the shared 1px pixel texture) then the detail sprite on top, transparent
  "(no bg)" cells show the bright base; `:water` uses the real water tile on
  `animated-tiles.png`. All layers
  anchor at the tile's bottom-left with a `(height-1-y)` flip, matching
  `sim.input/screen->tile` so clicks hit the right tile. Dual
  `OrthographicCamera`, world cam pans/zooms, fixed UI cam for the HUD.
- **Debug overlay (dev).** `render.layers.debug` is a gated layer drawn LAST in
  the world block: for each pawn with a job path it draws the *remaining* route
  (sliced at `:path-index`) as cyan segments + an amber goal marker, reusing the
  1×1 pixel texture. Gated by a `:debug?` flag in `sim.ui-state`; toggled by the
  backtick key or `(debug!)` in the REPL. It's a pure VIEW of `(:path,
  :path-index)`, it NEVER trims the stored `:path`. Geometry (`path->segments`,
  `remaining-path`) is pure + unit-tested headless (`sim.debug-layer-test`); the
  `draw` GL calls stay untested like the other layers.
- **Tile inspect + entity selection.** A pure, headless-tested `sim.inspect`
  core (`describe-tile`, `selectable-at`) backs two dumb GL views: a bottom-
  right hover panel (`sim.ui.inspect-panel`, UI cam) showing the hovered tile
  as concept lines (terrain `Grass 100%` / `Stone (impassable)`, then one line
  per selectable entity), and a world-space selection box
  (`sim.render.layers.selection`, drawn between pawns and the debug overlay).
  Left-click cycles `:selected` through a tile's selectable entities (any kind,
  not just pawns); selection is the substrate future right-click action menus
  will read. Same pure-core / untested-`draw` split as the debug layer.
- **Input, RimWorld grammar.** `sim.input` proxies an InputAdapter:
  left-click cycles selection through a tile's selectable entities
  (pawns/items/trees, by id; empty tile clears), right-click issues a forced
  `:go-to`, but only to a selected PAWN (a tree/item selection is ignored),
  mouse-move records the hovered tile (`ui/set-hover!`) for the inspect panel,
  middle-drag pans, wheel zooms, WASD/arrows pan (polled per frame),
  backtick (`) toggles the debug overlay. `sim.command` is the one namespace
  that touches BOTH the world and ui-state atoms. (Space→pause is injected to
  avoid an `input→clock` dep; backtick→debug and mouse-move→hover call `ui/…`
  directly since `input` already depends on `ui-state`, inject only to cut NEW
  edges.)
- **Pause + lifecycle.** The loop boots PAUSED. An in-window button
  (`sim.ui.hud`) and the space key toggle pause, both just flip an atom.
  The window runs on the MAIN thread (all platforms), so closing it stops the
  loop and exits the process, window-life = process-life, under `:run` and
  `:repl` alike. The terminal renderer (`sim.render`) still exists for REPL
  path-viz but no longer runs inside the loop.
- **Layer 3, autonomy + content (the survival loop).** Pawns now run
  themselves: a data think-tree (`sim.think`) picks behavior, eat when hungry
  (walk to nearest reservable food, consume it, refill `:food`), else haul a
  loose item to a stockpile, else wander.
  Needs decay on the rare band; the hunger threshold and decay rates are content
  (`resources/defs/*.edn`, loaded by `sim.defs`). Reservations
  (`sim.reservation`) keep two pawns off the same target. Verified end-to-end:
  a hungry pawn seeks, eats, and goes back to wandering. Job types: `:go-to`,
  `:haul`, `:eat`. Auto-haul DONE: the `give-haul` think-tree leaf gathers loose
  items (grounded, not already stockpiled, reservable) into the nearest stockpile
  cell. NOT yet built: a work-priority matrix (give-haul is forward-compatible,
  it re-homes into a `:hauling` WorkGiver registry unchanged; see the spec).
- **Stockpile zoning + placement mode.** `Z` enters zoning mode; left-DRAG
  paints a stockpile rectangle, SHIFT+left-DRAG erases cells, `Esc`/right-click
  exits. Zones are saved world state (`sim.zone`, `:zones`); the live drag
  rectangle is view state. The drag-rectangle core is reusable for future
  building placement. Pawns don't USE stockpiles yet (that's the haul leaf).
- **Buildings + reachability (the spatial spine, Stages 1-2).** Walls are `:building`
  entities (a `:thing` def with `:kind :building`, `:blocks-path? true`), placed
  one at a time in `B` build mode (a hover cursor shows validity; shift-click
  deconstructs; `Esc`/right-click exits). A derived, memoized PathGrid
  (`sim.pathgrid`: a per-cell cost `double-array`, terrain move-cost with INFINITY
  at blockers) is what `sim.pathfinding` and the `sim.regions` reachability cache
  READ, so a wall built at runtime reroutes pawns and can wall off a goal (O(1)
  `reachable?` rejection). Pawns mid-walk replan via step-validation (`walk-toward`
  nils `:path` when the next cell is now blocked). `F1`/`F2` toggle transparent
  region / pathgrid debug overlays. Stage 2 (a chunked incremental region
  graph with O(1) component reachability) is now in; next is rooms (Stage 3).

## Load-bearing architectural decisions

- **One world atom.** `defonce`'d in `sim.world`. Survives REPL reload,
  recompile code, world state persists. The whole REPL-driven workflow
  hinges on this.
- **Pure tick function.** `(sim.simulation/tick world) → world'`. The
  sim clock atomically swaps with this. No mutable state inside sim code.
  *One deliberate exception:* `tick` (and pathfinding) transitively read the
  immutable Def DB (`sim.defs`, a global atom). Because defs never change after
  load, this is morally a constant read, determinism holds (same-seed runs stay
  identical). See the content/state split below.
- **Content/state split (the Def DB).** Game CONTENT lives in `sim.defs`, a
  `defonce` registry loaded from `resources/defs/*.edn` at namespace-load,
  spec-validated, repopulated on every reload (edit EDN + `(reload-defs!)` lands
  live; the atom identity is stable like the world atom). It is **never in the
  world map and never saved**, `sim.save` doesn't touch it. This is `docs/
  rimworld-engine-internals.md` §3's two rules: (1) state references content by
  *keyword* (`:material :wood`, a `:grass` grid cell), never by embedding, so the
  world stays an acyclic map nippy writes directly; (2) immutable defs separate
  from mutable state ⇒ tiny saves, forward-compatible saves, trivial mod merging,
  zero cyclic-graph pain. Migrated content (use-time reads only): terrain
  (`sim.tile` wrappers delegate to `defs/terrain`), materials (`defs/material`),
  need-decay rates (`defs/need-decay`). Lookups have a graceful fallback (unknown
  terrain → grass), so a dangling reference degrades, not crashes. **Seam closed
  (was "Seam left"):** construction-time content (pawn starting `:needs`,
  `:ticker-type`, `:move-ticks`) is now def-driven via the `:thing` category
  (`resources/defs/things.edn`): `sim.entity/make-thing` reads a per-type
  template and the `make-*` constructors are thin wrappers. The feared
  defs-before-entities load-order coupling was a non-issue, `sim.defs` depends on
  nothing in `sim`, so the require graph loads defs before any construction
  (`sim.entity` → `sim.defs`). Entities carry a `:def` back-ref; construction
  fails fast on an unknown def-id (use-time lookups still degrade).
- **Scheduler, not a uniform tick.** `tick` = `advance-clock → schedule/run`.
  Per-tick work is registered band systems (`schedule/register-system!`), not a
  hand-written pipeline. New periodic work = a system on a band; new bucketed
  entity kinds = a `:ticker-type`. `entity/add-entity`/`remove-entity` are the
  index chokepoint (they call `schedule/register`/`unregister`), keep ALL
  entity lifecycle flowing through them so the bucket index can't drift. The
  registry is a `defonce` atom; `register-system!` replaces by name, so reloads
  re-register idempotently.
- **Kind-indexed entity queries (a second derived index).** Alongside the
  scheduler's bucket index, `(:kinds world)` is a per-kind SORTED-SET of ids
  (`{:pawn #{…} :item #{…} :tree #{…}}`). `entity/pawns|items|trees` read it, so a
  kind query is O(of-that-kind), not an O(all-entities) `(filter (vals :entities))`
  (it was run 3× per tick + per render layer per frame, nested in O(items×pawns)
  deliberation). Same contract as the scheduler index: maintained at the
  `add-entity`/`remove-entity` chokepoint (`index-kind`/`unindex-kind`), DERIVED,
  never the source of truth, stripped on save, rebuilt on load by
  `entity/reindex-kinds`. SORTED sets, not hash sets, so the queries iterate
  ASCENDING BY ID: deterministic per-kind iteration with no per-call sort, the
  precondition for parallel job execution (every worker must see entities in the
  same order). `all-entities` stays `vals`-order (its consumers sort if they need
  to). Still provisional: the entity-id counter is a process-global atom; it folds
  into the world with the eventual column-store (decide the id scheme once there).
- **Job-as-data with multimethod dispatch.** `(defmulti job/advance ...)`,
  dispatch on `(:type job)`. New job types are pure `defmethod` adds,
  zero touches elsewhere. Job types so far: `:go-to`, `:haul`, `:eat`.
- **AI is a DATA think-tree, not a cond.** `sim.think` holds a priority tree
  (`default-tree`) walked depth-first, first valid leaf wins (RimWorld's
  `ThinkNode_Priority`). Nodes reference predicates/job-givers by KEYWORD,
  resolved via the `preds`/`givers` registries, so the tree is inert data
  (EDN-ready, moddable later) while behavior lives in code. `deliberate` is pure
  `(world,pawn) -> job-or-nil`; `sim.ai/redeliberate` routes the job through
  `sim.job/assign` (AUTO, so the reservation gate applies). Adding a behavior =
  add a node + register one giver; the walker never changes. Current leaves: eat
  (hungry pawn → nearest reservable food → consume, refill `:food` to 1.0), haul
  (idle pawn → nearest reservable loose item → nearest stockpile cell, between
  eat and wander in priority), and wander (go-to a random nearby cell). The
  hunger threshold is content (`needs.edn :food :seek-below`). NOT yet built: a
  constant/reflex tree for mid-job interrupts, `PrioritySorter` (float-ranked
  children), and a WorkType priority matrix (give-haul re-homes into it as a
  WorkGiver unchanged).
- **`advance` returns a *world*, not a pawn.** Pickup/drop need to touch
  both the pawn AND the item; returning a world makes that natural. Don't
  revert this, every job since haul depends on it.
- **Movement is grid-faithful but glides (RimWorld's model).** Pawns always
  *logically* occupy an integer cell; the smoothness is a render lie.
  `walk-toward` is a sub-cell progress accumulator: a `:move {:from :to :elapsed
  :cost}` record in the job tracks the segment in flight, `:elapsed` climbs one
  tick per `advance`, and the cell completes when `:elapsed >= :cost`. Three
  load-bearing rules: (1) **`:pos` flips to the destination cell at move START**
  (the pawn occupies the cell it is ENTERING, reservation/collision honesty;
  consequence: a mid-move pawn is selectable on its *leading* cell). (2) **Speed
  is denominated in TICKS, never wall-clock**, `segment-cost = max(1, round(
  move-ticks × pathfinding/traversal-cost))`, the SAME currency A* minimizes, so
  the route chosen is the fastest to walk, and a future clock tick-rate
  multiplier scales speed/needs/bands together for free (the deferred speed-
  settings seam). (3) **The progress FLOAT lives only in render** (`sim.render.
  interp/draw-pos` lerps `:from → :pos` by `:elapsed/:cost` with the Y-flip),
  the sim stays integer-tick, so same-seed runs are bit-identical. There is NO
  speed gate in `sim.ai/advance-job` (the old 15-tick `moves-this-tick?`
  complected walk-speed with deliberation cadence, deleted; advance runs every
  tick). `:move`/`:move-ticks` are plain world state, so they save for free; old
  saves load settled.
- **Player override pattern.** Player commands set `:priority :forced
  :source :player` on the job (the `job/forced-by-player` override). Auto-
  assignment later will check these.
- **Placement mode (reusable drag-rectangle).** `sim.ui-state/:mode`
  (`:select` default | `:zone-stockpile`) gates input: in zoning mode a left-DRAG
  paints a rectangle (`Z` enters, `Esc`/right-click cancels), and SHIFT+left-DRAG
  ERASES cells (`zone/remove-cells`, dropping emptied zones; preview turns red).
  The erase-ness is decided by whether Shift is held at drag-START and stored as
  `:erase?` in the drag, so it's stable for the whole gesture (RimWorld's
  pick-the-tool-then-drag feel). The in-progress rect lives in ui-state `:drag`
  (a preview, VIEW state, never saved); the
  committed result is a stockpile zone in the WORLD (`:zones`, saved). Same
  world-vs-view split as `:selected`/`:hover`. The drag/commit core is
  payload-agnostic, `sim.zone/cells-in-rect` + the mode machinery are what a
  future building-placement tool reuses; only the "what to create" differs
  (`sim.zone/add-stockpile` filters to in-bounds/passable/unzoned cells). Pure
  model + geometry are headless-tested; the input-proxy drag and the GL fill are
  the untested edges.
- **Selection is broader than commands.** `:selected` holds ANY selectable
  entity id (pawn/item/tree), set by left-click cycle-select. But verbs are
  kind-gated: `right-click!` only orders pawns, a non-pawn selection is
  ignored, never stamped with a dead job (the move-order guard). Widening
  `:selected` past "pawn id" is exactly why that guard exists; future context
  actions dispatch on the selected kind. Select-then-act, RimWorld-style.
- **One assignment chokepoint.** ALL job assignment routes through
  `sim.job/assign` (pure `world → world`: sets the job + logs `:job/assigned`).
  Player clicks, REPL helpers, and future auto-assignment all call it, so the
  set-job/log side effects can't drift between callers. The log entry is
  derived from the job map, so new job types log for free.
- **Reservations are a DERIVED query, not stored state.** `sim.reservation`
  answers "who claims target X" purely from the pawns' active jobs, each job
  encodes its target (`reserved-targets`: `:haul` → `[item-id]`; `:go-to` →
  nothing). `claimant` returns the LOWEST-id active claimer via `(apply min …)`
  (deterministic regardless of iteration order, and now `(entity/pawns)` yields
  pawns id-sorted via the `:kinds` index anyway, so the min is belt-and-
  suspenders); `can-reserve?` is true if unclaimed or self.
  **Release is a non-event:** a cleared job's claim just vanishes, so there is no
  release fn to call and no "phantom reservation" bug class. Two enforcement
  points: (1) `sim.job/assign` refuses an AUTO (non-forced) job whose target is
  claimed by another pawn (logs `:job/blocked`); forced player orders override
  (player is boss). (2) the haul `:pickup` phase guards on `can-reserve?` so a
  same-tick race can't double-grab. Invariant, **reserve what you'll write**:
  one-claimant-per-target makes pawn writes disjoint, which is the precondition
  for parallelizing job execution later (the runtime stand-in for ECS archetype
  write-analysis). The future parallel-assignment path is propose→reconcile over
  `claimant`, NOT lock-based CAS (which would be non-deterministic). `sim.reservation`
  depends only on `sim.entity` (it interprets jobs but never requires `sim.job`,
  keeping the graph acyclic).
- **Render layers are pure projections.** A layer = `(draw world batch)`.
  Compositor walks them in order. Z-order replaces ASCII precedence as
  the "what shows on top" mechanic.
- **Liveness vs. activity are separate axes.** `running?` (is the loop thread
  alive, `start!`/`stop!`, heavyweight, joins the thread) is distinct from
  `paused?` (should the live loop advance, `pause!`/`resume!`, just an atom
  write). The pause button/space take the atom-write path so they never block
  the GL thread on a thread-join.
- **Rendering is decoupled from the loop.** `sim.clock` ONLY advances the sim;
  the libGDX thread renders independently off the same atom. Pausing resets
  nothing, it just skips `drain-ticks`. `prev` keeps advancing every
  iteration, so a resume does NOT fast-forward through banked wall-time.
- **UI eats the click first.** A left click is offered to `sim.ui.hud` before
  it can become a world command, the input-side mirror of "HUD draws last"
  z-order. Every future widget reuses this ordering.
- **Solid rects = a 1×1 white texture**, tinted and stretched through the
  existing SpriteBatch, no second renderer, no begin/end juggling. It backs the
  HUD bar, the debug overlay, the selection box, and the inspect panel; it was
  the on-ramp to 32px sprites.
- **Hot-reload rides on var late-binding.** Direct `ns/fn` calls on the GL
  thread (layers, commands) re-resolve their var each frame, so reloads land
  live. Two captured-once exceptions: values injected into the input proxy
  (wrap them as `(fn [x y] (hud/click! x y))`), and the `run-loop!` body
  itself (loop edits need `(restart!)`, not just `:reload-all`).

## Clojure gotchas we've already paid for

- **Only `^long` and `^double` are primitive arg hints.** `^int`, `^short`,
  etc. throw *"Only long and double primitives are supported"* at compile
  time. For Java interop methods with `int` params, drop the hint and let
  proxy box.
- **Primitive-hinted fns cap at 4 args.** Error: *"fns taking primitives
  support only 4 or fewer args"*. Drop primitive hints from the arg list
  and re-bind inside the body with `(let [x (long x)] ...)` to keep
  primitive math in the hot loop without exceeding the arity limit.
- **Don't use `^double` on a `def`.** It tries to resolve `double` as a
  type → collides with `clojure.core/double` (the fn). Use `^:const` for
  typed numeric constants and coerce explicitly with `(double v)` where
  needed.
- **Source order matters at cold start.** `clj -M:repl` compiles
  top-to-bottom in one pass. `:reload` is forgiving because vars from the
  previous load still exist. Define functions *before* their callers in
  source order; reach for `(declare ...)` only when there's a real
  mutual recursion.
- **`:reload` vs `:reload-all`.** `:reload` recompiles only the named ns.
  `:reload-all` walks the require graph. After multi-file edits, do
  `(require 'user :reload-all)`, it's the canonical "reload everything"
  one-liner.
- **`defmulti` is `defonce`-like.** Reload doesn't reset dispatch tables.
  `defmethod` re-registers in place. If you ever rename a dispatch value,
  the old methods linger, `(.removeMethod multi old-key)` to clean up.

## Environment notes (Windows specifics)

- **Windows console is CP-1252.** Cannot render `█`, `…`, etc. Use ASCII
  fallbacks (`#`, `...`) in any string that hits stdout.
- **JDK 24 removed `-XX:+ZGenerational`.** ZGC is now generational by
  default. Don't add the flag to `:jvm-opts`.
- **Maven `:classifier` is no longer supported in deps.edn.** Use
  `groupId/artifactId$classifier` in the lib name. Example:
  `com.badlogicgames.gdx/gdx-platform$natives-desktop`.
- **libGDX 1.13.1 pins LWJGL 3.3.3, which predates JDK 24's JNI version**
  → `[LWJGL] Unsupported JNI version detected` warning. Fix: override all
  six LWJGL modules (lwjgl, -glfw, -jemalloc, -openal, -opengl, -stb) plus
  their `$natives-windows` to 3.4.1 in deps.edn. ALL modules must share one
  version, mixing 3.3.x and 3.4.x crashes at runtime.
- **`--enable-native-access=ALL-UNNAMED`** is in `:run`/`:repl` jvm-opts to
  silence the JDK native-load restriction warning (libGDX loads .dll via
  `System.load`). Will become a hard error in a future JDK.
- **deps.edn changes need a full REPL restart**, not `:reload-all`, the
  classpath is fixed at JVM launch.
- **Clojure CLI from the official PowerShell installer registers a PS
  module, not a binary on PATH.** External tools (Zed, IntelliJ, CI)
  can't find `clojure.cmd`. Fix: `scoop install deps.clj` for a real
  binary shim on PATH.
- **`clojure.pprint` / `clojure.repl` aren't auto-loaded.** Add them to
  `dev/user.clj`'s `:require` block explicitly.

## REPL workflow

Boot: `clj -M:repl` (dev paths + helpers loaded). The window opens on the
MAIN thread (uniform on all platforms; required by macOS), and a
`clojure.main` REPL runs on a daemon background thread reading the same
terminal. On macOS compose the `:mac` alias: `clj -M:mac:repl`.

```clojure
(in-ns 'user)               ;; the helper namespace
(reset-world!)              ;; fresh world
(spawn-pawn! "Test" [5 10])
(go!)                       ;; start engine + resume (window already open)
(status)                    ;; loop/paused/tick/counts at a glance
(pause!) (resume!)          ;; or click the in-window button / press space
(debug!)                    ;; toggle path overlay (or press backtick in-window)
(reload-defs!)              ;; reload content from resources/defs/*.edn (no ns reload)
(tick! 100)                 ;; manual step, works even while paused
(quit!)                     ;; close the window = exit the process
```

Window-life = process-life: the window launches with the process and closing
it (or `(quit!)`) exits. So there is no `(go!)`/`(halt!)` window spawn/kill,
`go!` now just starts+resumes the sim clock, `halt!` stops it (window stays).

After editing source: `(require 'user :reload-all)`, preserves the world
atom via `defonce`. Layers, commands, and the HUD reload live. **But edits to
`run-loop!` itself need `(restart!)`**, the running clock thread holds the
old compiled loop body until `start!` respawns it.

`clj -M:run` is "run only" (no REPL). Closing the window exits the process.

## Open questions / not yet decided

- **Items in `:entities` vs separate `:items` map.** Currently unified. UPDATE:
  the `:kinds` index (per-kind sorted-set of ids) makes kind queries O(of-kind)
  over the unified map, so the *performance* motive to split has largely
  evaporated; revisit only if a kind needs a genuinely different storage shape.
- **Carried items have `:pos nil`.** No visual indicator that a pawn is
  carrying something. Could add `@s` rendering when carrying, deferred
  until libGDX layers are in place.
- **Job action abstraction.** Haul defmethod is verbose because every
  branch threads through `entity/update-entity`. A `[updated-pawn
  mutations]` shape might emerge once we have a 3rd job type. UPDATE: `:eat`
  (the 3rd type) landed and reused the existing helpers (`walk-toward`,
  `next-phase`, `mark-state`, `set-job`) cleanly, no refactor needed for
  walk-to-target-then-act jobs. The question stays open for a genuinely
  different shape (mining = modify terrain; building = create an entity).
- **Path caching vs. replanning.** A `:go-to` path is computed once and followed
  via `:path-index`. It's primed eagerly at assignment (`job/assign` →
  `prime-path`) so the route lives in world state immediately, the overlay
  draws it the instant the order is given, even while PAUSED (no tick has run).
  `walk-toward`'s lazy `(nil? path)` branch is the fallback: it recomputes
  whenever `:path` is nil (haul nils it per *phase* via `next-phase`; future
  dynamic obstacles will nil it for a free recompute). Correct ONLY while the
  grid is NO LONGER static mid-walk: player-built walls are the first runtime
  walkability change, so *step-validation* is LIVE: before entering the next cell,
  `walk-toward` checks it against the `sim.pathgrid` PathGrid and nils `:path` when
  a wall has appeared, so the existing `(nil? path)` branch replans for FREE.
  Passability is the derived PathGrid (terrain + building blockers) that
  `find-path` and `sim.regions` both read. Do NOT add recompute-every-
  tick (A* is now the array-backed `java.util.PriorityQueue` implementation in
  `sim.pathfinding`, fast, ~2-12× the old map-based scan, but a full replan
  every tick is still wasteful). **Stages 1-2 of the reachability work are DONE:**
  `sim.regions` labels passable cells into connected components and `find-path`
  short-circuits to nil in O(1) when start/goal are passable but in different
  components (~15,700× on the 120×120 split-grid worst case: 1.69ms → 108ns warm).
  Stage 2 upgraded the flat labeling to a CHUNKED region graph (12×12 chunks,
  cross-chunk adjacency, a union-find component layer) with INCREMENTAL rebuild via
  PathGrid cost-diff (re-flood only the dirty chunks), behind the unchanged
  `reachable?`/`region-at` interface. `build-graph` was then optimized (HashMap
  edge accumulator + allocation-free inlined corner rule) so the incremental
  rebuild is ~3ms on 200×200 (was ~10ms). STILL deferred: hierarchical pathing
  (HPA*) for big maps, a fully incremental `build-graph` (border-scoped relink so
  it is O(dirty) not O(cells)), and rooms (Stage 3: enclosure detection). This is
  RimWorld's model (regions + reachability cache + dirty-on-build + per-step path
  validation).
- **GLFW restart-in-same-JVM is now moot.** The unified main-thread model
  removed the window spawn / `(go!)`-reopens-window path, so the old "can we
  restart libGDX in one REPL session?" question no longer applies,
  window-life = process-life, and a fresh run is a fresh JVM.
- **Sprite migration is DONE.** All three world layers draw 32rogues sprites
  (`sim.render.sprites` preloads the sheets; cell maps transcribed from the
  `.txt` files, validated by `sim.sprites-test`). The world-space selection box
  is now DONE (see "Tile inspect + entity selection"). Terrain now renders a
  per-terrain `:color` base + "(no bg)" detail sprite (bright, not the old dark
  dungeon look); `:water` uses the real water tile from `animated-tiles.png`
  (static frame). Not yet done: tile ANIMATION (a real-time frame-stepper for
  the water/fire frames), autotiling for walls/edges (`autotiles.png`), and a
  HUD font upgrade (default BitmapFont at 1.0, crisp but small; gdx-freetype
  would give larger crisp text).

## Files to know

- `src/sim/defs.clj`: the Def DB: `defonce` content registry loaded from
  `resources/defs/*.edn` at ns-load, spec-validated. Lookups `terrain`/
  `material`/`need`/`need-decay`/`thing`/`ids`; `load!`/`load-sources!` (the mod seam).
  NOT in the world; NOT saved.
- `resources/defs/{terrain,materials,needs,things}.edn`: game content (move-cost/
  passable?/char; weight/char; per-need decay; per-type construction templates,
  kind, ticker-type, starting needs, move-ticks, material). Edit + `(reload-defs!)` to retune.
- `src/sim/world.clj`: the atom + initial-state shape (incl. the derived
  `:schedule` and `:kinds` indexes)
- `src/sim/simulation.clj`: the pure tick function + band-system defs/registration
- `src/sim/schedule.clj`: tick-band scheduler: bucket math, the derived bucket
  index, the band→systems registry, `run`/`run*`, `reindex`
- `src/sim/clock.clj`: the simulation clock (fixed-timestep tick driver);
  `start!`/`stop!` (liveness), `pause!`/`resume!`/`toggle-pause!` (sim-time)
- `src/sim/job.clj`: defmulti `advance`, haul phases, `assign` (+ `prime-path`:
  eager go-to pathing at assign time), and the movement core: `segment-cost`
  (ticks to cross a cell) + `walk-toward` (the sub-cell progress accumulator)
- `src/sim/pathfinding.clj`: 8-directional A*: `find-path` + `traversal-cost`
  (the unified terrain×√2-diagonal cost A* and movement share); octile heuristic,
  corner rule. Internals are primitive-array state (g/came-from/closed) + a
  `java.util.PriorityQueue` open set ordered by a total `(f, idx)` key; `traversal-
  cost` stays the public cost currency. octile consistency needs `move-cost ≥ 1.0`
  (enforced by `defs ::move-cost`). Reads the shared `sim.pathgrid` cost array
  (terrain + building blockers, no per-search snapshot) and short-circuits to nil
  via `sim.regions` before A* when start/goal are in different connected components.
- `src/sim/regions.clj`: PURE CHUNKED region graph (RimWorld's reachability
  cache): `of-pathgrid` floods 12×12 chunks into per-chunk-canonical region NODES,
  links cross-chunk adjacency (the SAME 8-dir + corner-rule A* uses, read from a
  `sim.pathgrid` cost array), and union-finds the nodes into COMPONENTS so
  `reachable?` is an O(1) component compare. Memoized by PathGrid IDENTITY in a
  size-1 `defonce` atom; a miss re-floods only the chunks whose costs changed
  (diff vs the cached cost array), then rebuilds the graph + components.
  `reachable?` (takes a world), `region-at`/`count-regions` (NODE count, not
  component count); `of-grid` is a terrain-only convenience. Cache CANNOT go stale
  (a new PathGrid identity → rebuild; the diff is exhaustive), so no false
  negatives. Depends on `sim.pathgrid` + `sim.tile`; NOT in the world, NOT saved.
  Deferred: border-incremental relink (an O(dirty) `build-graph`) and HPA*.
- `src/sim/pathgrid.clj`: derived per-cell traversal-cost `double-array`
  (`build`/`for-world`/`cost`/`passable?`): terrain move-cost with INFINITY at
  blocking buildings. A MEMOIZED PROJECTION keyed on `[grid, (:kinds :building)]`
  identity (size-1 `defonce`), so NO save/world/chokepoint wiring; reads the world
  by raw `get-in`, requires only `sim.tile`. Pathfinding + regions read it.
- `src/sim/reservation.clj`: PURE derived reservation queries
  (`reserved-targets`, `claimant`, `can-reserve?`) over pawns' active jobs; no
  stored state, release is automatic. Depends only on `sim.entity`.
- `src/sim/zone.clj`: PURE stockpile-zone model + rectangle geometry
  (`cells-in-rect`, `add-stockpile`, `stockpile-cells`, `cell-zoned?`). `:zones`
  is plain saved world state. The drag-rect core is reusable for future
  placement (buildings).
- `src/sim/render/layers/zones.clj`: stockpile floor overlay + live drag
  preview (pure rect geometry + untested GL fill); composed just above terrain.
- `src/sim/think.clj`: the DATA think-tree + pure walker (`deliberate`) +
  pred/giver registries + `default-tree`. Idle behavior selection lives here.
- `src/sim/ai.clj`: `advance-job` (job execution, every tick, NO speed gate;
  speed lives in `job/segment-cost`) + `redeliberate` (idle choice: walks
  `sim.think` then `job/assign`s the result; rare-throttled by the caller)
- `src/sim/log.clj`: debug log helpers (append/recent/of-type/for-pawn)
- `src/sim/inspect.clj`: PURE tile inspection: `describe-tile` (concept-line
  strings), `selectable-at` (entities on a tile, sorted by id). Headless-tested.
- `src/sim/command.clj`: player commands; the one bridge between the world
  and ui-state atoms. left-click = cycle-select; right-click = move order
  (pawns only); `can-build-wall?`/`build-wall!`/`deconstruct-wall!` (build mode)
- `src/sim/input.clj`: InputAdapter proxy (mouse/keys → commands + pause +
  backtick→debug + `B`→build mode (click place / shift-click deconstruct) +
  `F1`/`F2`→region/pathgrid overlays + mouse-move→hover)
- `src/sim/ui_state.clj`: camera + `:selected` (any kind) + `:hover` + `:mode` +
  `:debug?`/`:debug-regions?`/`:debug-pathgrid?` view-state (plain data);
  `selected`/`select!`, `hover`/`set-hover!`, `debug?`/`toggle-debug!` (+ the
  region/pathgrid toggles)
- `src/sim/ui/hud.clj`: HUD widgets: bounds + draw + click (the pause button)
- `src/sim/ui/inspect_panel.clj`: bottom-right hover inspect panel (GL view of
  `sim.inspect`; untested `draw`)
- `src/sim/render/gdx.clj`: libGDX window, layer compositor, HUD draw, input wiring
- `src/sim/render/sprites.clj`: 32rogues sheet loading + cached region lookup + cell maps
- `src/sim/render/interp.clj`: PURE render-time `draw-pos`: lerps a gliding
  pawn's DRAWN position between cells (the only place tick-progress becomes a
  float; render-only, never stored). Used by the pawns + selection layers.
- `src/sim/render/layers/{terrain,items,pawns}.clj`: pure sprite-draw layers
  (`pawns` draws at `interp/draw-pos`, so walkers glide)
- `src/sim/render/layers/selection.clj`: world-space selection box; pure
  `selection-box-rects` (world-pixel based; follows the glide via `draw-pos`) +
  the GL `draw`
- `src/sim/render/layers/debug.clj`: gated debug overlay (pawn paths); pure
  `path->segments` / `remaining-path` + the GL `draw`
- `src/sim/render/layers/buildings.clj`: draws `:building` entities (the wall
  sprite), above items and below pawns
- `src/sim/render/layers/build_cursor.clj`: single-cell hover indicator in
  `:build` mode (pure `cell-rect` + untested GL fill, tinted by `can-build-wall?`)
- `src/sim/render/layers/{debug_regions,debug_pathgrid}.clj`: gated transparent
  overlays: per-region tint (`F1`) and blocked-cell wash (`F2`); pure
  `region->color` + untested GL fill
- `src/sim/render.clj`: terminal renderer (REPL path-viz only now)
- `dev/user.clj`: REPL helpers (`go!`/`halt!`/`restart!`, `status`,
  spawn-*, assign-*, log, `debug!`, etc.)

## Planning docs convention

All planning docs are personal and gitignored under `.context/`: design
**specs** in `.context/specs/`, implementation **plans** in `.context/plans/`.
None of it is tracked in the repo. The tracked `docs/` holds only durable
reference material (`rimworld-engine-internals.md`, `screens-design.md`,
`debug-layer-design.md`, `original-deep-research.md`), never specs or plans.

## Output style preference

The user is on mobile and prefers learning-mode responses with `★ Insight`
blocks for 2–3 educational points specific to the work just done. Lean on
the architectural "why", they're learning Clojure mid-stream and value
understanding tradeoffs over watching me code.
