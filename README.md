# sim — a RimWorld-style colony sim in Clojure

A desktop colony simulation built on the JVM. Currently a console-output skeleton; the architecture is set up to grow into a real renderer (Quil, then play-cljc or raw LWJGL3) without re-shaping the simulation core.

## Run it

```
clj -M:run            # window only
clj -M:repl           # window + a terminal REPL (the main way to develop)
```

On macOS, compose the `:mac` alias (adds the required `-XstartOnFirstThread`):

```
clj -M:mac:run
clj -M:mac:repl
```

A window opens (paused — press play or space). The window runs on the main
thread on every platform; closing it exits the process.

## REPL workflow (the main way to develop)

`clj -M:repl` opens the window AND gives you a `clojure.main` REPL prompt in
the same terminal (it runs on a background thread, since the window owns the
main thread). From that prompt:

```clojure
(in-ns 'user)           ; the helper namespace (loaded for you)

(status)                ; loop/paused/tick/counts at a glance
(go!)                   ; start the engine + resume ticking
(pause!) (resume!)      ; or click the in-window button / press space
(reset-world!)          ; fresh world
(spawn-pawn! "Dave" [10 10])
(tick! 100)             ; manual step
(debug!)                ; toggle the path overlay (or press backtick)
(quit!)                 ; close the window = exit the process
```

Re-evaluate any `sim.*` namespace and the next frame/tick uses the new code —
the headline reason this project is in Clojure. (Edits to the clock loop body
itself need `(restart!)`.)

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
