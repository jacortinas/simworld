# Tile Inspect + Entity Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a RimWorld-style bottom-right hover inspect panel plus left-click-to-cycle entity selection with a world-space selection box.

**Architecture:** A new pure, headless-tested `sim.inspect` core (`describe-tile`, `selectable-at`) backs two dumb GL renderers — a fixed `sim.ui.inspect-panel` (UI camera) and a world-space `sim.render.layers.selection` box. This mirrors the established `debug-layer` split (pure geometry + untested `draw`). `sim.ui-state` gains a `:hover` field; `sim.input` feeds it from `mouseMoved`; `sim.command/left-click!` becomes a cycle-select over `selectable-at`.

**Tech Stack:** Clojure 1.12 on the JVM, libGDX 1.13.1 (SpriteBatch + BitmapFont + 1px pixel texture), cognitect test-runner (`clj -M:test`).

---

## Conventions for every task

- **Run tests via PowerShell**, not Bash — `clj` is a PowerShell module function on this machine. A single namespace: `clj -M:test -n sim.<ns>-test`. Everything: `clj -M:test`.
- **Source order matters at cold start** (`clj -M:test` compiles top-to-bottom in one pass): define a fn *before* its callers within a file.
- **Windows console is CP-1252** — keep any stdout strings ASCII (`...`, not `…`). The inspect panel strings are drawn by libGDX (not stdout), so the literal `"..."` truncation marker is fine.
- After multi-file edits in a live REPL: `(require 'user :reload-all)`. The `make-processor` proxy body and the `gdx` compositor are **captured once at window create** — edits there need a fresh launch (`clj -M:run`), not a reload. This affects Tasks 3 and 9.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `src/sim/inspect.clj` | create | PURE: `selectable-kinds`, `selectable-at`, `describe-tile` + private `terrain-line`/`entity-label`/`truncate`. |
| `test/sim/inspect_test.clj` | create | Headless tests for the above. |
| `src/sim/ui_state.clj` | modify | Add `:hover` field + `hover`/`set-hover!`. |
| `test/sim/ui_state_test.clj` | modify | Add hover round-trip test. |
| `src/sim/input.clj` | modify | Override `mouseMoved` → `ui/set-hover!`. |
| `src/sim/ui/inspect_panel.clj` | create | GL: draw the bottom-right concept-line pane. Untested. |
| `src/sim/command.clj` | modify | `left-click!` → cycle selection over `selectable-at`. |
| `test/sim/command_test.clj` | create | Cycle-select logic over the two atoms. |
| `src/sim/render/layers/selection.clj` | create | Pure `selection-box-rects` + untested GL `draw`. |
| `test/sim/selection_layer_test.clj` | create | `selection-box-rects` geometry. |
| `src/sim/render/layers/pawns.clj` | modify | Drop `selected-tint` + `selected-id` arg. |
| `src/sim/render/gdx.clj` | modify | Add inspect-panel (UI block) + selection layer (world block); update pawns call. |

---

# PLAN A — Hover Inspect Panel

End state: hovering a tile shows a bottom-right concept-line panel (terrain line + one line per selectable entity); off-map hover shows nothing.

## Task 1: `sim.inspect` pure core

**Files:**
- Create: `src/sim/inspect.clj`
- Test: `test/sim/inspect_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/sim/inspect_test.clj`:

```clojure
(ns sim.inspect-test
  "Pure inspect logic — no GL. Builds tiny worlds from sim.tile/sim.entity
   and asserts the concept-line strings and selectable filtering."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.tile    :as tile]
   [sim.inspect :as inspect]))

;; A 5x5 grass grid with a few terrain pokes; entities added per-test.
(defn- world-with
  "Build a world whose :grid is 5x5 grass with `terrain-pokes` ([x y key] ...)
   applied, and whose :entities is the given seq keyed by :id."
  [terrain-pokes entities]
  (let [grid (reduce (fn [g [x y k]] (tile/set-tile g x y k))
                     (tile/make-grid 5 5 :grass)
                     terrain-pokes)]
    {:grid grid
     :entities (into {} (map (juxt :id identity)) entities)}))

(deftest off-map-is-nil
  (testing "describe-tile returns nil outside the grid"
    (let [w (world-with [] [])]
      (is (nil? (inspect/describe-tile w [-1 0])))
      (is (nil? (inspect/describe-tile w [0 -1])))
      (is (nil? (inspect/describe-tile w [5 0])))
      (is (nil? (inspect/describe-tile w [0 5]))))))

(deftest bare-grass-tile
  (testing "a passable grass tile is one line, speed as a percentage"
    (is (= ["Grass 100%"] (inspect/describe-tile (world-with [] []) [2 2])))))

(deftest impassable-tile-omits-percent
  (testing "impassable terrain shows (impassable), no speed %"
    (let [w (world-with [[1 1 :stone]] [])]
      (is (= ["Stone (impassable)"] (inspect/describe-tile w [1 1]))))))

(deftest move-speed-percentages
  (testing "speed % = round(100 / move-cost) for passable terrain"
    (let [w (world-with [[0 0 :dirt] [1 0 :gravel] [2 0 :water]] [])]
      (is (= ["Dirt 87%"]   (inspect/describe-tile w [0 0])))
      (is (= ["Gravel 77%"] (inspect/describe-tile w [1 0])))
      (is (= ["Water 40%"]  (inspect/describe-tile w [2 0]))))))

(deftest entity-lines-sorted-by-id
  (testing "terrain line first, then one label per selectable entity, by :id"
    (let [tree {:id 7 :kind :tree :pos [3 3]}
          pawn {:id 9 :kind :pawn :name "Dave" :pos [3 3]}
          item {:id 4 :kind :item :material :wood :pos [3 3]}
          w    (world-with [] [tree pawn item])]
      (is (= ["Grass 100%" "Wood" "Tree" "Dave"]
             (inspect/describe-tile w [3 3]))))))

(deftest selectable-at-filters-and-sorts
  (testing "only selectable kinds, at the tile, sorted by id; carried items excluded"
    (let [tree    {:id 2 :kind :tree :pos [1 1]}
          pawn    {:id 1 :kind :pawn :name "A" :pos [1 1]}
          carried {:id 3 :kind :item :material :wood :pos nil :carried-by 1}
          elsewhere {:id 4 :kind :tree :pos [2 2]}
          w (world-with [] [tree pawn carried elsewhere])]
      (is (= [1 2] (map :id (inspect/selectable-at w [1 1]))))
      (is (= []    (inspect/selectable-at w [0 0])) "bare tile -> empty"))))

(deftest long-labels-truncate-with-ellipsis
  (testing "a label past max-line-len is cut to exactly max-line-len ending in ..."
    (let [pawn {:id 1 :kind :pawn :name (apply str (repeat 50 \X)) :pos [0 0]}
          w    (world-with [] [pawn])
          line (second (inspect/describe-tile w [0 0]))]
      (is (= inspect/max-line-len (count line)))
      (is (clojure.string/ends-with? line "...")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.inspect-test`
Expected: FAIL — namespace `sim.inspect` cannot be found / does not compile.

- [ ] **Step 3: Write the implementation**

Create `src/sim/inspect.clj` (note: helpers defined *before* `describe-tile` for cold-start load order):

```clojure
(ns sim.inspect
  "PURE tile-inspection logic — no GL, no atoms. Turns (world, [x y]) into the
   concept-line strings the hover panel renders, and into the sorted list of
   selectable entities the cursor can cycle through.

   Mirrors the debug-layer discipline: this is the headless-tested core; the
   GL renderers (sim.ui.inspect-panel, sim.render.layers.selection) are dumb
   views over it. Bounds-checks here so callers (sim.input) can hand us raw,
   possibly off-map coords without taking a sim.tile dependency."
  (:require
   [clojure.string :as str]
   [sim.tile   :as tile]
   [sim.entity :as entity]))

(set! *warn-on-reflection* true)

;; Things worth selecting/labelling. Base terrain is NEVER here. Future kinds
;; (:animal :monster) append for free — describe-tile/selectable-at pick them up.
(def selectable-kinds #{:pawn :item :tree})

;; Char cap per concept line. Default font isn't monospace, so this is a rough
;; visual cap, not pixel-accurate — tune live in the REPL. A cut line is padded
;; out to exactly this length by the trailing "..." (so the cap is the cut).
(def ^:const max-line-len 28)

(defn selectable-at
  "Entities at tile [x y] whose :kind is selectable, SORTED BY :id (stable
   cycle order). Carried items (:pos nil) never match a tile, so they're
   excluded naturally."
  [world [x y]]
  (->> (entity/all-entities world)
       (filter #(and (selectable-kinds (:kind %)) (= [x y] (:pos %))))
       (sort-by :id)))

(defn- truncate
  "Cap `s` at max-line-len chars; if cut, the last 3 chars become '...' so the
   result is exactly max-line-len long."
  [s]
  (if (> (count s) max-line-len)
    (str (subs s 0 (- max-line-len 3)) "...")
    s))

(defn- terrain-line
  "Concept line for a terrain keyword: '<Type> <speed>%' when passable, else
   '<Type> (impassable)'. speed% = round(100 / move-cost)."
  [terrain-key]
  (let [{:keys [passable? move-cost]} (tile/terrain-info terrain-key)
        nm (str/capitalize (name terrain-key))]
    (if passable?
      (str nm " " (Math/round (/ 100.0 (double move-cost))) "%")
      (str nm " (impassable)"))))

(defn- entity-label
  "Short label for a selectable entity."
  [ent]
  (case (:kind ent)
    :pawn (:name ent)
    :item (str/capitalize (name (:material ent)))
    :tree "Tree"
    (str (name (:kind ent)))))

(defn describe-tile
  "Vector of concept-line strings for tile [x y]: the terrain line first, then
   one truncated label per selectable entity (by id). nil if [x y] is off-map."
  [world [x y]]
  (let [{:keys [width height] :as grid} (:grid world)]
    (when (tile/in-bounds? width height x y)
      (into [(truncate (terrain-line (tile/tile-at grid x y)))]
            (map (comp truncate entity-label))
            (selectable-at world [x y])))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.inspect-test`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```
git add src/sim/inspect.clj test/sim/inspect_test.clj
git commit -m "feat(inspect): pure describe-tile + selectable-at core"
```

## Task 2: `:hover` view-state

**Files:**
- Modify: `src/sim/ui_state.clj`
- Test: `test/sim/ui_state_test.clj`

- [ ] **Step 1: Write the failing test**

Add to `test/sim/ui_state_test.clj` (after the existing `toggle-debug-cycles` deftest):

```clojure
(deftest hover-round-trips
  (testing "set-hover! stores a [tx ty]; hover reads it; nil clears"
    (is (nil? (ui/hover)) "absent key reads nil")
    (ui/set-hover! [3 7])
    (is (= [3 7] (ui/hover)))
    (ui/set-hover! nil)
    (is (nil? (ui/hover)))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.ui-state-test`
Expected: FAIL — `No such var: sim.ui-state/hover`.

- [ ] **Step 3: Write the implementation**

In `src/sim/ui_state.clj`, add `:hover nil` to the `defonce` initial map:

```clojure
(defonce ui-state
  ;; zoom < 1.0 zooms IN (OrthographicCamera semantics). 0.8 is a slightly
  ;; closer default than 1.0. gdx `create` recenters :x/:y on the map midpoint,
  ;; so those are just an initial placeholder.
  (atom {:camera   {:x 400.0 :y 200.0 :zoom 0.8}
         :selected nil
         :hover    nil
         :debug?   false}))
```

Then add these two fns after `select!`:

```clojure
(defn hover
  "Tile [tx ty] currently under the cursor, or nil. May be off-map: callers
   (sim.inspect/describe-tile) bounds-check. View state — never serialized."
  []
  (:hover @ui-state))

(defn set-hover!
  "Store the hovered tile [tx ty] (or nil). Called from sim.input/mouseMoved."
  [pos]
  (swap! ui-state assoc :hover pos))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.ui-state-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```
git add src/sim/ui_state.clj test/sim/ui_state_test.clj
git commit -m "feat(ui-state): add :hover field + hover/set-hover!"
```

## Task 3: `mouseMoved` → hover

**Files:**
- Modify: `src/sim/input.clj`

No unit test — `mouseMoved` is a proxy override calling `ui/set-hover!` (the round-trip is covered by Task 2). Verified manually at the end of Plan A.

- [ ] **Step 1: Add the override**

In `src/sim/input.clj`, inside the `(proxy [InputAdapter] [] ...)` in `make-processor`, add a `mouseMoved` override. Put it right after the `scrolled` override:

```clojure
      ;; Cursor moved with no button down: record the hovered tile so the
      ;; inspect panel can read it. Returns false — hover must NEVER consume
      ;; the event. Calls ui/set-hover! directly (we already depend on
      ;; sim.ui-state), same precedent as backtick->debug. Stores raw,
      ;; possibly off-map coords; sim.inspect bounds-checks.
      (mouseMoved [screen-x screen-y]
        (let [height  (long (:height (:grid (world-fn))))
              [tx ty] (screen->tile (camera-fn) tile-size height screen-x screen-y)]
          (ui/set-hover! [tx ty]))
        false)
```

Update the docstring's RimWorld mapping list to add a line for hover (after the `space` line):

```
     mouse-move   → record hovered tile (sim.ui-state/set-hover!)
```

- [ ] **Step 2: Verify it compiles**

Run: `clj -M:test -n sim.ui-state-test`
Expected: PASS (this forces a load of the test path; `sim.input` itself compiles when required). For an explicit syntax check:
Run: `clj -M -e "(require 'sim.input) (println :ok)"`
Expected: prints `:ok` with no compile error.

- [ ] **Step 3: Commit**

```
git add src/sim/input.clj
git commit -m "feat(input): mouseMoved records hovered tile"
```

## Task 4: `sim.ui.inspect-panel` GL renderer

**Files:**
- Create: `src/sim/ui/inspect_panel.clj`

No unit test — pure GL `draw`, matching the hud/terrain/pawns/debug convention.

- [ ] **Step 1: Write the renderer**

Create `src/sim/ui/inspect_panel.clj`:

```clojure
(ns sim.ui.inspect-panel
  "Bottom-right hover inspect pane: the concept lines for the tile under the
   cursor, right-aligned on a translucent rect, just above the HUD status bar.

   A dumb view, like the HUD: it reads (ui/hover), asks sim.inspect for the
   lines, and draws them. All logic is in sim.inspect (headless-tested); this
   file only does GL. Consumes NO clicks. Drawn under the fixed UI camera, in
   the same block as hud/draw.

   Reuses the 1px pixel texture (the solid-rect trick from sim.ui.hud) for the
   panel background and the shared BitmapFont for text. Right-alignment uses a
   GlyphLayout to measure each line's pixel width."
  (:require
   [sim.ui-state :as ui]
   [sim.inspect  :as inspect])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont GlyphLayout)))

(set! *warn-on-reflection* true)

;; Mirrors sim.ui.hud/bar-h: the panel floats just above the 30px status bar.
(def ^:const ^:private bar-h 30)
(def ^:const ^:private pad 6)            ; inner padding, panel px
(def ^:const ^:private right-margin 8)   ; gap from the right viewport edge
(def ^:const ^:private gap-above-bar 6)  ; gap between panel bottom and the bar

(def ^:private panel-color (Color. 0.10 0.10 0.13 0.92)) ; matches hud bar-color
(def ^:private text-color  (Color. 0.90 0.93 0.98 1.0))

(defn draw
  "Render the hover panel. No-op when nothing is hovered or the hovered tile is
   off-map (describe-tile -> nil). `world` supplies terrain/entities; the panel
   always reflects the HOVERED tile, never the selection."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel world]
  (when-let [hov (ui/hover)]
    (when-let [lines (inspect/describe-tile world hov)]
      (let [vw       (.getWidth (Gdx/graphics))
            cap      (.getCapHeight font)
            line-h   (+ cap pad)
            layout   (GlyphLayout.)
            ;; widest line drives the panel width
            widths   (mapv (fn [s] (.setText layout font ^String s) (.width layout)) lines)
            text-w   (double (reduce max 0.0 widths))
            panel-w  (float (+ text-w (* 2 pad)))
            panel-h  (float (+ (* line-h (count lines)) pad))
            panel-x  (float (- vw panel-w right-margin))
            panel-y  (float (+ bar-h gap-above-bar))
            text-r   (float (- (+ panel-x panel-w) pad))] ; right edge for text
        ;; background
        (.setColor batch panel-color)
        (.draw batch pixel panel-x panel-y panel-w panel-h)
        (.setColor batch Color/WHITE)
        ;; lines top-to-bottom, right-aligned within the panel
        (.setColor font text-color)
        (dotimes [i (count lines)]
          (let [s  ^String (nth lines i)
                w  (double (nth widths i))
                ;; top line at the top of the panel; libGDX text y is the
                ;; baseline (top of the glyphs), so offset down by cap.
                ty (float (- (+ panel-y panel-h) pad (* i line-h)))
                tx (float (- text-r w))]
            (.draw font batch s tx ty)))))))
```

- [ ] **Step 2: Verify it compiles**

Run: `clj -M -e "(require 'sim.ui.inspect-panel) (println :ok)"`
Expected: prints `:ok` (loads libGDX classes; no GL context needed just to compile).

- [ ] **Step 3: Commit**

```
git add src/sim/ui/inspect_panel.clj
git commit -m "feat(ui): bottom-right hover inspect panel renderer"
```

## Task 5: Wire the panel into `gdx` (Plan A end-to-end)

**Files:**
- Modify: `src/sim/render/gdx.clj`

- [ ] **Step 1: Add the require**

In the `(:require ...)` block of `src/sim/render/gdx.clj`, add (after the `sim.ui.hud` require):

```clojure
   [sim.ui.inspect-panel :as inspect-panel]
```

- [ ] **Step 2: Draw the panel in the UI block**

In the `render` method's UI block, add the panel draw right after `(hud/draw b f px w)`:

```clojure
          ;; --- Fixed UI camera: HUD that ignores pan/zoom ---
          (.setProjectionMatrix b (.combined uc))
          (.begin b)
          (hud/draw b f px w)
          (inspect-panel/draw b f px w)
          (.end b)))
```

- [ ] **Step 3: Verify and run end-to-end**

Run: `clj -M -e "(require 'sim.render.gdx) (println :ok)"`
Expected: prints `:ok`.

Then launch and confirm manually (this is the Plan A acceptance check — `mouseMoved` wiring from Task 3 is captured at window create, so a fresh launch is required):

Run: `clj -M:run`
Expected: hovering the mouse over a grass tile shows a small bottom-right panel reading `Grass 100%`; hovering a tile with a pawn/tree/item adds a line per entity; moving off the map area shows no panel.

- [ ] **Step 4: Commit**

```
git add src/sim/render/gdx.clj
git commit -m "feat(gdx): wire hover inspect panel into the UI block"
```

---

# PLAN B — Click-Cycle Selection + World-Space Box

End state: left-clicking a tile cycles `:selected` through its selectable entities (wrapping; empty tile clears), shown by a box outline around the selected entity's tile. The pawn-tint stopgap is gone.

## Task 6: `command/left-click!` cycle-select

**Files:**
- Modify: `src/sim/command.clj`
- Test: `test/sim/command_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/sim/command_test.clj`:

```clojure
(ns sim.command-test
  "Cycle-select logic in sim.command/left-click!. Drives the real world +
   ui-state atoms (the two atoms command bridges) and restores them after."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.world    :as world]
   [sim.ui-state :as ui]
   [sim.tile     :as tile]
   [sim.command  :as command]))

;; Snapshot and restore both globals so tests don't leak into the live atoms.
(use-fixtures :each
  (fn [run]
    (let [w @world/world
          u @ui/ui-state]
      (try (run)
           (finally
             (reset! world/world w)
             (reset! ui/ui-state u))))))

(defn- setup!
  "Reset the world to a 5x5 grass grid holding `entities` (by id) and clear
   the selection."
  [entities]
  (reset! world/world
          {:clock 0
           :grid (tile/make-grid 5 5 :grass)
           :entities (into {} (map (juxt :id identity)) entities)
           :events [] :log []})
  (ui/select! nil))

(deftest cycle-advances-and-wraps
  (testing "repeated clicks on one tile advance through entities, by id, wrapping"
    (let [tree {:id 1 :kind :tree :pos [3 3]}
          pawn {:id 2 :kind :pawn :name "Dave" :pos [3 3]}]
      (setup! [tree pawn])
      (command/left-click! 3 3)
      (is (= 1 (ui/selected)) "first click -> lowest id")
      (command/left-click! 3 3)
      (is (= 2 (ui/selected)) "second click -> next id")
      (command/left-click! 3 3)
      (is (= 1 (ui/selected)) "third click wraps to the first"))))

(deftest empty-tile-clears
  (testing "clicking a tile with no selectable entities clears the selection"
    (let [pawn {:id 2 :kind :pawn :name "Dave" :pos [3 3]}]
      (setup! [pawn])
      (command/left-click! 3 3)
      (is (= 2 (ui/selected)))
      (command/left-click! 0 0)
      (is (nil? (ui/selected)) "empty tile -> nil"))))

(deftest different-tile-selects-its-first
  (testing "clicking a different populated tile selects that tile's first entity"
    (let [a {:id 5 :kind :tree :pos [1 1]}
          b {:id 9 :kind :tree :pos [2 2]}]
      (setup! [a b])
      (command/left-click! 1 1)
      (is (= 5 (ui/selected)))
      (command/left-click! 2 2)
      (is (= 9 (ui/selected)) "new tile starts at its first, not a cycle"))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.command-test`
Expected: FAIL — the current `left-click!` selects only a pawn via `pawn-at`, so `cycle-advances-and-wraps` (which expects the tree at id 1 first) fails.

- [ ] **Step 3: Write the implementation**

In `src/sim/command.clj`, add `sim.inspect` to the requires (no cycle — `inspect` requires only `entity`/`tile`):

```clojure
  (:require
   [sim.world    :as world]
   [sim.ui-state :as ui]
   [sim.entity   :as entity]
   [sim.inspect  :as inspect]
   [sim.job      :as job]
   [sim.tile     :as tile]))
```

Replace the `left-click!` defn with the cycle version (leave `pawn-at` and `right-click!` unchanged):

```clojure
(defn left-click!
  "RimWorld left-click: cycle the selection through the selectable entities on
   the clicked tile. Repeated clicks on one tile advance and wrap; a tile whose
   current selection isn't present starts at its first entity; an empty tile
   clears. Entities are sorted by :id (inspect/selectable-at) for a stable
   cycle order."
  [tx ty]
  (let [ids (mapv :id (inspect/selectable-at @world/world [tx ty]))
        cur (ui/selected)
        nxt (cond
              (empty? ids)      nil
              (some #{cur} ids) (nth ids (mod (inc (.indexOf ids cur)) (count ids)))
              :else             (first ids))]
    (ui/select! nxt))
  nil)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.command-test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```
git add src/sim/command.clj test/sim/command_test.clj
git commit -m "feat(command): left-click cycles selection over selectable-at"
```

## Task 7: `sim.render.layers.selection` box

**Files:**
- Create: `src/sim/render/layers/selection.clj`
- Test: `test/sim/selection_layer_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/sim/selection_layer_test.clj`:

```clojure
(ns sim.selection-layer-test
  "Pure geometry for the world-space selection box — sim.render.layers.selection.
   The draw fn issues libGDX calls (untestable headless); selection-box-rects
   is the testable part, same split as the debug layer's path->segments."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.layers.selection :as selection]))

(deftest box-is-four-edge-rects-with-y-flip
  (testing "four thin rects framing the tile's world-pixel rect, (height-1-y) flipped"
    (let [t    selection/box-thickness
          ;; tile [2 1], ts=32, grid-height=5 -> px = 2*32 = 64,
          ;; py = (5-1-1)*32 = 96; tile rect is (64,96) 32x32.
          rects (selection/selection-box-rects [2 1] 32 5)]
      ;; order: bottom, top, left, right
      (is (= [[64.0          96.0          32.0 t]
              [64.0          (- 128.0 t)   32.0 t]
              [64.0          96.0          t    32.0]
              [(- 96.0 t)    96.0          t    32.0]]
             rects)))))

(deftest box-thickness-is-positive
  (testing "box-thickness is a usable positive line width"
    (is (pos? selection/box-thickness))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.selection-layer-test`
Expected: FAIL — `sim.render.layers.selection` cannot be found.

- [ ] **Step 3: Write the implementation**

Create `src/sim/render/layers/selection.clj` (pure `selection-box-rects` defined before `draw`):

```clojure
(ns sim.render.layers.selection
  "World-space selection marker: a box outline around the selected entity's
   tile, for ANY selectable kind. Replaces the pawn-tint stopgap that lived in
   sim.render.layers.pawns.

   Same discipline as the debug layer: selection-box-rects is pure geometry
   (tested headless in sim.selection-layer-test); draw is the untested GL view.
   Uses the (height-1-y) Y-flip — identical to terrain/pawns/debug — so the box
   registers exactly with the sprite underneath, and reuses the shared 1px
   pixel texture for solid rects. Resets the batch tint to white when done."
  (:require
   [sim.ui-state :as ui]
   [sim.entity   :as entity])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

;; Outline thickness in world px. Public so the geometry test derives expected
;; rects from it. ^:const flag (not a type hint) avoids the ^double-on-def trap.
(def ^:const box-thickness 2.0)

(def ^:private box-color (Color. 1.0 0.85 0.2 0.95)) ; amber, like the debug goal

(defn selection-box-rects
  "Four thin edge rects [x y w h] (doubles, world px) framing tile [x y], with
   the (grid-height-1-y) Y-flip. Order: bottom, top, left, right."
  [[x y] tile-size grid-height]
  (let [ts (double tile-size)
        t  box-thickness
        px (* (double x) ts)
        py (* (double (- (long grid-height) (long y) 1)) ts)]
    [[px            py            ts t]    ; bottom edge
     [px            (- (+ py ts) t) ts t]  ; top edge
     [px            py            t  ts]   ; left edge
     [(- (+ px ts) t) py          t  ts]])) ; right edge

(defn draw
  "Draw the box around the selected entity's tile. No-op when nothing is
   selected, the id is stale, or the entity has no :pos (e.g. a carried item).
   Reads (ui/selected) -> entity -> :pos."
  [world ^SpriteBatch batch tile-size ^Texture pixel]
  (when-let [sel (ui/selected)]
    (when-let [pos (:pos (entity/entity world sel))]
      (let [height (long (:height (:grid world)))]
        (.setColor batch box-color)
        (doseq [[x y w h] (selection-box-rects pos tile-size height)]
          (.draw batch pixel (float x) (float y) (float w) (float h)))
        (.setColor batch Color/WHITE)))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.selection-layer-test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```
git add src/sim/render/layers/selection.clj test/sim/selection_layer_test.clj
git commit -m "feat(selection): world-space selection box layer"
```

## Task 8: Drop the pawn-tint stopgap

**Files:**
- Modify: `src/sim/render/layers/pawns.clj`

No unit test — `pawns/draw` is an untested GL layer; this is a deletion. The compositor call updates in Task 9.

- [ ] **Step 1: Remove the tint and the `selected-id` arg**

Replace the entire contents of `src/sim/render/layers/pawns.clj` with:

```clojure
(ns sim.render.layers.pawns
  "Pawns layer: each pawn drawn as a 32px sprite at its tile.

   Selection feedback is now the world-space box (sim.render.layers.selection),
   uniform across all selectable kinds — so pawns always draw untinted. Resets
   the batch tint to white when done so later draws aren't affected."
  (:require
   [sim.entity :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color)
   (com.badlogic.gdx.graphics.g2d SpriteBatch TextureRegion)))

(set! *warn-on-reflection* true)

(defn draw
  "Render pawns in WORLD coordinates. Same Y-flip convention as terrain."
  [world ^SpriteBatch batch tile-size]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)
        ^TextureRegion region (sprites/pawn-region)]
    (doseq [pawn (entity/pawns world)]
      (when-let [[x y] (:pos pawn)]
        (let [px (* (long x) ts)
              ;; bottom-left anchor; (height-1-y) matches screen->tile (see terrain)
              py (* (- height (long y) 1) ts)]
          (.setColor batch Color/WHITE)
          (.draw batch region (float px) (float py) (float ts) (float ts)))))
    (.setColor batch Color/WHITE)))
```

- [ ] **Step 2: Verify it compiles**

Run: `clj -M -e "(require 'sim.render.layers.pawns) (println :ok)"`
Expected: prints `:ok`. (The old 4-arg call site in `gdx` is updated in Task 9; `gdx` is not loaded by this check.)

- [ ] **Step 3: Commit**

```
git add src/sim/render/layers/pawns.clj
git commit -m "refactor(pawns): drop selected-tint stopgap (box replaces it)"
```

## Task 9: Wire selection into `gdx` (Plan B end-to-end)

**Files:**
- Modify: `src/sim/render/gdx.clj`

- [ ] **Step 1: Add the require**

In the `(:require ...)` block, add (after the `sim.render.layers.debug` require):

```clojure
   [sim.render.layers.selection :as selection-layer]
```

- [ ] **Step 2: Update the world block**

In the `render` method's world block, change the pawns call to drop the `(ui/selected)` arg, and add the selection box before the debug overlay:

```clojure
          (terrain/draw     w b tile-size)
          (flora-layer/draw w b tile-size)
          (items-layer/draw w b tile-size)
          (pawns-layer/draw w b tile-size)
          ;; Selection box: world-space marker around the selected entity's
          ;; tile (any kind), reusing the 1px texture. Before the debug overlay
          ;; so a debug path still draws on top of the box.
          (selection-layer/draw w b tile-size px)
          ;; Debug overlay draws LAST in the world block so paths sit on top of
          ;; everything; it self-gates on (ui/debug?) and reuses the HUD's 1px
          ;; texture (px) for its solid-rect segments.
          (debug-layer/draw w b tile-size px)
          (.end b)
```

- [ ] **Step 3: Verify and run end-to-end**

Run: `clj -M -e "(require 'sim.render.gdx) (println :ok)"`
Expected: prints `:ok`.

Run all tests to confirm nothing regressed:
Run: `clj -M:test`
Expected: all tests PASS.

Then launch and confirm manually (compositor edits are captured at window create — fresh launch required):

Run: `clj -M:run`
Expected: left-clicking a pawn/tree/item draws an amber box around its tile; clicking the same tile again advances the box to the next entity there and wraps; clicking empty ground removes the box; pawns are no longer tinted yellow.

- [ ] **Step 4: Commit**

```
git add src/sim/render/gdx.clj
git commit -m "feat(gdx): wire selection box, drop pawns selected-id arg"
```

---

## Final verification

- [ ] Run the full suite: `clj -M:test` — expect all PASS (inspect, ui-state, command, selection-layer, plus the pre-existing debug-layer/entity/job/simulation/sprites/worldgen suites).
- [ ] Launch `clj -M:run` and walk the two acceptance checks (Task 5 hover panel, Task 9 selection box) once more in a single session.
```
