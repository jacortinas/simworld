# Visual Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Brighten the map — per-terrain color base + 32rogues "(no bg)" detail sprites + real (static) water + a lighter clear color — using assets already in the repo.

**Architecture:** Terrain defs gain a `:color [r g b]` base (content). The terrain render layer draws, per tile, a color quad (the shared 1×1 `pixel` texture, the `zones.clj` trick) then the detail sprite on top — transparency decides what shows. `terrain->cell` switches to the `[sheet col row]` shape (matching `material->cell`) so water can live on `animated-tiles.png`.

**Tech Stack:** Clojure 1.12, libGDX, cognitect test-runner (`clj -M:test`). All new logic is headless-tested; GL `draw` stays untested like every render layer.

**Spec:** `docs/superpowers/specs/2026-05-27-visual-refresh-design.md`

---

## File Structure

- `resources/defs/terrain.edn` — **modify.** Add `:color [r g b]` to each terrain.
- `src/sim/defs.clj` — **modify.** `::color` spec on `::terrain-entry`; `terrain-color` accessor.
- `src/sim/tile.clj` — **modify.** `terrain-color` wrapper delegating to defs.
- `src/sim/render/sprites.clj` — **modify.** `terrain->cell` → `[sheet col row]`; ground → "(no bg)" cells; `:animated` sheet + water cell; `terrain-region` destructures the sheet.
- `src/sim/render/layers/terrain.clj` — **modify.** `draw` takes `pixel`; base quad + detail sprite per tile.
- `src/sim/screens/play.clj` — **modify.** Pass `pixel` to `terrain/draw` (line 51).
- `src/sim/render/gdx.clj` — **modify.** Lighter `glClearColor` (line 134).
- `CREDITS.md` — **create.** 32rogues attribution.
- `test/sim/defs_test.clj` — **modify.** `:color` validation + `terrain-color` tests.
- `test/sim/sprites_test.clj` — **modify.** `:animated` sheet; `[sheet col row]` bounds check.
- `CLAUDE.md` — **modify.** Note the brightened terrain render + `:color` field.

---

## Task 1: Terrain `:color` content + accessor

**Files:**
- Modify: `resources/defs/terrain.edn`, `src/sim/defs.clj`, `src/sim/tile.clj`
- Test: `test/sim/defs_test.clj`

- [ ] **Step 1: Write the failing tests**

Append to `test/sim/defs_test.clj`:

```clojure
(deftest every-terrain-has-a-valid-color
  (testing "each terrain def carries a [r g b] base color of 3 doubles in [0,1]"
    (doseq [k (defs/ids :terrain)]
      (let [c (:color (defs/terrain k))]
        (is (vector? c) (str k " :color should be a vector"))
        (is (= 3 (count c)) (str k " :color should have 3 components"))
        (is (every? #(<= 0.0 (double %) 1.0) c) (str k " :color components in [0,1]"))))))

(deftest terrain-color-returns-base-and-fallback
  (testing "terrain-color returns the def's color"
    (is (= (:color (defs/terrain :grass)) (defs/terrain-color :grass))))
  (testing "an unknown terrain falls back to grass's color (terrain falls back to grass)"
    (is (= (defs/terrain-color :grass) (defs/terrain-color :no-such-terrain)))))

(deftest load-rejects-out-of-range-color
  (testing "a terrain :color component above 1.0 fails validation"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)color"
         (defs/load-sources! {:terrain [{:grass {:move-cost 1.0 :passable? true
                                                 :color [2.0 0.0 0.0]}}]})))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clj -M:test -n sim.defs-test`
Expected: FAIL — `defs/terrain-color` is unresolved (compile/var error) and `:color` is absent from the defs.

- [ ] **Step 3: Add `:color` to every terrain in `resources/defs/terrain.edn`**

Replace the map (keep the header comment above it) with:

```clojure
{:grass  {:char \. :move-cost 1.0  :passable? true  :color [0.36 0.62 0.30]}
 :dirt   {:char \, :move-cost 1.15 :passable? true  :color [0.55 0.43 0.28]}
 :gravel {:char \: :move-cost 1.30 :passable? true  :color [0.60 0.60 0.62]}
 :stone  {:char \# :move-cost 1.0  :passable? false :color [0.40 0.40 0.42]}
 :water  {:char \~ :move-cost 2.5  :passable? true  :color [0.20 0.45 0.75]}
 :wall   {:char \# :move-cost 1.0  :passable? false :color [0.30 0.30 0.32]}}
```

- [ ] **Step 4: Add the `::color` spec in `src/sim/defs.clj`**

After the `::seek-below` spec (line 33), add:

```clojure
(s/def ::color (s/coll-of (s/and number? #(<= 0.0 (double %) 1.0))
                          :kind vector? :count 3))
```

Then extend `::terrain-entry` (line 35) to accept it:

```clojure
(s/def ::terrain-entry  (s/keys :req-un [::move-cost ::passable?] :opt-un [::char ::color]))
```

- [ ] **Step 5: Add the `terrain-color` accessor in `src/sim/defs.clj`**

After the `terrain` lookup (ends line 125), add:

```clojure
(def ^:private default-color
  "Neutral grey for a terrain def lacking a :color (graceful, never crashes)."
  [0.5 0.5 0.5])

(defn terrain-color
  "Base [r g b] color for terrain `k`. Unknown terrain falls back to grass
   (via `terrain`); a def with no :color falls back to neutral grey."
  [k]
  (:color (terrain k) default-color))
```

- [ ] **Step 6: Add the `terrain-color` wrapper in `src/sim/tile.clj`**

After `move-cost` (ends line 34), add:

```clojure
(defn terrain-color
  "Base [r g b] render color for a terrain type (delegates to sim.defs)."
  [terrain-key]
  (defs/terrain-color terrain-key))
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `clj -M:test -n sim.defs-test`
Expected: PASS — all defs tests green, including the three new ones.

- [ ] **Step 8: Commit**

```bash
git add resources/defs/terrain.edn src/sim/defs.clj src/sim/tile.clj test/sim/defs_test.clj
git commit -F - <<'EOF'
feat(defs): per-terrain :color base + terrain-color accessor

Adds a [r g b] base color (0-1 doubles) to every terrain def, spec-
validated, with a neutral-grey fallback. Backs the brightened terrain
render layer.
EOF
```

---

## Task 2: Sprites — `[sheet col row]`, "(no bg)" ground, animated water

**Files:**
- Modify: `src/sim/render/sprites.clj`
- Test: `test/sim/sprites_test.clj`

- [ ] **Step 1: Update the test's sheet table + add the animated grid**

In `test/sim/sprites_test.clj`, change `sheet-files` (lines 16-19) to add the animated sheet:

```clojure
(def ^:private sheet-files
  {:tiles    "32rogues/tiles.png"
   :rogues   "32rogues/rogues.png"
   :items    "32rogues/items.png"
   :animated "32rogues/animated-tiles.png"})
```

And `expected-grid` (lines 21-24) to pin its dimensions (352×384 px = 11×12 cells):

```clojure
(def ^:private expected-grid    ; [cols rows] of 32px cells
  {:tiles    [17 26]
   :rogues   [7 7]
   :items    [11 26]
   :animated [11 12]})
```

- [ ] **Step 2: Update `mapped-cells-are-in-bounds` for the `[sheet col row]` shape**

Replace the `mapped-cells-are-in-bounds` deftest (lines 51-61) with the sheet-aware version (mirrors `item-cells-in-bounds`):

```clojure
(deftest mapped-cells-are-in-bounds
  (doseq [[k [sheet c r]] sprites/terrain->cell]
    (let [[cols rows] (expected-grid sheet)]
      (testing (str "terrain " (name k) " on sheet " (name sheet))
        (is (and (<= 0 c) (< c cols)) (str "col " c " out of 0.." (dec cols)))
        (is (and (<= 0 r) (< r rows)) (str "row " r " out of 0.." (dec rows))))))
  (let [[rc rr] (expected-grid :rogues)
        [c r]   @#'sprites/pawn-cell]
    (testing "pawn cell"
      (is (and (<= 0 c) (< c rc)) (str "col " c " out of 0.." (dec rc)))
      (is (and (<= 0 r) (< r rr)) (str "row " r " out of 0.." (dec rr))))))
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `clj -M:test -n sim.sprites-test`
Expected: FAIL — `terrain->cell` is still `[col row]`, so destructuring `[sheet c r]` makes `sheet` a number and `(expected-grid sheet)` nil → NPE/assertion failure; also the `:animated` PNG-grid check has no matching production sheet yet (still passes on the test's own table, but the cell-bounds test fails).

- [ ] **Step 4: Convert `terrain->cell` to `[sheet col row]` in `src/sim/render/sprites.clj`**

Replace the `terrain->cell` def (lines 75-81) with:

```clojure
(def terrain->cell
  {:grass  [:tiles    4 7]    ; 8.e  grass 1 (no bg)
   :dirt   [:tiles    4 8]    ; 9.e  dirt 1 (no bg)
   :gravel [:tiles    4 9]    ; 10.e stone floor 1 (no bg)
   :stone  [:tiles    0 1]    ; 2.a  rough stone wall (top)
   :water  [:animated 0 10]   ; animated-tiles row 11 "water waves", frame 1
   :wall   [:tiles    0 2]})  ; 3.a  stone brick wall (top)
```

- [ ] **Step 5: Update `terrain-region` to read the sheet**

Replace `terrain-region` (lines 85-89) with:

```clojure
(defn terrain-region
  "Sprite region for a terrain keyword; falls back to grass if unmapped."
  ^TextureRegion [terrain-key]
  (let [[sheet c r] (terrain->cell terrain-key (terrain->cell :grass))]
    (region sheet c r)))
```

- [ ] **Step 6: Add the `:animated` sheet to production `sheet-files`**

In `src/sim/render/sprites.clj`, change `sheet-files` (lines 29-32) to:

```clojure
(def ^:private sheet-files
  {:tiles    "32rogues/tiles.png"
   :rogues   "32rogues/rogues.png"
   :items    "32rogues/items.png"
   :animated "32rogues/animated-tiles.png"})
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `clj -M:test -n sim.sprites-test`
Expected: PASS — animated sheet exists at 11×12, every terrain cell is in-bounds for its sheet, water resolves to `[:animated 0 10]`.

- [ ] **Step 8: Commit**

```bash
git add src/sim/render/sprites.clj test/sim/sprites_test.clj
git commit -F - <<'EOF'
feat(sprites): [sheet col row] terrain cells; no-bg ground + real water

terrain->cell now carries a sheet tag (like material->cell). Ground uses
the transparent "(no bg)" detail cells; :water moves to the real water
tile on animated-tiles.png. sprites-test validates the new sheet + cells.
EOF
```

---

## Task 3: Terrain layer — color base + detail; brighter clear color

**Files:**
- Modify: `src/sim/render/layers/terrain.clj`, `src/sim/screens/play.clj`, `src/sim/render/gdx.clj`

No unit test — GL `draw` is untested across all layers (the spec). Correctness rides on Task 1/2 tests + the full-suite compile + manual verify (Task 5).

- [ ] **Step 1: Rewrite the terrain layer `draw`**

Replace the whole body of `src/sim/render/layers/terrain.clj` (keep the ns docstring intent) with:

```clojure
(ns sim.render.layers.terrain
  "Terrain layer: per tile, a base color quad (the terrain's :color, stamped
   with the shared 1px pixel texture) then the detail sprite on top. Transparent
   '(no bg)' sprites let the bright base show; opaque sprites (water/stone/wall)
   cover it — no per-terrain branching. Pure function of (world, batch,
   tile-size, pixel). The caller owns begin/end; this resets tint to white."
  (:require
   [sim.tile :as tile]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  "Render every tile in WORLD coordinates with the (height-1-y) Y-flip used by
   every world layer (matches sim.input/screen->tile). Each tile = a base color
   quad + the detail sprite."
  [world ^SpriteBatch batch tile-size ^Texture pixel]
  (let [grid   (:grid world)
        width  (long (:width grid))
        height (long (:height grid))
        ts     (long tile-size)]
    (dotimes [y height]
      (dotimes [x width]
        (let [t       (tile/tile-at grid x y)
              px      (* x ts)
              py      (* (- height y 1) ts)
              [r g b] (tile/terrain-color t)]
          ;; 1. base color quad
          (.setColor batch (float r) (float g) (float b) (float 1.0))
          (.draw batch pixel (float px) (float py) (float ts) (float ts))
          ;; 2. detail sprite on top (untinted)
          (.setColor batch Color/WHITE)
          (.draw batch (sprites/terrain-region t)
                 (float px) (float py) (float ts) (float ts)))))))
```

- [ ] **Step 2: Pass `pixel` to the terrain layer in `src/sim/screens/play.clj`**

Change line 51 from:

```clojure
    (terrain/draw     world batch tile-size)
```

to:

```clojure
    (terrain/draw     world batch tile-size pixel)
```

- [ ] **Step 3: Lighten the clear color in `src/sim/render/gdx.clj`**

Change line 134 from:

```clojure
          (.glClearColor (Gdx/gl) 0.05 0.05 0.08 1.0)
```

to:

```clojure
          (.glClearColor (Gdx/gl) 0.12 0.13 0.16 1.0)
```

- [ ] **Step 4: Verify the whole project still compiles + all tests pass**

Run: `clj -M:test`
Expected: PASS — 0 failures, 0 errors across every namespace (a require-time compile error in any touched file would surface here).

- [ ] **Step 5: Commit**

```bash
git add src/sim/render/layers/terrain.clj src/sim/screens/play.clj src/sim/render/gdx.clj
git commit -F - <<'EOF'
feat(render): per-terrain color base + detail sprite, lighter clear color

The terrain layer stamps a color quad (terrain :color) then the detail
sprite per tile; transparency decides what shows. Clear color lifted out
of dungeon-black to frame the map.
EOF
```

---

## Task 4: Attribution

**Files:**
- Create: `CREDITS.md`

- [ ] **Step 1: Create `CREDITS.md`**

```markdown
# Credits

## Art

- **32rogues** — © 2024 Seth Boyles · <https://sethbb.itch.io/32rogues>
  Tileset used under its license: commercial and non-commercial use permitted,
  modification allowed, no redistribution or resale. Credit is appreciated
  (not required) and gladly given here.
```

- [ ] **Step 2: Commit**

```bash
git add CREDITS.md
git commit -F - <<'EOF'
docs(credits): attribute the 32rogues tileset (Seth Boyles)
EOF
```

---

## Task 5: Docs + verify

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Note the brightened terrain render in `CLAUDE.md`**

In the "Rendering — libGDX, 32px sprites" bullet, after the sentence describing the layers, add:

```
The terrain layer draws a per-terrain `:color` base quad (content in
terrain.edn) then the sprite on top — transparent "(no bg)" detail cells show
the bright base; `:water` uses the real water tile on `animated-tiles.png`.
```

And update the sprite-migration "Not yet done" line that still claims "The map
is still all-grass until worldgen": drop that clause (worldgen + the brightened
palette now produce varied, bright terrain).

- [ ] **Step 2: Full suite (final regression gate)**

Run: `clj -M:test`
Expected: PASS — 0 failures, 0 errors.

- [ ] **Step 3: Manual visual verify (REPL, requires the window)**

Run: `clj -M:mac:repl`, then:

```clojure
(in-ns 'user)
(generate-world! {:seed 42})
(go!)
```

Expected: the map renders BRIGHT — green grass, tan dirt, grey gravel, blue
water — not the old dark dungeon look. Tune any color live by editing
`resources/defs/terrain.edn` and `(reload-defs!)` (no restart). Close the window
when satisfied.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -F - <<'EOF'
docs(claude): record brightened terrain render + :color content field
EOF
```

---

## Self-Review notes

- **Spec coverage:** terrain `:color` (Task 1) ✓; sprites "(no bg)" + real water + `[sheet col row]` (Task 2) ✓; base-quad+detail layer + clear color (Task 3) ✓; attribution (Task 4) ✓; tests + CLAUDE.md (Tasks 1,2,5) ✓. Scope boundary honored — no new terrain types, no animation, no worldgen logic.
- **Type consistency:** `terrain-color` (defs → tile → layer) returns `[r g b]`; `terrain->cell` is `[sheet col row]` everywhere (def, `terrain-region`, both tests); `:animated` added to production AND test sheet tables with grid `[11 12]`; `draw` signature `(world batch tile-size pixel)` matches the updated `play.clj` call.
- **No placeholders:** every code/edit step shows full content; colors and the water cell `[:animated 0 10]` are concrete.
