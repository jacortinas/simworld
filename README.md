# sim — a RimWorld-style colony sim in Clojure

A desktop colony simulation built on the JVM. Currently a console-output skeleton; the architecture is set up to grow into a real renderer (Quil, then play-cljc or raw LWJGL3) without re-shaping the simulation core.

## Run it

```
clj -M:run
```

You should see a tick counter and an ASCII map redraw a few times per second.

## REPL workflow (the main way to develop)

```
clj -M:repl
```

Then from your editor / REPL client:

```clojure
(require 'user)
(in-ns 'user)

(start!)        ; start the game loop
(snapshot)      ; peek at the current world
(stop!)         ; stop the loop
(reset-world!)  ; reset to a fresh world

(spawn-pawn! [10 10])  ; add a pawn at a tile
```

You can re-evaluate any namespace (e.g. `sim.simulation`) while the game is running and the next tick uses the new code. This is the headline reason the project is in Clojure.

## Project layout

```
sim/
  deps.edn
  README.md
  src/sim/
    core.clj         entry point — boots the loop, wires shutdown hooks
    game_loop.clj    fixed-timestep game loop (sim + render decoupled)
    world.clj        the world atom + initial-state constructor
    tile.clj         tile types, terrain definitions, grid access
    entity.clj       pawns/entities — creation, queries, updates
    simulation.clj   (defn tick [world] new-world)  pure transformation
    pathfinding.clj  A* — naive map version now, primitive-array version later
    ai.clj           pawn decision-making — stub for behavior trees / rules
    events.clj       event queue + multimethod handlers
    render.clj       console renderer (ASCII grid + status line)
    save.clj         nippy freeze/thaw of @world
  dev/
    user.clj         REPL helpers — start!, stop!, reset-world!, spawn-pawn!
  test/sim/
    simulation_test.clj
```

## Design notes

- **One `defonce` atom** holds the entire world. Pure tick function transforms it.
- **Sim and render are decoupled.** Sim at fixed 30 Hz; render off `@world` snapshot.
- **`*warn-on-reflection* true`** at the top of every namespace. Reflection warnings are bugs.
- **Spatial data uses persistent maps for now** (fine for prototyping); migrating to `long-array`-backed grids when profiling shows the bottleneck.
- **Saves use Nippy.** Same data structure that runs the game becomes the save file — no DTOs.

## What's not here yet

The skeleton is deliberately minimal. The following are stubs with the right shape but trivial implementations:

- A* pathfinding (naive implementation; switch to primitive arrays when slow)
- Behavior tree / rules engine for pawn AI (placeholder decide-fn)
- Job system (no jobs yet; pawns idle)
- Save versioning (nippy works; schema migration is TODO)

## JVM tuning

The `:run` and `:repl` aliases enable Generational ZGC with sub-millisecond pause times. This is the right default for any Clojure simulation — it absorbs persistent-data-structure allocation churn without dropping frames.
