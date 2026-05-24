# Cross-platform launch (macOS) — design

Status: **approved, not built.** Make the project clone-and-run on macOS
(Apple Silicon and Intel) without losing the terminal-REPL development
workflow, by inverting which thread owns the libGDX window.

## Problem

The project must run on macOS, not just Windows. Three things block that today:

1. **LWJGL natives are Windows-only.** `deps.edn` overrides all six LWJGL
   modules to 3.4.1 but lists only `$natives-windows`. On a Mac there is no
   native to load → crash at startup. (libGDX's own `gdx-platform$natives-desktop`
   already bundles every desktop OS, so only the LWJGL override is the gap.)
2. **The window runs on a spawned thread.** `sim.render.gdx/start!` runs
   `Lwjgl3Application` on a dedicated `sim-gdx` thread, and `sim.core/-main`
   parks the main thread. macOS Cocoa/AppKit requires the windowing + event
   loop to run on the process's **main thread (thread 0)**, launched with
   `-XstartOnFirstThread`. A spawned thread cannot satisfy that, so as written
   *neither* `clj -M:run` *nor* the REPL `(go!)` opens a window on macOS.
3. **No `-XstartOnFirstThread`**, and it is macOS-only — Windows/Linux JVMs
   refuse to boot with it, so it cannot live in shared `:jvm-opts`.

The main-thread requirement is macOS's, not libGDX's: every native macOS
rendering path (Metal, Cocoa GL, GLFW, SDL) inherits it. Switching renderers
does not escape it. The fix is to give the window the main thread.

## Decision

**Unify all platforms on one model:** the libGDX window always runs on the
**main thread**, launched as a dedicated window at startup; the terminal REPL
runs on a **daemon background thread**. Windows, macOS, and Linux behave
identically — one launch path, no OS detection.

This is viable here precisely because the architecture already separates the
**sim clock** (its own thread, `sim.clock`) from **rendering**. Moving the
window to the main thread only changes *where `run-app` is called from* — the
clock, the world atom, jobs, layers, and hot-reload are untouched.

### What is gained / lost

- **Kept:** terminal REPL, live hot-reload (var late-binding re-resolves
  layers/sim/commands every frame regardless of which thread recompiled them),
  and every steering helper (`tick!`, `pause!`, `resume!`, `debug!`, `status`,
  spawns, save/load, log access).
- **Lost:** `(go!)`/`(halt!)` that *spawn and kill the window* from a cold
  REPL. The window is up from launch and closing it ends the process
  (window-life = process-life). This is an unavoidable consequence of "window
  owns the main thread," not a libGDX limitation.

## Design

### §1 — Thread inversion (`sim.core`)

`clj -M:<alias>` hands the `-m` namespace's `-main` the process main thread.
New boot sequence, run on that main thread:

1. Seed the starter world (as today — the window needs a grid to render, so
   both run and dev flavors seed; dev users can `(reset-world!)` after).
2. `clock/start!` — clock thread comes up **paused** (unchanged).
3. *Dev flavor only:* spawn a **daemon background thread** running the terminal
   REPL (see §3).
4. Install the Ctrl-C / shutdown hook (as today).
5. `gdx/run!` — run the libGDX window **on this (main) thread**, blocking until
   the window closes.
6. On window close: `run!` returns → `shutdown-agents` → process exits (the
   daemon REPL thread dies with the JVM).

A single `-main` selects the flavor from its args: no args → run only; the
literal arg `"repl"` → start the background REPL in step 3.

### §2 — `sim.render.gdx`

- Add public **`run!`** — today's private `run-app`, run on the **calling**
  thread (which must be the main thread). It constructs the
  `Lwjgl3Application` (blocking) against `world/world`. This is the core fix.
- **Delete** `start!` and the `app-thread` atom — the spawn-a-thread path is
  now unused.
- **Keep** `stop!` (`(Gdx/app).exit`). It remains callable from the REPL thread
  to close the window (→ `dispose` → process exit). `dispose` still calls
  `clock/stop!` and disposes GL resources.

### §3 — Background REPL (decision: plain `clojure.main/repl`)

The window owns the main thread, so the terminal prompt moves to a daemon
background thread reading the same `System.in`. Chosen flavor: **plain
`clojure.main/repl`** with an `:init` that does `(require 'user)(in-ns 'user)`
— no nREPL server, no port file, no client to connect. This matches the
"I just use the terminal REPL" workflow and is the simplest wiring.

`System.in` is process-wide and readable from any thread; the main thread does
not read stdin (it is in the GL loop), so the background REPL owns it with no
contention. Prompt output interleaves with sim/libGDX stdout in the one
terminal — acceptable and documented.

(Rejected: a background **nREPL server** + client. More machinery, only worth
it for editor connection, which is not wanted. Easy to add later if that
changes.)

### §4 — Launch UX & the macOS flag (`deps.edn`)

`-XstartOnFirstThread` is macOS-only, so it lives in a tiny composable alias
that contributes *only* that one jvm-opt. `clj` concatenates `:jvm-opts` across
composed aliases, keeping the run/dev definitions DRY (no duplication).

| Command | Behavior |
|---|---|
| `clj -M:run` | window only (no REPL) — "the real thing" |
| `clj -M:repl` | window + background terminal REPL — daily driver |
| `clj -M:mac:repl` | same as `:repl`, **+ `-XstartOnFirstThread`** (macOS) |
| `clj -M:mac:run` | window only, on macOS |

Alias changes:

- `:run` — keep `:main-opts ["-m" "sim.core"]`; behavior changes via the
  `-main` rewrite (§1).
- `:repl` — change `:main-opts` from `["-m" "nrepl.cmdline" "--interactive"]`
  to `["-m" "sim.core" "repl"]`. The `nrepl` dependency is no longer used by
  this path and is removed from `:repl`/`:dev` extra-deps. (`criterium` and
  `clj-async-profiler` stay.)
- `:mac` — new: `{:jvm-opts ["-XstartOnFirstThread"]}`.

### §5 — REPL helpers (`dev/user.clj`)

Window-lifecycle helpers lose meaning (window = process now); clock helpers are
unchanged.

- `go!` → repurposed to `start!` + `resume!` + `status` (start the *sim*; no
  window action).
- `halt!` → repurposed to `stop!` (stop the *sim*; window stays).
- `gdx-start!` → **removed**. `gdx-stop!` → renamed `quit!` (closes the window =
  exits the process).
- `restart!` → **unchanged** — it respins the *clock* loop for `run-loop!` /
  `drain-ticks` / tick-rate edits, which has nothing to do with the window.
- All other helpers (`tick!`, `debug!`, `status`, `look-at!`, `zoom!`,
  `snapshot`, `reset-world!`, spawns, save/load, log access, pathfinding viz)
  untouched.

### deps.edn — macOS natives

Add, for **all six** LWJGL modules at 3.4.1, both macOS classifiers:

- `$natives-macos-arm64` (Apple Silicon — most modern Macs)
- `$natives-macos` (Intel Macs)

Extra natives on the classpath are harmless; only the matching platform loads
at runtime. `gdx-platform$natives-desktop` already covers libGDX's own macOS
natives. (Linux natives are a trivial future add but out of scope here.)

### .gitattributes — LF everywhere

Goal: LF (Unix/macOS/Linux) line endings in the repo **and** in every working
tree, including Windows — never CRLF. `eol=lf` is the operative bit: it
overrides the machine's `core.autocrlf` for matched files, so behavior is
identical on every clone without per-machine config.

```gitattributes
# Force LF in the repo AND in every working tree (Windows included)
* text=auto eol=lf

# Binary assets — never touch their bytes
*.png   binary
*.hprof binary
*.npy   binary
```

`32rogues/*.txt` (sprite cell maps) stay text → LF; `32rogues/*.png` are
protected as binary. After adding the file, run `git add --renormalize .` and
commit so existing files (checked out as CRLF under the old setting) flip to
LF, not just future files.

## Files to change

- `src/sim/core.clj` — rewrite `-main`: arg-selected flavor, run window on main
  thread via `gdx/run!`, optional daemon `clojure.main/repl` thread, seed +
  shutdown-agents.
- `src/sim/render/gdx.clj` — add public `run!` (blocking, main-thread); delete
  `start!` and `app-thread`; keep `stop!`/`dispose`.
- `deps.edn` — add macOS LWJGL natives; add `:mac` alias; repoint `:repl`
  main-opts; drop unused `nrepl` dep from `:repl`/`:dev`.
- `dev/user.clj` — repurpose `go!`/`halt!`, remove `gdx-start!`, rename
  `gdx-stop!` → `quit!`; leave the rest.
- `.gitattributes` — new (LF policy above); plus a `git add --renormalize .`
  pass.
- `README.md` / `CLAUDE.md` — update the run/REPL instructions and the
  loop/lifecycle notes to the unified model (README is already stale and will
  be corrected as part of this).

## Testing

- The change is structural (threading/launch/build), not logic, so most of it
  is verified by **running**, not unit tests:
  - `clj -M:run` (Windows) opens a window on the main thread; closing it exits
    the process.
  - `clj -M:repl` (Windows) opens the window *and* gives a working terminal
    REPL on the background thread; `(spawn-pawn! ...)`, `(resume!)`,
    reloading a `sim.*` ns all land live.
  - `clj -M:mac:repl` / `clj -M:mac:run` on macOS hardware — the
    cross-platform acceptance test. **Cannot be run from the Windows dev box;
    requires a Mac.**
- Existing headless unit tests (`sim.simulation-test`, `sim.job-test`,
  `sim.debug-layer-test`, `sim.sprites-test`, `sim.ui-state-test`) must still
  pass via `clj -M:test` — they don't touch windowing, so the launch change
  should not affect them; running them confirms no namespace/require breakage
  from the `gdx`/`core`/`user` edits.

## Known limitations

- **`(inspect)` likely will not work on macOS.** It opens a Swing/AWT tree;
  AWT also demands the macOS main thread, which GLFW now holds. Documented as a
  limitation, not engineered around.
- **Interleaved terminal output** — REPL prompt and sim/libGDX stdout share one
  terminal in the dev flavor. Cosmetic.
- **`(go!)` after `(halt!)` window restart in one session** is no longer a
  concern (it's removed), which also sidesteps the previously-UNVERIFIED
  GLFW-restart-in-same-JVM open question.

## Out of scope

- Linux natives (trivial later add).
- A start-menu / screens app-state machine (separate `docs/screens-design.md`).
- Any rendering/feature work; this is launch + build + line endings only.
