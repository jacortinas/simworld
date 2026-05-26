# Screens & Start Menu — Design Spec

**Date:** 2026-05-26
**Status:** Approved, ready for implementation planning
**Topic:** An app-level state machine above the sim clock — main menu, async worldgen with phase progress, in-game play, and a pause-menu overlay

## Context

Today "the program started" and "you are in a colony (paused)" mean the same
thing. `sim.core/-main` seeds a placeholder world (Alice/Bob/Cleo + a wall
down the middle), starts the sim clock, opens the libGDX window, and lets the
user play. There is no menu and no way to return to one — the only exit is
closing the window.

The relevant current state:

- **`sim.core/-main`** does, in order: `seed-world!` → `clock/start!` →
  `gdx/run!`. The window owns the main thread; the clock runs on a daemon
  background thread; an optional dev REPL runs on another daemon thread.
- **`sim.clock`** has two independent axes: `running?` (thread alive — flipped
  by `start!`/`stop!`) and `paused?` (advancing — flipped by
  `pause!`/`resume!`/`toggle-pause!`). The clock boots paused; the first
  user action is pressing play.
- **`sim.render.gdx/render`** always draws the same content: world layers
  (terrain → flora → items → pawns → optional debug overlay) under a
  pan/zoom world camera, plus the HUD under a fixed UI camera.
- **`sim.input/make-processor`** takes opts including `:on-toggle-pause` and
  `:on-ui-click` as injected lambdas — already dependency-injected to keep
  the input ns decoupled from clock and HUD.
- **`sim.ui-state`** holds `{:camera :selected :debug?}` — pure view-data,
  never serialized; the camera is the source of truth that the Java
  `OrthographicCamera` is synced from each frame.
- **`sim.worldgen/generate`** is a pure synchronous pipeline of named phases
  (terrain phase: `base-pass`; detail phase: `scatter-pass`). It currently
  emits no progress signals.
- **`sim.ui.hud`** owns the bottom status bar; its button (pause/play) is a
  1px-white-texture rect tinted and stretched through the SpriteBatch — the
  established rendering grammar for all screen-space widgets.
- **`docs/screens-design.md`** is the prior architectural sketch this spec
  refines and supersedes. The sketch proposed the keyword + multimethod
  dispatch this spec adopts.

A separate Windows branch — not yet merged — is adding zoning and a world-space
selection frame around selected entities. That work necessarily touches
`sim.ui-state` (selection state) and the input drag-paint surface. **This spec
deliberately avoids both** so the merge stays mechanical.

## Goals

1. **Launching the program lands the user at a main menu**, with the sim
   clock stopped and no world allocated. The menu offers New Colony and Quit.
2. **New Colony generates a fresh world asynchronously**, showing a `:worldgen`
   screen with phase-by-phase text progress while a background thread runs
   `sim.worldgen/generate`. When generation completes, the app transitions to
   the play screen with the clock started (paused) and a small starter party
   of pawns placed on the new map.
3. **Esc during play opens a pause-menu overlay**, freezing the clock. The
   overlay offers Resume, Quit to Menu, and Quit Game. Resume restores the
   clock to its prior pause state. Quit to Menu returns to the main menu with
   the clock stopped and the world cleared.
4. **Closing the window at any point exits the process cleanly** — the
   existing window-life = process-life invariant is preserved.

## Non-goals (YAGNI)

- **Load Colony.** Save/load is out of scope; `sim.save` exists but isn't
  invoked from any screen. Add later alongside save-versioning work.
- **Settings screen.** No audio/video/keybinding configuration yet.
- **General screen-stack framework.** The pause-menu overlay is a *specific*
  composition (pause draws over play) achieved by one screen's draw fn
  delegating to another's. No generic stack data structure, no lifecycle
  hooks.
- **Confirmation prompt before Quit to Menu.** Opening the pause menu IS the
  deliberate intent; a second confirmation would be confirmation fatigue.
- **Hover/focus state on buttons.** Buttons are click-only for v1.
- **Worldgen progress bar.** Phase text is the entire progress UI. A bar
  would require subdividing the inner noise loop, which is the documented
  hotspot.
- **In-game menu button** (a HUD widget that opens the pause menu). Esc is
  the only way to open the pause menu for v1.

## Architecture

### New namespaces

- **`sim.app`** — owns the app-state atom and all transition functions. The
  *only* place that orchestrates `clock/start!`/`clock/stop!`,
  `world/reset-world!`, `worldgen/generate`, and `:screen` swaps in tandem.
- **`sim.screens`** — multimethod dispatch layer. `draw-screen` is called
  from `sim.render.gdx/render` each frame; `make-processor` is built per
  screen at GL-thread startup and stored in a map keyed by `:screen`.
- **`sim.screens.main-menu`** — title + New Colony / Quit buttons.
- **`sim.screens.worldgen`** — phase label + animated dots + Back button on
  failure. Self-driven transition to `:play` when the background future
  resolves.
- **`sim.screens.play`** — the *current* gameplay rendering and input,
  relocated verbatim from `sim.render.gdx/render` and the
  `sim.input/make-processor` call site.
- **`sim.screens.pause-menu`** — overlay: delegates to play's draw, darkens,
  then draws Resume / Quit to Menu / Quit Game.

### Modified namespaces

- **`sim.core/-main`** — drops `seed-world!` and `clock/start!`. Just opens
  the window. App boots at `:main-menu`.
- **`sim.render.gdx/render`** — body shrinks to a clear-screen call plus
  `(screens/draw-screen (app/current-screen) ctx)`. The pre-existing world +
  HUD pipeline moves into `sim.screens.play`.
- **`sim.render.gdx/create`** — builds three input processors at startup
  (main-menu, worldgen, play, pause-menu) and stores them keyed by screen.
  Each transition writes `:screen` to the app atom AND calls
  `setInputProcessor` with the matching processor.
- **`sim.worldgen/generate`** — takes an optional `:on-phase` callback in its
  opts. Each pipeline pass invokes it with a phase keyword (`:terrain`,
  `:detail`). Pure pipeline stays pure; the callback is the only side
  channel.
- **`dev/user.clj`** — REPL helpers gain `(menu!)`, `(new-colony!)`, and
  retain the existing world/pawn-spawn helpers for direct REPL usage that
  bypasses screens.

### Untouched namespaces

`sim.ui-state`, `sim.clock`, `sim.world`, `sim.simulation`, `sim.job`,
`sim.ai`, `sim.entity`, `sim.tile`, every render layer, `sim.command`,
`sim.input` (the processor builder itself — only the call site moves; one new
injected callback is added).

## State shape

```clojure
(defonce app
  (atom {:screen :main-menu                             ; | :worldgen | :play | :pause-menu
         :worldgen   {:status :idle                     ; | :running | :done | :failed
                      :phase  nil                       ; | :terrain | :detail
                      :result nil                       ; the generated world, or nil
                      :error  nil}                      ; a Throwable, or nil
         :pause-menu {:was-paused? false}}))            ; captured on open
```

One atom, three top-level keys. `:screen` is the discriminator the dispatch
layer reads every frame. The sub-maps are only meaningful while their
screen is active — they're the channels by which background work
(`:worldgen` future) and transition-time captures (`:pause-menu` clock
snapshot) communicate with the GL thread.

`defonce` so REPL reloads preserve the atom — same property the world atom
and ui-state rely on.

## Transitions

Six transition functions, all in `sim.app`:

- **`enter-worldgen!`** — guard: ignore if `(:status (:worldgen @app))` is
  already `:running`. Reset `:worldgen` sub-map to
  `{:status :running :phase :terrain :result nil :error nil}`. Spawn
  `(future ...)` that calls `worldgen/generate` with `:on-phase #(swap! app
  assoc-in [:worldgen :phase] %)` and a random seed (`(System/nanoTime)`)
  unless one is supplied. On success, write `{:status :done :result world}`;
  on Throwable, write `{:status :failed :error t}`. Swap `:screen → :worldgen`
  and `setInputProcessor` to worldgen's.
- **`enter-play!`** *(called by the worldgen screen when it observes
  `:status :done`, on the GL thread)* — `reset! world/world` to the
  generated world; `seed-colony!` (place starter pawns); `clock/start!`;
  swap `:screen → :play` and processor. Wrap in `try/catch`; on failure,
  log the Throwable via `*err*` and roll back to `:main-menu` (clock
  stopped, world reset to `(world/initial-world {})`, `:worldgen` sub-map
  reset to `:idle`). The user can retry New Colony.
- **`enter-pause-menu!`** — capture `(clock/paused?*)` into `:pause-menu
  :was-paused?`; `clock/pause!` (idempotent); swap `:screen → :pause-menu`
  and processor.
- **`resume-from-pause-menu!`** — if `:was-paused?` was false, call
  `clock/resume!`; otherwise leave paused. Swap `:screen → :play` and
  processor.
- **`quit-to-menu!`** — `clock/stop!` (joins the clock thread, ~1s max);
  reset `world/world` to an initial empty world; reset `:worldgen` and
  `:pause-menu` sub-maps to defaults; swap `:screen → :main-menu` and
  processor.
- **`quit-game!`** — `Gdx.app.exit`. The window's `dispose` then runs;
  `clock/stop!` is a no-op (clock already stopped from `quit-to-menu!` or
  was never started); main thread falls through to `shutdown-agents` and
  exits.

`seed-colony!` places three pawns with names drawn from a small list on
walkable tiles near the map center. It throws if no walkable tile is
available — caught by `enter-play!` and surfaced via rollback.

## Lifecycle trace

```
1.  -main:                  window opens, app at :main-menu, clock stopped,
                            world is an empty initial state.

2.  user clicks "New Colony" → enter-worldgen!:
      - :worldgen sub-map = {:status :running :phase :terrain ...}
      - future spawned: runs worldgen/generate with :on-phase callback
      - :screen → :worldgen; input processor swapped

3.  every frame on :worldgen screen:
      - reads (:worldgen @app)
      - :status :running → renders phase label + animated dots
      - :status :done    → calls enter-play! (on the GL thread)
      - :status :failed  → renders error + Back button

4.  enter-play! [world']:
      - (reset! world/world world')
      - (seed-colony!)
      - (clock/start!)            ; thread comes up paused
      - :screen → :play; processor swap

5.  user plays. presses Esc:
    enter-pause-menu!:
      - capture (clock/paused?*) → :pause-menu :was-paused?
      - (clock/pause!)
      - :screen → :pause-menu; processor swap

6a. user clicks Resume (or Esc):
    resume-from-pause-menu!:
      - if NOT :was-paused?: (clock/resume!)
      - :screen → :play; processor swap

6b. user clicks Quit to Menu:
    quit-to-menu! → :screen → :main-menu; clock stopped; world cleared

6c. user clicks Quit Game:
    (Gdx.app.exit) → dispose → process exits

7.  closing the window at any step → dispose → clock/stop! (idempotent) →
    main thread falls through → shutdown-agents → exit.
```

## Per-screen components

### `sim.screens.main-menu`

**Draws** (UI cam only — world cam unused, no world to project):

- Full-viewport dark background fill (1px texture).
- Centered title text "sim" via the existing `BitmapFont`.
- Two buttons stacked vertically, horizontally centered: **New Colony**,
  **Quit**. Buttons reuse `sim.ui.hud`'s rendering grammar exactly (border
  rect + inset fill + label).

**Input** — own `InputProcessor`:

- Left-click on **New Colony** rect → `app/enter-worldgen!`.
- Left-click on **Quit** rect → `app/quit-game!`.
- Everything else ignored.

### `sim.screens.worldgen`

**Draws** (UI cam only):

- Same dark background.
- Centered status text derived from `(:worldgen @app)`:
  - `:status :running` → `"Generating world... (terrain)"` or `"... (detail)"`
    using a humanized phase name.
  - `:status :failed` → `"World generation failed: <message>"` plus a **Back**
    button below.
- Animated trailing dots (`.` → `..` → `...` → `.`) on a 300ms wall-clock
  cycle keyed off `System/nanoTime`. This animation runs on **real-time**,
  not sim-time — the clock is stopped, so this is the first concrete use of
  the real-time/sim-time distinction the codebase has been preparing for.
- When `:status :done`, the draw fn itself calls `(app/enter-play! result)`.
  The worldgen thread does *not* trigger the transition; the GL thread acts
  on the result. This keeps `reset! world/world` strictly on the GL thread.

**Input** — own `InputProcessor`:

- Only meaningful when `:status :failed`: Esc or click on **Back** →
  `app/quit-to-menu!`.
- Otherwise: no-op.

### `sim.screens.play`

**Draws** — the *current* `sim.render.gdx/render` body, lifted verbatim:

- World cam: terrain → flora → items → pawns → debug overlay layers.
- UI cam: HUD bar.

**Input** — the *current* `sim.input/make-processor`, called with one
additional injected callback: `:on-open-pause-menu (fn []
(app/enter-pause-menu!))`. The Esc key triggers it. The space-bar
pause-toggle binding is unchanged. The injection-of-lambdas pattern that
`make-processor` already uses is the right shape for this — one more opt
key, no restructuring.

### `sim.screens.pause-menu`

**Draws** — the one place we introduce screen composition:

```clojure
(defmethod draw-screen :pause-menu [_ ctx]
  (draw-screen :play ctx)            ; 1. draw play underneath; clock paused so frozen
  (draw-overlay! ctx)                ; 2. darken viewport: 1px tex tinted (0,0,0,0.55)
  (draw-modal! ctx))                 ; 3. centered modal: title + 3 buttons
```

Modal contents:

- Centered title "Paused".
- Three buttons stacked vertically: **Resume** / **Quit to Menu** / **Quit
  Game**. Same rendering grammar as main-menu buttons.

**Input** — own `InputProcessor`:

- Esc → `app/resume-from-pause-menu!`.
- Left-click on **Resume** rect → `app/resume-from-pause-menu!`.
- Left-click on **Quit to Menu** rect → `app/quit-to-menu!` (no further
  confirmation).
- Left-click on **Quit Game** rect → `app/quit-game!`.
- Clicks on the darkened-overlay area outside the modal rect: **ignored**.
  Clicks do not fall through to play's input.

## Worldgen integration

`sim.worldgen/generate` gains an optional `:on-phase` key in its opts. When
present, each pipeline pass invokes it with a phase keyword *before*
beginning that pass:

```clojure
(defn base-pass [{:keys [opts] :as state}]
  (when-let [cb (:on-phase opts)] (cb :terrain))
  ;; ... existing body ...
  )

(defn scatter-pass [{:keys [opts] :as state}]
  (when-let [cb (:on-phase opts)] (cb :detail))
  ;; ... existing body ...
  )
```

The callback runs on whichever thread `generate` is running on (the worldgen
future, in production; the test thread, in tests). It must be fast and
side-effect-free besides whatever the caller wants to record — for the
production case, a `swap!` on the app atom.

Worldgen stays pure aside from this opt-in callback. Calls without
`:on-phase` (e.g. existing tests, REPL `(worldgen/generate)`) are
unaffected.

`enter-worldgen!` defaults `:seed (System/nanoTime)` so each menu New Colony
produces a fresh map. Explicit `:seed` callers (the existing snapshot test,
REPL reproductions) remain deterministic.

## Error handling

- **Worldgen `future` throws.** The future body wraps `generate` in `try
  (catch Throwable t ...)`. On exception, swap `{:status :failed :error t}`
  into `app`. The `:worldgen` screen renders the error message and a Back
  button. The Throwable is *stored*, not re-thrown — the render loop never
  sees an exception bubble up.
- **Window close during running worldgen.** The future is interrupted by
  `shutdown-agents` in `-main` after `dispose` runs. Any in-flight `swap!`
  is harmless (writes to memory the JVM is about to free).
- **Double-click on New Colony.** `enter-worldgen!` guards on `(:status
  (:worldgen @app))` being `:running` — ignore if already running.
- **`enter-play!` fails** (e.g., `seed-colony!` can't find a walkable
  tile). `try/catch` in `enter-play!` rolls back as described in the
  Transitions section: log via `*err*`, reset world to
  `(world/initial-world {})`, reset `:worldgen` sub-map to `:idle`, swap
  `:screen → :main-menu`. The user lands at the menu and can retry.
- **Clock fails to start.** `clock/start!` is idempotent and returns a
  keyword, never throws. No handling needed.
- **REPL reload during `:play`.** `defonce app` survives. After reload,
  `:screen` is still `:play`, the world atom is still populated, the clock
  is still ticking. Hot-reload through screens works *because* of the
  defonce.
- **REPL reload during `:worldgen`.** The future captured the OLD worldgen
  function var — late-binding does not apply inside `future` bodies.
  Worldgen finishes against pre-reload code; result lands in `app`. This
  is a non-issue in practice (worldgen finishes in <1s).

## Edge cases worth naming

- **Animation clock for worldgen dots uses `System/nanoTime`,** not anything
  tied to the sim clock. The sim clock is stopped during worldgen, so
  coupling animation to it would be wrong. This is the first concrete use
  in the codebase of the long-documented "render runs on real-time, sim on
  sim-time" distinction.
- **Window resize during worldgen.** The existing `resize` callback in
  `gdx` resizes both cameras; UI-cam-only screens recompute centering from
  `Gdx.graphics.getWidth/getHeight` each frame. Resize Just Works.
- **Closing the window from the main menu.** Clock not running, no world
  to save. `dispose` runs, `clock/stop!` is a no-op, main thread exits.
- **Pause menu opened while the game was already manually paused.** The
  `:was-paused?` capture-and-restore pattern is the safety net: Resume
  preserves the user's prior pause intent rather than silently switching
  to running.

## Testing

The codebase has a strong convention: pure data is tested, GL/threading is
not. This spec matches it.

### New / extended tests

- **`test/sim/app_test.clj`** *(new)* — tests for the pure state-update
  logic. The orchestrating `enter-*!` functions are split:

  ```clojure
  (defn next-app-state [app event & args]  ; pure
    ; computes new app value for events :enter-worldgen, :phase,
    ; :worldgen-done, :worldgen-failed, :enter-play, :enter-pause-menu,
    ; :resume-from-pause-menu, :quit-to-menu
    )
  ```

  Tests assert: the screen state machine transitions correctly through
  `:main-menu → :worldgen → :play → :pause-menu → :play → :main-menu`; the
  `:failed` branch; the re-entry guard on `:worldgen`; that `:phase`
  updates do not change `:screen`; the `:was-paused?` capture/restore for
  both prior states (running and already-paused).

- **`test/sim/screens_test.clj`** *(new)* — multimethod dispatch tests
  (each `:screen` keyword resolves to the right draw fn / processor
  builder); pure button hit-test geometry for main-menu, worldgen, and
  pause-menu rects (mirrors `sim.ui.hud`'s implicit hit pattern);
  composition test verifying `(draw-screen :pause-menu ctx)` invokes
  `(draw-screen :play ctx)` via a recording mock.

- **`test/sim/worldgen_test.clj`** *(extension)* — add a test that calls
  `(generate (assoc default-opts :on-phase #(swap! seen conj %)))` and
  asserts `@seen` contains `[:terrain :detail]` in order. Validates the
  callback contract without any rendering.

### Out of test scope (unchanged from project convention)

- GL drawing paths. No layer in the codebase has GL tests; we don't break
  the pattern.
- `Lwjgl3Application` startup or `setInputProcessor` swapping.
- Real clock-thread / worldgen-future interactions. The pure state-machine
  tests cover the *logic* of transitions; the *threading* of those
  transitions is taken on faith, with the architectural invariant
  "only the GL thread mutates `world/world`" being load-bearing rather
  than tested.

## Acceptance criteria

Working v1 means:

1. `clj -M:mac:run` opens the window at the main menu, clock stopped, no
   world allocated.
2. Click **New Colony** → `:worldgen` screen shows phase progression
   (`terrain` → `detail`) with animated dots → automatic transition to
   `:play`.
3. Play screen behaves identically to today's behavior — pawns, sprites,
   HUD, pause button, debug overlay, mouse/keyboard commands all work.
4. Press **Esc** during play → pause menu opens. The world view is frozen
   underneath, darkened. Modal shows Resume / Quit to Menu / Quit Game.
5. Click **Resume** (or press Esc again) → pause menu closes. If the user
   had manually paused before opening the menu, the world stays paused; if
   the world was running, it resumes.
6. Click **Quit to Menu** from the pause menu → returns to main menu, clock
   stopped, world cleared.
7. Click **Quit Game** at any point on either menu → window closes, process
   exits cleanly.
8. Closing the window directly at any point → same clean exit.
9. New Colony pressed twice in a session produces *different* worlds
   (random seed by default).
10. All existing tests pass (no regressions). New tests in `sim.app_test`,
    `sim.screens_test`, and the worldgen extension all pass.

## Out of scope / future

- **Load Colony** (`sim.save` integration) once save-versioning is solid.
- **Settings screen** for audio/video/keybindings.
- **In-game pause-menu button** in the HUD (today's HUD only has the
  pause/play button; a separate menu-open button is a small future
  addition).
- **General screen-stack framework** — not yet needed; the pause-menu
  overlay's `(draw-screen :play ctx)` delegation is the entire stacking
  feature for now.
- **Worldgen progress bar** — would require subdividing the noise loop;
  not warranted at current map sizes.
- **Confirmation prompt on Quit to Menu** — once saves exist, this becomes
  meaningful.
- **Hover / focus state on buttons** — buttons are click-only for v1.
- **Pop-up menus on selected entities** — separate from screens, will read
  the selection substrate the parallel zoning branch establishes.
