# Cross-platform (macOS) Launch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the project clone-and-run on macOS (Apple Silicon + Intel) by running the libGDX window on the main thread on all platforms, with the terminal REPL on a daemon background thread — without losing the REPL-driven dev workflow.

**Architecture:** Invert thread ownership. `sim.core/-main` (always on the process main thread) seeds the world, starts the sim clock, optionally spawns a `clojure.main/repl` on a daemon background thread, then runs the libGDX window on the main thread (blocking until close). The sim clock already runs on its own thread, so this touches only *where the window is launched from*. macOS's `-XstartOnFirstThread` is supplied by a small composable `:mac` alias.

**Tech Stack:** Clojure 1.12, libGDX 1.13.1 + LWJGL 3.4.1 (lwjgl3 backend), tools.deps aliases, git attributes.

**Test strategy:** This is structural/launch plumbing with no meaningful pure logic to unit-test. Verification = (a) the existing headless suite still passes (`clj -M:test`), (b) a headless compile-check that the edited namespace graph loads, and (c) manual run of `clj -M:run` / `clj -M:repl` on Windows. The true macOS acceptance run (`clj -M:mac:repl`) **requires Mac hardware and cannot be done from the Windows dev box** — it is called out as a deferred manual check.

**Windows launcher note:** Per CLAUDE.md, `clj` may be a PowerShell module rather than a PATH binary; if `clj` is not found, use the `clojure` / `deps` shim (deps.clj). Commands below are written as `clj -M:...`; substitute the working launcher.

---

## File Structure

- `.gitattributes` — **new.** Repo-wide LF line-ending policy + binary protection.
- `deps.edn` — **modify.** Add macOS LWJGL natives; add `:mac` alias; repoint `:repl` main-opts to `sim.core`; drop the now-unused `nrepl` dep from `:repl`/`:dev`.
- `src/sim/render/gdx.clj` — **modify.** Add public `run!` (blocking, main-thread); delete `start!` and the `app-thread` atom; keep `stop!`/`dispose`.
- `src/sim/core.clj` — **modify.** Rewrite `-main`: arg-selected flavor, optional background `clojure.main/repl`, run the window on the main thread via `gdx/run!`.
- `dev/user.clj` — **modify.** Repurpose `go!`/`halt!`; remove `gdx-start!`; rename `gdx-stop!` → `quit!`; leave everything else.
- `README.md`, `CLAUDE.md` — **modify.** Update run/REPL instructions + lifecycle notes to the unified model.

Tasks 3–5 (gdx + core + user.clj + the `:repl` repoint) are mutually dependent — removing `gdx/start!` breaks the old `-main` and the old `user` helpers — so they are grouped into **one task with one commit** (Task 3) to keep every commit's entry point compilable.

---

### Task 1: LF line-ending policy (`.gitattributes`)

**Files:**
- Create: `.gitattributes`

- [ ] **Step 1: Create `.gitattributes`**

```gitattributes
# Force LF in the repo AND in every working tree (Windows included).
# eol=lf overrides each machine's core.autocrlf, so every clone is identical.
* text=auto eol=lf

# Binary assets — never touch their bytes (line-ending conversion would corrupt them).
*.png   binary
*.hprof binary
*.npy   binary
```

- [ ] **Step 2: Renormalize existing files to the new policy**

Run:
```bash
git add --renormalize .
git status --short
```
Expected: either some text files re-staged as modified (their working-tree CRLF normalized to LF), or nothing (repo was already LF under the old `autocrlf=true` — fine; the attribute still governs future checkouts/clones). The `.png` files must NOT appear as modified.

- [ ] **Step 3: Commit**

```bash
git add .gitattributes
git commit -m "build: enforce LF line endings repo-wide via .gitattributes"
```

---

### Task 2: macOS natives + `:mac` alias (`deps.edn`)

These changes are additive/safe and do not alter Windows behavior. The `:repl` main-opts repoint is deliberately deferred to Task 3 (it depends on the `-main` rewrite).

**Files:**
- Modify: `deps.edn` (the LWJGL `:deps` block; add a new `:mac` alias)

- [ ] **Step 1: Add macOS native classifiers for all six LWJGL modules**

Replace the LWJGL override block (currently the six modules with only `$natives-windows`) so each module also lists both macOS classifiers. The full replacement block:

```clojure
         ;; --- LWJGL override ---
         ;; libGDX 1.13.1 pins LWJGL 3.3.3, which predates JDK 24's JNI
         ;; version and emits "[LWJGL] Unsupported JNI version detected".
         ;; 3.4.1 recognizes it. ALL modules must share ONE version —
         ;; mixing 3.3.x and 3.4.x crashes at runtime. We list natives for
         ;; every platform we ship to (windows + macOS intel + macOS arm64);
         ;; only the matching one loads at runtime, the rest are inert.
         org.lwjgl/lwjgl                              {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl$natives-windows              {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl$natives-macos                {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl$natives-macos-arm64          {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-glfw                         {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-glfw$natives-windows         {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-glfw$natives-macos           {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-glfw$natives-macos-arm64     {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-jemalloc                     {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-jemalloc$natives-windows     {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-jemalloc$natives-macos       {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-jemalloc$natives-macos-arm64 {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-openal                       {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-openal$natives-windows       {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-openal$natives-macos         {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-openal$natives-macos-arm64   {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-opengl                       {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-opengl$natives-windows       {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-opengl$natives-macos         {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-opengl$natives-macos-arm64   {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-stb                          {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-stb$natives-windows          {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-stb$natives-macos            {:mvn/version "3.4.1"}
         org.lwjgl/lwjgl-stb$natives-macos-arm64      {:mvn/version "3.4.1"}}
```

- [ ] **Step 2: Add the `:mac` alias**

Inside `:aliases`, add this alias (e.g. right after `:run`). It contributes ONLY the macOS-only JVM flag; `clj` concatenates `:jvm-opts` across composed aliases, so `clj -M:mac:repl` adds it to whatever `:repl`/`:run` already supply. Do NOT put this flag in shared `:jvm-opts` — Windows/Linux JVMs refuse to boot with it.

```clojure
  ;; clj -M:mac:run / clj -M:mac:repl — macOS only.
  ;; -XstartOnFirstThread is REQUIRED on macOS (GLFW must own the main
  ;; thread) and is unrecognized on Windows/Linux, so it lives here and is
  ;; opted in by composing the alias.
  :mac
  {:jvm-opts ["-XstartOnFirstThread"]}
```

- [ ] **Step 3: Verify deps resolve (downloads the macOS native jars)**

Run:
```bash
clj -P -M:run
```
Expected: completes with no error (downloads/caches the new native jars). `-P` prepares the classpath without running.

- [ ] **Step 4: Commit**

```bash
git add deps.edn
git commit -m "build: add macOS LWJGL natives and :mac (-XstartOnFirstThread) alias"
```

---

### Task 3: Thread inversion — window on main thread, REPL on background thread

One atomic change across four files (gdx, core, user.clj, the `:repl` repoint), committed together so no intermediate commit has a broken entry point.

**Files:**
- Modify: `src/sim/render/gdx.clj` (add `run!`; remove `start!` + `app-thread`)
- Modify: `src/sim/core.clj` (rewrite `-main`)
- Modify: `dev/user.clj` (repurpose helpers)
- Modify: `deps.edn` (`:repl` main-opts + drop `nrepl`)

- [ ] **Step 1: `gdx.clj` — replace the private `run-app` with a public `run!`**

Replace the `run-app` defn (the private blocking launcher) with this public `run!`:

```clojure
(defn run!
  "Open the libGDX window and run it ON THE CALLING THREAD, which MUST be the
   process main thread (a hard macOS requirement, and now uniform on every
   platform). Blocks until the window closes. `sim.core/-main` calls this on
   the main thread; `dispose` (window close) stops the sim clock."
  ([] (run! world/world))
  ([world-atom]
   (let [cfg (doto (Lwjgl3ApplicationConfiguration.)
               (.setTitle "sim")
               (.setWindowedMode 800 600)
               (.useVsync true)
               (.setForegroundFPS 60))]
     (Lwjgl3Application. (make-listener world-atom) cfg))))
```

- [ ] **Step 2: `gdx.clj` — delete the spawn-thread path**

Delete the `app-thread` atom declaration:
```clojure
(defonce ^:private app-thread (atom nil))
```
and delete the entire `start!` defn (the one that spawned the `"sim-gdx"` daemon thread). Leave `stop!` exactly as it is.

- [ ] **Step 3: `core.clj` — rewrite the namespace + `-main`**

Replace the whole file body (ns form, `seed-world!`, `-main`) with:

```clojure
(ns sim.core
  "Application entry point.

   The libGDX window runs on the MAIN thread (a hard macOS requirement, now
   uniform on all platforms). The simulation clock runs on its own thread.
   The optional dev REPL runs on a daemon background thread.

     clj -M:run        -> window only.
     clj -M:repl       -> window + a clojure.main REPL on a background thread
                          (the terminal you type into).
     clj -M:mac:run    -> as :run,  plus -XstartOnFirstThread (macOS).
     clj -M:mac:repl   -> as :repl, plus -XstartOnFirstThread (macOS).

   Closing the window ends the process (window-life = process-life)."
  (:require
   [clojure.main   :as main]
   [sim.clock      :as clock]
   [sim.render.gdx :as gdx]
   [sim.tile       :as tile]
   [sim.world      :as world])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- seed-world!
  "Reset the world to a small starter map with a few obstacles and pawns."
  []
  (world/reset-world! {:width 40 :height 20 :seed 42})
  ;; A simple wall down the middle so pathfinding has something to do.
  (swap! world/world
         (fn [w]
           (reduce (fn [w y] (update w :grid tile/set-tile 20 y :wall))
                   w
                   (range 5 15))))
  ;; A couple of pawns to look at.
  (world/spawn-pawn! "Alice" [5 5])
  (world/spawn-pawn! "Bob"   [10 8])
  (world/spawn-pawn! "Cleo"  [30 12]))

(defn- start-repl-thread!
  "Start a clojure.main REPL on a daemon background thread. The window owns
   the main thread, so the terminal prompt lives here instead. Reads the same
   stdin; switches into the `user` helper namespace before prompting."
  []
  (doto (Thread.
         ^Runnable (fn []
                     (main/repl :init (fn [] (require 'user) (in-ns 'user))))
         "sim-repl")
    (.setDaemon true)
    (.start)))

(defn -main
  [& args]
  (println "[sim] booting...")
  (seed-world!)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
            (fn []
              (println "\n[sim] shutting down...")
              (clock/stop!))))
  (clock/start!)                       ; engine thread comes up paused
  (when (some #{"repl"} args)
    (start-repl-thread!))              ; dev flavor: terminal REPL on a side thread
  ;; Run the window on THIS (main) thread. Blocks until the window closes.
  (gdx/run!)
  ;; Window closed -> fall through. Shut the agent pool so the JVM exits
  ;; promptly instead of lingering ~60s on the non-daemon future pool.
  (shutdown-agents)
  (println "[sim] bye."))
```

- [ ] **Step 4: `user.clj` — repurpose the window-lifecycle helpers**

In `dev/user.clj`, replace the `go!`, `halt!`, `gdx-start!`, and `gdx-stop!` defns. `restart!` stays exactly as it is (it respins the clock loop, unrelated to the window). Replace:

```clojure
(defn go!
  "Start the SIM running: bring the clock up (idempotent) and resume ticking,
   then print status. The window is already open (it owns the main thread and
   launches with the process), so this no longer touches the window."
  []
  (clock/start!)
  (clock/resume!)
  (status)
  :go)

(defn halt!
  "Stop the SIM clock (pawns/jobs/needs freeze). The window stays open and
   keeps rendering. Use (quit!) to actually close the window / exit."
  []
  (clock/stop!)
  :halted)

(defn quit!
  "Close the libGDX window, which ends the process (window-life = process-life
   under the unified main-thread model)."
  []
  (gdx/stop!))
```

That is: `go!` loses its `(gdx/start!)` line, `halt!` loses its `(gdx/stop!)` line, `gdx-start!` is removed entirely, and `gdx-stop!` is renamed to `quit!`. Also update the `(comment ...)` block at the bottom — change the `(go!)` line's trailing note from "open window + start engine + resume" to "start engine + resume (window is already open)".

- [ ] **Step 5: `deps.edn` — repoint `:repl` and drop the unused `nrepl` dep**

Change the `:repl` alias `:main-opts` from:
```clojure
   :main-opts   ["-m" "nrepl.cmdline" "--interactive"]
```
to:
```clojure
   :main-opts   ["-m" "sim.core" "repl"]
```
Then remove the `nrepl/nrepl {:mvn/version "1.3.0"}` line from BOTH the `:dev` and `:repl` `:extra-deps` maps (it is no longer used by any launch path; `criterium` and `clj-async-profiler` stay). Leave the `:repl` `:jvm-opts` unchanged.

- [ ] **Step 6: Headless compile-check of the edited namespace graph**

Run (loads the edited namespaces without opening a window — `gdx/run!` is never called):
```bash
clj -M:dev -e "(require 'sim.core) (require 'user) (println :compiles-ok)"
```
Expected: prints `:compiles-ok` with no compile/reflection errors and no unresolved-var errors (confirms `gdx/start!` removal didn't orphan a caller).

- [ ] **Step 7: Run the headless regression suite**

Run:
```bash
clj -M:test
```
Expected: all existing tests pass (`sim.simulation-test`, `sim.job-test`, `sim.debug-layer-test`, `sim.sprites-test`, `sim.ui-state-test`). They don't touch windowing, so this confirms the edits broke no namespaces they depend on.

- [ ] **Step 8: Commit**

```bash
git add src/sim/render/gdx.clj src/sim/core.clj dev/user.clj deps.edn
git commit -m "feat: run window on main thread; terminal REPL on background thread

Unifies all platforms on a window-owns-main-thread model so the project runs
on macOS. gdx/run! replaces the spawn-thread start!; -main runs the window on
the main thread and (for :repl) starts a clojure.main REPL on a daemon thread.
:repl now launches sim.core; unused nrepl dep dropped."
```

---

### Task 4: Documentation (`README.md`, `CLAUDE.md`)

**Files:**
- Modify: `README.md` ("Run it" + "REPL workflow" sections)
- Modify: `CLAUDE.md` ("## REPL workflow" section)

- [ ] **Step 1: README — replace the "Run it" and "REPL workflow (the main way to develop)" sections**

Replace both sections with:

```markdown
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
```

- [ ] **Step 2: CLAUDE.md — replace the "## REPL workflow" section**

Replace the existing "## REPL workflow" section (its boot line, the example block, and the `go!`/`halt!`/`restart!` notes) with:

```markdown
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
(tick! 100)                 ;; manual step — works even while paused
(quit!)                     ;; close the window = exit the process
```

Window-life = process-life: the window launches with the process and closing
it (or `(quit!)`) exits. So there is no `(go!)`/`(halt!)` window spawn/kill —
`go!` now just starts+resumes the sim clock, `halt!` stops it (window stays).

After editing source: `(require 'user :reload-all)` — preserves the world
atom via `defonce`. Layers, commands, and the HUD reload live. **But edits to
`run-loop!` itself need `(restart!)`** — the running clock thread holds the
old compiled loop body until `start!` respawns it.

`clj -M:run` is "run only" (no REPL). Closing the window exits the process.
```

- [ ] **Step 3: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: update run/REPL instructions for unified main-thread launch"
```

---

### Task 5: Final verification + macOS handoff note

**Files:** none (verification only)

- [ ] **Step 1: Manual Windows run — `:run` flavor**

Run `clj -M:run`. Expected: console prints `[sim] booting...`; a window titled "sim" opens (paused). Press space / play → pawns start moving. Close the window → console prints `[sim] bye.` and the process exits within ~1s (no ~60s hang).

- [ ] **Step 2: Manual Windows run — `:repl` flavor**

Run `clj -M:repl`. Expected: the window opens AND a REPL prompt appears in the terminal. At the prompt verify the inverted workflow works:
```clojure
(in-ns 'user)
(status)                 ; prints loop/paused/tick/counts
(go!)                    ; pawns begin ticking
(spawn-pawn! "Z" [2 2])  ; a new pawn appears in the window
(debug!)                 ; path overlay toggles in the window
```
Then close the window → process exits.

- [ ] **Step 3: Confirm the headless suite is still green**

Run `clj -M:test`. Expected: all tests pass (regression gate after the doc/edit churn).

- [ ] **Step 4: Record the deferred macOS acceptance check**

The actual macOS run cannot be done from the Windows dev box. On a Mac, after `git clone`, the acceptance test is:
```
clj -M:mac:repl
```
Expected on Mac: window opens (no `-XstartOnFirstThread` / GLFW main-thread crash), terminal REPL works, hot-reload works. Note in the final report that this step is **pending Mac hardware**, and that `(inspect)` (Swing/AWT) is a known non-working helper on macOS.

---

## Self-Review

**1. Spec coverage:**
- §1 thread inversion → Task 3 Step 3 (`-main`). ✓
- §2 gdx `run!` / delete `start!`+`app-thread` / keep `stop!` → Task 3 Steps 1–2. ✓
- §3 plain `clojure.main/repl` on daemon thread → Task 3 Step 3 (`start-repl-thread!`). ✓
- §4 `:mac` alias + launch table + `:repl` repoint + drop nrepl → Task 2 (alias/natives) + Task 3 Step 5 (`:repl`/nrepl). ✓
- §5 helper fate (`go!`/`halt!`/`gdx-start!`/`gdx-stop!`→`quit!`/`restart!` kept) → Task 3 Step 4. ✓
- deps macOS natives → Task 2 Step 1. ✓
- `.gitattributes` LF + renormalize → Task 1. ✓
- Docs update → Task 4. ✓
- Testing (headless suite + run checks + deferred Mac) → Task 3 Steps 6–7, Task 5. ✓
- Known limitations (`inspect` on Mac) → Task 5 Step 4 note. ✓

**2. Placeholder scan:** No TBD/TODO/"handle edge cases"/"similar to". Every code/edit step shows the actual content. ✓

**3. Type/name consistency:** `gdx/run!` (defined Task 3 Step 1) is the exact symbol called in `-main` (Step 3) and the README/CLAUDE docs. `quit!` (Step 4) calls `gdx/stop!` (kept, Step 2). `start-repl-thread!` defined and called within the same Step 3 block. `:mac` alias name consistent across deps.edn, README, CLAUDE, and verification commands. ✓
