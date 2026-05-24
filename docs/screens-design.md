# Screen state machine — design sketch

Status: **design only, not built.** This is where a start menu (new / load
colony) slots in later, on top of the existing `sim.clock` + `sim.render.gdx`.

## The problem it solves

Today "program started" == "you're in a colony (paused)". With a menu that's
wrong: launching the program should land you in a **menu**, with *no
simulation running*, until you choose New or Load. So we need an **app state
machine** that sits ABOVE the sim clock.

## Core idea: screens

A **screen** = one top-level app state that decides, for the frame loop, *what
to draw* and *how input is handled*. The clock is a subsystem the Play screen
owns — other screens simply don't run it.

```
 launch ──► MainMenu          (clock STOPPED; no/blank world)
              │  New colony ──► WorldGen ──► Play
              │  Load colony ─► (load file) ─► Play
 Play:  clock running (pausable); world + HUD drawn
              └─ Quit to menu ─► (clock/stop!) ─► MainMenu
```

Minimum set to start: `:main-menu` and `:play`. Add `:worldgen` /
`:loading` when those steps need their own screen (e.g. a progress bar).

## State: one keyword in an atom

Keep it dead simple — no framework. Add an app-state atom (either a new
`sim.app` ns or a `:screen` key in `sim.ui-state`):

```clojure
(defonce app (atom {:screen :main-menu}))
```

The render/frame loop dispatches on `(:screen @app)`. A multimethod keyed on
the screen keyword is the natural fit (mirrors `job/advance`):

```clojure
(defmulti draw-screen  (fn [screen _ctx] screen))
(defmulti screen-input (fn [screen _ctx] screen))   ; or per-screen processors
```

## Transitions own the side effects

Transitions are the ONLY place that starts/stops the clock or touches the
world wholesale. They're the menu's verbs:

```clojure
(defn enter-play! [world']         ; from New or Load
  (reset! world/world world')
  (clock/start!)                   ; clock begins (boots paused, press play)
  (swap! app assoc :screen :play))

(defn quit-to-menu! []
  (clock/stop!)                    ; clock down; render keeps running
  (swap! app assoc :screen :main-menu))
```

So `clock/start!` / `clock/stop!` become things **screen transitions** call —
not `-main`, not the user directly.

## How current code slots in (the refactor, when we do it)

- **`sim.render.gdx` `render()`** — currently always draws world layers + HUD.
  Becomes: dispatch on `(:screen @app)`. `:play` → the current world layers +
  HUD; `:main-menu` → menu layout. Both still draw under the UI cam for menu
  chrome; only `:play` uses the world cam.
- **`sim.input`** — currently one processor (world commands + pause). Becomes
  per-screen: swap `setInputProcessor` on transition, or one processor that
  dispatches on screen. Menu buttons reuse the **`sim.ui.hud` 1px-texture
  button** pattern — a menu is just more HUD widgets in screen space.
- **`sim.core/-main`** — stops calling `clock/start!`. It opens the window and
  leaves `app` at `:main-menu` (clock stopped). The window still "is" the
  process; closing it still exits.
- **World** — MainMenu holds a blank/absent world; New colony runs worldgen to
  build one before `enter-play!`; Load reads a save.

## sim-time vs real-time becomes concrete here

MainMenu, a worldgen progress bar, menu hover/animation, camera tweens — all
run on the **render/frame loop (real-time)** while the **clock is stopped**.
This is the first place "render runs even when the sim doesn't" stops being
theoretical, so keep any menu/animation timing off the clock.

## YAGNI notes

- Don't build a general Screen framework with lifecycle hooks up front. Start
  with the keyword + multimethod dispatch + two transition fns.
- Worldgen gets its own screen only once it's slow enough to want a progress
  view; until then New colony can generate synchronously inside the transition.
- A screen *stack* (overlays like a pause menu over Play) is a later upgrade;
  a single current-screen keyword is enough first.
