# sim, a RimWorld-style colony sim in Clojure

A desktop colony simulation on the JVM, rendered with libGDX and driven from a
live REPL. Pawns path around the map, run their own needs and jobs, haul items
to stockpiles, eat when hungry, and the player can build walls that reshape
where everyone can walk. The whole world is one immutable Clojure value
transformed by a pure tick function, so you recompile a namespace and the next
frame uses the new code without losing the running game.

## Run it

```
clj -M:run            # window only
clj -M:repl           # window + a clojure.main REPL on a background thread
```

On macOS, compose the `:mac` alias (it adds the required `-XstartOnFirstThread`,
GLFW must own the main thread there):

```
clj -M:mac:run
clj -M:mac:repl
```

The window boots to a **main menu**. New Colony runs procedural worldgen on a
background thread (a progress screen shows the phase), then drops you into the
play screen with the simulation **paused**. Press space or the in-window button
to start time. Esc opens the pause menu. The window runs on the main thread on
every platform, so closing it exits the process (window-life = process-life).

## REPL workflow (the main way to develop)

`clj -M:repl` opens the window AND gives you a `clojure.main` prompt in the same
terminal (it runs on a daemon thread, since the window owns the main thread).
From that prompt:

```clojure
(in-ns 'user)               ; the helper namespace (loaded for you)

(status)                    ; screen / loop / paused / tick / counts at a glance
(new-colony!)               ; generate a world + seed starter pawns (skips the menu)
(go!)                       ; start the clock + resume ticking
(pause!) (resume!)          ; or press space / click the in-window button
(reset-world!)              ; fresh empty world
(generate-world!)           ; procedural world in place
(spawn-pawn! "Dave" [10 10])
(spawn-item! :wood [12 10])
(tick! 100)                 ; manual step, works even while paused
(debug!)                    ; toggle the path overlay (or press backtick in-window)
(reload-defs!)              ; reload content from resources/defs/*.edn (no ns reload)
(save-game!) (load-game!)   ; Nippy freeze/thaw of @world
(quit!)                     ; close the window = exit the process
```

Re-evaluate any `sim.*` namespace and the next frame/tick uses the new code,
the headline reason this project is in Clojure. Layers, commands, and the HUD
reload live; edits to the clock loop body itself need `(restart!)`.

## In-window controls

- **Left-click** cycles selection through the entities on a tile (pawns, items, trees).
- **Right-click** orders the selected pawn to walk somewhere.
- **Middle-drag** pans, **wheel** zooms, **WASD / arrows** pan.
- **Space** pauses, **Esc** opens the pause menu (or backs out of a tool first).
- **Z** stockpile zoning mode (left-drag paints, shift-drag erases).
- **B** build mode (left-click places one wall, shift-click deconstructs, a hover cursor shows validity).
- **`** (backtick) path overlay, **F1** region overlay, **F2** pathgrid (blocked-cell) overlay.

## What's working

- **Core sim.** Linearized tile grid; pawns with needs and skills; 8-directional
  A* pathfinding (octile heuristic, diagonal x sqrt(2) cost, strict corner rule)
  backed by primitive arrays and a `java.util.PriorityQueue`; a chunked region-graph
  **reachability cache** that rejects doomed paths in O(1). Pawns glide smoothly
  between cells (a render-only interpolation; the sim stays integer-tick).
- **Jobs as data.** `:go-to`, `:haul` (a 4-phase pickup/deliver FSM), and `:eat`,
  dispatched by multimethod. New job types are pure `defmethod` additions.
- **Autonomy.** A data think-tree picks behavior: eat when hungry, else haul a
  loose item to a stockpile, else wander. Needs decay on a staggered band;
  reservations keep two pawns off the same target.
- **Buildings + reachability.** Walls are entities; a derived, memoized **PathGrid**
  (terrain cost merged with building blockers) is what pathfinding and the region
  cache read, so a wall built at runtime reroutes pawns and can wall off a goal.
  Pawns mid-walk replan when a wall lands on their path.
- **Stockpile zoning.** Drag to paint stockpile rectangles; the haul behavior
  delivers loose items into them.
- **Rendering (libGDX, 32px sprites).** A layered compositor draws terrain, zones,
  items, buildings, pawns, a selection box, and gated debug overlays, under one
  SpriteBatch with dual cameras (world + fixed HUD). A tile-inspect panel reads the
  hovered tile.
- **Screens.** Main menu, async worldgen screen, play, and a pause-menu overlay,
  an app state machine that owns the clock above the simulation.
- **Determinism.** A seeded RNG makes same-seed runs bit-identical, all the way
  through worldgen, pathfinding, and colony setup.
- **Save/load.** Nippy freezes `@world` directly; the data structure that runs the
  game is the save file. Derived indexes are stripped and rebuilt on load.

## Architecture (the load-bearing ideas)

- **One `defonce` world atom.** The whole world is one immutable map; the clock
  atomically swaps it with `(sim.simulation/tick world) -> world'` at 30 Hz.
  Survives REPL reloads.
- **Content / state split.** Game content (terrain, materials, needs, thing-defs)
  lives in `sim.defs`, loaded from `resources/defs/*.edn`, validated by spec, never
  in the world map and never saved. State references content by keyword.
- **Scheduler, not a uniform tick.** `sim.schedule` tiers work into bands
  (`:normal` every tick, `:rare` ~4.2 s, `:long` ~33 s) so per-tick cost scales
  with active entities, not total ones.
- **Derived indexes at one chokepoint.** `:kinds` (per-kind id sets) and the
  schedule buckets are rebuilt from `:entities` and maintained only in
  `entity/add-entity` / `remove-entity`. The PathGrid and regions are pure
  projections memoized off those, so they cannot go stale.
- **Render is decoupled from the sim.** The libGDX thread renders off the world
  atom every frame; the clock thread only advances the sim. Pausing freezes
  sim-time, not real-time.

`CLAUDE.md` documents these decisions in depth; `docs/` holds the durable research
(`rimworld-engine-internals.md`, `screens-design.md`, `debug-layer-design.md`).

## Project layout

```
sim/
  deps.edn
  resources/defs/*.edn         game content: terrain, materials, needs, things, graphics
  src/sim/
    core.clj                   entry point (window on main thread; optional REPL thread)
    app.clj                    app state machine above the clock (screen transitions)
    screens/*.clj              main-menu / worldgen / play / pause-menu screens
    clock.clj                  fixed-timestep sim clock (start/stop, pause/resume)
    simulation.clj             the pure tick fn + band-system registration
    schedule.clj               tick-band scheduler + bucket index
    world.clj                  the world atom + initial-state shape
    worldgen.clj               procedural generation
    rng.clj                    deterministic seeded RNG
    tile.clj                   grid representation + terrain lookups
    entity.clj                 entity creation/queries + the :kinds index
    defs.clj                   the immutable content registry (the Def DB)
    pathfinding.clj            8-directional array-backed A*
    pathgrid.clj               derived per-cell cost grid (terrain + building blockers)
    regions.clj                chunked region graph + reachability cache
    job.clj                    job multimethod, haul/eat/go-to, movement core
    think.clj                  the data think-tree (idle behavior selection)
    ai.clj                     job execution + redeliberation
    reservation.clj            derived "who claims target X" queries
    zone.clj                   stockpile zones + drag-rectangle geometry
    command.clj                player commands (the world <-> ui-state bridge)
    input.clj                  mouse/keyboard -> commands
    inspect.clj                pure tile-inspection core
    ui_state.clj               camera + selection + hover + debug flags (view state)
    ui/{hud,inspect_panel}.clj on-screen widgets
    render/gdx.clj             libGDX window + layer compositor
    render/sprites.clj         sprite-sheet loading + region cache
    render/interp.clj          render-time glide interpolation
    render/layers/*.clj        pure draw layers (terrain, items, pawns, buildings, overlays, ...)
    render.clj                 terminal renderer (REPL path-viz only)
    save.clj                   Nippy freeze/thaw of @world
    log.clj                    bounded in-world debug log
  dev/user.clj                 REPL helpers
  test/sim/*                   headless tests (pure cores; GL/input edges untested)
```

## Not yet built

- **Rooms** (enclosure detection over the region graph; the chunked, incremental
  region graph they build on now exists).
- **Construction** as labor: blueprints, frames, material hauling, a `:build` job.
  Walls are placed instantly for now.
- **Doors** (passable-but-special region edges), temperature, a work-priority matrix.
- Tile **animation** (water/fire frames), autotiling for wall edges, a larger HUD font.
- A save/load UI and schema migration (Nippy works; migration is deferred).

## JVM tuning

`:run` and `:repl` enable Generational ZGC for sub-millisecond pauses (it absorbs
persistent-data-structure allocation churn without dropping frames) and grant the
native-access / `sun.misc.Unsafe` permissions libGDX and LWJGL need on a modern JDK.
`:repl` and `:dev` also add criterium and clj-async-profiler for benchmarking.
```
