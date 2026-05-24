# Map Generation — Plan 1 (gridnoise core + terrain base + detail scatter)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a deterministic, gameplay-meaningful map — varied ground + water, scattered trees, and haulable wood/food/stone — playable end-to-end with the existing haul job.

**Architecture:** Two layers. A game-agnostic `gridnoise.*` core (value noise, a generic grid, PNG dump) makes *shapes*; a `sim.worldgen` pipeline of composable `state → state` passes turns shapes into game content. The pipeline runs in two phases: a **terrain phase** (writes only the cell grid) then an **entity/detail phase** (a "fat pass" that reads finished terrain and scatters trees/items). Determinism comes from seeding each pass independently. This plan ships a minimal terrain phase (noise base, no rock) + the detail phase; rock + cellular-automata + connectivity guard are deferred to Plan 2.

**Tech Stack:** Clojure 1.12, `clojure.test`, `java.util.Random` (seeded), `java.awt.image.BufferedImage` / `javax.imageio.ImageIO` for the PNG dump, libGDX for rendering.

---

## Spec

This plan implements Plan 1 of `docs/superpowers/specs/2026-05-24-map-generation-design.md`. Read that spec's "Two phases", "Composability", and "Determinism" sections before starting.

## Running tests

Primary (a real shell with the Clojure CLI on PATH):

```
clj -M:test -n <namespace>
```

If `clj` is not resolvable in your shell (this Windows box registers the Clojure CLI as a PowerShell module, not a PATH binary), run tests from a REPL instead:

```clojure
(require '[clojure.test :as t] '<namespace> :reload)
(t/run-tests '<namespace>)
```

Both report `{:test N :pass N :fail 0 :error 0}` style summaries. "Expected: PASS" below means `:fail 0 :error 0`.

## File structure (what each file owns)

**Generic core — `gridnoise.*` (MUST NOT import anything under `sim.*`):**

- `src/gridnoise/noise.clj` — seeded value noise + fbm field. Pure math, returns doubles in `[0,1]`.
- `src/gridnoise/grid.clj` — generic grid `{:width :height :cells}`: `generate`, `idx`, `in-bounds?`, `cell-at`, `neighbors-8`, `map-cells`.
- `src/gridnoise/image.clj` — render a grid of `[0,1]` cells to a grayscale PNG.

**Game layer — `sim.*` (depends on `gridnoise`, never the reverse):**

- `src/sim/worldgen.clj` — pipeline (`generate`, `init-state`, `default-pipeline`), terrain classifier + `base-pass` (terrain phase), `scatter-pass` (detail phase) and its lean helpers.
- `src/sim/entity.clj` — add `make-tree`, `:tree` kind, `trees` query (edit).
- `src/sim/render/sprites.clj` — add `tree-region` (edit).
- `src/sim/render/layers/flora.clj` — draw tree entities (new).
- `src/sim/render/gdx.clj` — insert flora layer into compositor (edit).
- `src/sim/world.clj` — `reset-world!` gains `:generate?` (edit).
- `dev/user.clj` — `generate-world!` helper + gridnoise requires (edit).

**Tests:**

- `test/gridnoise/noise_test.clj`, `test/gridnoise/grid_test.clj`, `test/gridnoise/image_test.clj`
- `test/sim/entity_test.clj`, `test/sim/worldgen_test.clj`

---

## Task 1: `gridnoise.noise` — seeded value noise + fbm field

**Files:**
- Create: `src/gridnoise/noise.clj`
- Test: `test/gridnoise/noise_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `test/gridnoise/noise_test.clj`:

```clojure
(ns gridnoise.noise-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [gridnoise.noise :as noise]))

(deftest field-is-deterministic
  (testing "same seed + coords → identical value, across separate field fns"
    (let [f (noise/field {:seed 42})]
      (is (= (f 3 7) (f 3 7))))
    (is (= ((noise/field {:seed 42}) 3 7)
           ((noise/field {:seed 42}) 3 7)))))

(deftest field-stays-in-unit-range
  (testing "every sample is within [0,1]"
    (let [f (noise/field {:seed 1})]
      (doseq [x (range 0 40) y (range 0 40)]
        (let [v (f x y)]
          (is (<= 0.0 v 1.0) (str "out of range at " [x y] " = " v)))))))

(deftest field-is-continuous
  (testing "tiny coordinate change → tiny value change (interpolated, not hashed)"
    (let [f (noise/field {:seed 5})]
      (is (< (Math/abs (- (f 10.0 10.0) (f 10.000001 10.0))) 1e-3)))))

(deftest different-seeds-differ
  (testing "the seed actually changes the field"
    (is (not= ((noise/field {:seed 1}) 5 5)
              ((noise/field {:seed 2}) 5 5)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clj -M:test -n gridnoise.noise-test`
Expected: FAIL/ERROR — "No namespace: gridnoise.noise" / unable to resolve `field`.

- [ ] **Step 3: Write the implementation**

Create `src/gridnoise/noise.clj`. Note source order: helpers are defined before their callers (cold-start compiles top-to-bottom — see CLAUDE.md).

```clojure
(ns gridnoise.noise
  "Seeded value-noise primitives — game-agnostic. A `field` is a pure
   `(fn [x y] -> double in [0,1])` sampling fractal (fbm) value noise.

   Value noise = a hash at integer lattice points, smoothstep-interpolated
   between them. Keying the hash by coordinate+seed (instead of a sequential
   java.util.Random stream) makes sampling order-independent and trivially
   deterministic.")

(set! *warn-on-reflection* true)

(defn- smoothstep ^double [^double t]
  (* t t (- 3.0 (* 2.0 t))))

(defn- lerp ^double [^double a ^double b ^double t]
  (+ a (* t (- b a))))

(defn- hash01
  "Deterministic pseudo-random double in [0,1] for an integer lattice point.
   Integer hash (xorshift-ish) folded to a non-negative double."
  ^double [^long x ^long y ^long seed]
  (let [h (unchecked-long (+ (unchecked-multiply x 374761393)
                             (unchecked-multiply y 668265263)
                             (unchecked-multiply seed 1442695040888963407)))
        h (unchecked-long (bit-xor h (unsigned-bit-shift-right h 13)))
        h (unchecked-long (unchecked-multiply h 1274126177))
        h (bit-and h 0x7fffffff)]
    (/ (double h) 2147483647.0)))

(defn value-noise
  "Single-octave value noise at (x,y). Returns a double in [0,1]."
  ^double [^double x ^double y ^long seed]
  (let [x0  (long (Math/floor x))
        y0  (long (Math/floor y))
        x1  (inc x0)
        y1  (inc y0)
        u   (smoothstep (- x (double x0)))
        v   (smoothstep (- y (double y0)))
        n00 (hash01 x0 y0 seed)
        n10 (hash01 x1 y0 seed)
        n01 (hash01 x0 y1 seed)
        n11 (hash01 x1 y1 seed)
        nx0 (lerp n00 n10 u)
        nx1 (lerp n01 n11 u)]
    (lerp nx0 nx1 v)))

(defn field
  "Return a pure (fn [x y] -> double in [0,1]) sampling fractal value noise.
   opts: :seed :freq :octaves :persistence (all have sensible defaults).
   Each octave doubles frequency, scales amplitude by persistence, and uses
   seed+octave so layers don't align. Normalized by the amplitude sum."
  [{:keys [seed freq octaves persistence]
    :or   {seed 0 freq 0.08 octaves 4 persistence 0.5}}]
  (let [seed        (long seed)
        base-freq   (double freq)
        octaves     (long octaves)
        persistence (double persistence)]
    (fn [x y]
      (let [x (double x) y (double y)]
        (loop [o 0, f base-freq, amp 1.0, sum 0.0, norm 0.0]
          (if (= o octaves)
            (/ sum norm)
            (recur (inc o)
                   (* f 2.0)
                   (* amp persistence)
                   (+ sum (* amp (value-noise (* x f) (* y f) (+ seed o))))
                   (+ norm amp))))))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clj -M:test -n gridnoise.noise-test`
Expected: PASS (`:fail 0 :error 0`).

- [ ] **Step 5: Commit**

```bash
git add src/gridnoise/noise.clj test/gridnoise/noise_test.clj
git commit -m "feat(gridnoise): seeded value-noise field"
```

---

## Task 2: `gridnoise.grid` — generic grid + algorithms

**Files:**
- Create: `src/gridnoise/grid.clj`
- Test: `test/gridnoise/grid_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `test/gridnoise/grid_test.clj`:

```clojure
(ns gridnoise.grid-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [gridnoise.grid :as grid]))

(deftest generate-builds-cells-row-major
  (testing "generate calls (f x y) per cell, row-major"
    (let [g (grid/generate 3 2 (fn [x y] [x y]))]
      (is (= 3 (:width g)))
      (is (= 2 (:height g)))
      (is (= 6 (count (:cells g))))
      (is (= [0 0] (grid/cell-at g 0 0)))
      (is (= [2 1] (grid/cell-at g 2 1))))))

(deftest idx-and-in-bounds
  (let [g (grid/generate 3 3 (constantly :x))]
    (is (= 4 (grid/idx g 1 1)))
    (is (grid/in-bounds? g 0 0))
    (is (grid/in-bounds? g 2 2))
    (is (not (grid/in-bounds? g 3 0)))
    (is (not (grid/in-bounds? g -1 0)))
    (is (nil? (grid/cell-at g 5 5)))))

(deftest neighbors-8-clips-at-edges
  (let [g (grid/generate 3 3 (constantly :x))]
    (is (= 3 (count (grid/neighbors-8 g 0 0))) "corner has 3 neighbors")
    (is (= 5 (count (grid/neighbors-8 g 1 0))) "edge has 5 neighbors")
    (is (= 8 (count (grid/neighbors-8 g 1 1))) "center has 8 neighbors")))

(deftest map-cells-transforms-every-cell
  (let [g (grid/generate 2 2 (constantly 1))]
    (is (= [2 2 2 2] (:cells (grid/map-cells g inc))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clj -M:test -n gridnoise.grid-test`
Expected: FAIL/ERROR — "No namespace: gridnoise.grid".

- [ ] **Step 3: Write the implementation**

Create `src/gridnoise/grid.clj`:

```clojure
(ns gridnoise.grid
  "Generic 2D grid — game-agnostic. A grid is {:width :height :cells [...]},
   linearized as (+ x (* y width)). Cells are any value (doubles for a noise
   field, booleans for a CA mask, keywords for terrain). All ops take the grid
   map (so they read :width/:height), unlike sim.tile's raw-width helpers.")

(set! *warn-on-reflection* true)

(defn idx
  "Linear index of (x,y)."
  ^long [{:keys [width]} ^long x ^long y]
  (+ x (* y (long width))))

(defn in-bounds?
  [{:keys [width height]} x y]
  (and (>= x 0) (< x width) (>= y 0) (< y height)))

(defn cell-at
  "Cell value at (x,y), or nil if out of bounds."
  [{:keys [cells] :as g} x y]
  (when (in-bounds? g x y)
    (nth cells (idx g x y))))

(defn neighbors-8
  "In-bounds 8-connected neighbor coords of (x,y) as [[x y] ...]."
  [g x y]
  (vec (for [dx [-1 0 1] dy [-1 0 1]
             :when (not (and (zero? dx) (zero? dy)))
             :let  [nx (+ x dx) ny (+ y dy)]
             :when (in-bounds? g nx ny)]
         [nx ny])))

(defn generate
  "Build a grid by calling (f x y) for each cell, row-major."
  [width height f]
  {:width  width
   :height height
   :cells  (vec (for [y (range height) x (range width)] (f x y)))})

(defn map-cells
  "Return the grid with f applied to every cell value."
  [g f]
  (assoc g :cells (mapv f (:cells g))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clj -M:test -n gridnoise.grid-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/gridnoise/grid.clj test/gridnoise/grid_test.clj
git commit -m "feat(gridnoise): generic grid with index/neighbors/map-cells"
```

---

## Task 3: `gridnoise.image` — grayscale PNG dump

**Files:**
- Create: `src/gridnoise/image.clj`
- Test: `test/gridnoise/image_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/gridnoise/image_test.clj`:

```clojure
(ns gridnoise.image-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [gridnoise.noise :as noise]
   [gridnoise.grid :as grid]
   [gridnoise.image :as image])
  (:import (java.io File)))

(deftest writes-a-nonempty-png
  (testing "a noise field renders to a PNG file on disk"
    (let [f   (noise/field {:seed 3})
          g   (grid/generate 16 16 (fn [x y] (f x y)))
          tmp (File/createTempFile "gridnoise" ".png")]
      (.deleteOnExit tmp)
      (image/spit-png! (.getPath tmp) g)
      (is (.exists tmp))
      (is (pos? (.length tmp))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n gridnoise.image-test`
Expected: FAIL/ERROR — "No namespace: gridnoise.image".

- [ ] **Step 3: Write the implementation**

Create `src/gridnoise/image.clj`:

```clojure
(ns gridnoise.image
  "Render a grid to a grayscale PNG — standalone 'see the noise' tooling.
   Game-agnostic; uses only java.awt/javax.imageio (no display needed,
   safe headless). Default cell->gray expects cells in [0,1]."
  (:require [gridnoise.grid :as grid])
  (:import (java.awt.image BufferedImage)
           (javax.imageio ImageIO)
           (java.io File)))

(set! *warn-on-reflection* true)

(defn- unit->gray ^long [^double v]
  (long (Math/round (* 255.0 (max 0.0 (min 1.0 v))))))

(defn field->image
  "Render grid `g` to a BufferedImage. `cell->gray` maps a cell to a 0..255
   gray level; defaults to treating cells as doubles in [0,1]."
  (^BufferedImage [g] (field->image g (fn [c] (unit->gray (double c)))))
  (^BufferedImage [{:keys [width height] :as g} cell->gray]
   (let [img (BufferedImage. (int width) (int height) BufferedImage/TYPE_INT_RGB)]
     (dotimes [y height]
       (dotimes [x width]
         (let [gr  (int (cell->gray (grid/cell-at g x y)))
               rgb (bit-or (bit-shift-left gr 16) (bit-shift-left gr 8) gr)]
           (.setRGB img (int x) (int y) (int rgb)))))
     img)))

(defn spit-png!
  "Write grid `g` to `path` as a PNG. Returns the path."
  ([path g] (spit-png! path g (fn [c] (unit->gray (double c)))))
  ([path g cell->gray]
   (ImageIO/write (field->image g cell->gray) "png" (File. (str path)))
   path))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n gridnoise.image-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/gridnoise/image.clj test/gridnoise/image_test.clj
git commit -m "feat(gridnoise): grayscale PNG dump for fields"
```

---

## Task 4: `sim.entity` — tree entity

**Files:**
- Modify: `src/sim/entity.clj`
- Test: `test/sim/entity_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `test/sim/entity_test.clj`:

```clojure
(ns sim.entity-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.entity :as entity]))

(deftest make-tree-shape
  (testing "a tree is a :tree-kind entity at a position"
    (let [t (entity/make-tree [4 5])]
      (is (= :tree (:kind t)))
      (is (= [4 5] (:pos t)))
      (is (= :tree (:species t)))
      (is (some? (:id t))))))

(deftest trees-query-filters-by-kind
  (testing "trees returns only :tree entities"
    (let [w (-> {:entities {}}
                (entity/add-entity (entity/make-pawn "P" [0 0]))
                (entity/add-entity (entity/make-tree [1 1]))
                (entity/add-entity (entity/make-tree [2 2])))]
      (is (= 2 (count (entity/trees w))))
      (is (every? #(= :tree (:kind %)) (entity/trees w))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clj -M:test -n sim.entity-test`
Expected: FAIL/ERROR — unable to resolve `make-tree` / `trees`.

- [ ] **Step 3: Add `make-tree` after `make-item`**

In `src/sim/entity.clj`, immediately after the `make-item` defn (around line 52), add:

```clojure
;; ---------------------------------------------------------------------------
;; Trees — passable flora. A* reads only terrain, so trees never affect
;; pathfinding (RimWorld's model: walk through, chop later). Inert until a
;; future chop job; rendered now.
;; ---------------------------------------------------------------------------

(defn make-tree
  "Construct a tree entity at [x y]. Pure — does NOT insert into the world."
  [[x y]]
  {:id      (next-id!)
   :kind    :tree
   :pos     [x y]
   :species :tree})
```

- [ ] **Step 4: Add the `trees` query next to `items`**

In `src/sim/entity.clj`, immediately after the `items` defn (around line 78), add:

```clojure
(defn trees
  "Sequence of all tree entities."
  [world]
  (filter #(= :tree (:kind %)) (all-entities world)))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `clj -M:test -n sim.entity-test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/sim/entity.clj test/sim/entity_test.clj
git commit -m "feat(entity): tree entity + trees query"
```

---

## Task 5: `sim.worldgen` — pipeline scaffold + terrain phase (`base-pass`)

**Files:**
- Create: `src/sim/worldgen.clj`
- Test: `test/sim/worldgen_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `test/sim/worldgen_test.clj`:

```clojure
(ns sim.worldgen-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world :as world]
   [sim.tile :as tile]
   [sim.worldgen :as wg]))

(def ^:private test-opts {:seed 7 :width 48 :height 48})

(defn- gen [opts]
  (wg/generate (world/initial-world {}) opts))

(deftest base-pass-fills-valid-terrain
  (testing "every tile is a known passable-ground/water keyword (no stone in Plan 1)"
    (let [w     (gen (assoc test-opts :passes [wg/base-pass]))
          tiles (set (:tiles (:grid w)))]
      (is (= 48 (:width (:grid w))))
      (is (= 48 (:height (:grid w))))
      (is (every? #{:water :gravel :dirt :grass} tiles)
          (str "unexpected terrain: " tiles)))))

(deftest base-pass-produces-variety
  (testing "a reasonably sized map yields several distinct terrain types
            (guards against a classifier that collapses to one band)"
    (let [w     (gen (assoc test-opts :passes [wg/base-pass]))
          tiles (set (:tiles (:grid w)))]
      (is (>= (count tiles) 3)
          (str "expected >=3 terrain types, got " tiles)))))

(deftest terrain-is-deterministic
  (testing "same seed → identical grid"
    (let [a (gen (assoc test-opts :passes [wg/base-pass]))
          b (gen (assoc test-opts :passes [wg/base-pass]))]
      (is (= (:grid a) (:grid b))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clj -M:test -n sim.worldgen-test`
Expected: FAIL/ERROR — "No namespace: sim.worldgen".

- [ ] **Step 3: Write the pipeline scaffold + terrain phase**

Create `src/sim/worldgen.clj`. (The detail-phase `scatter-pass` and `default-pipeline` are added in Task 6; for now `default-pipeline` holds only `base-pass`.)

```clojure
(ns sim.worldgen
  "Procedural map generation — the GAME layer. Composes a pipeline of pure
   `state -> state` passes over the gridnoise core. Two phases:
     - terrain phase: passes that write ONLY the cell grid (here: base-pass)
     - detail phase:  a 'fat pass' that reads finished terrain and scatters
                      entities (added in Task 6)

   state = {:world <world> :seed <long> :opts <map> :reachable <set-or-nil>}

   Determinism: the terrain phase seeds gridnoise via the master seed; the
   detail phase derives its own java.util.Random from seed + offset, so passes
   are order-insensitive (see the spec's Determinism section)."
  (:require
   [gridnoise.noise :as noise]
   [gridnoise.grid  :as grid]
   [sim.tile        :as tile]
   [sim.entity      :as entity]))

(set! *warn-on-reflection* true)

(def default-opts
  "Tuning knobs. Override any via the opts arg to `generate`."
  {:width 40 :height 20 :seed 12345
   :freq 0.10 :octaves 4 :persistence 0.5
   ;; terrain thresholds (Plan 1 has no :stone — rock arrives in Plan 2)
   :water-level 0.32 :moist-low 0.42 :moist-high 0.70
   ;; detail-phase densities (used in Task 6)
   :tree-count 24 :tree-spacing 2 :wood-count 8 :food-count 10 :stone-count 8})

;; --- terrain phase -------------------------------------------------------

(defn classify
  "Map an elevation + moisture sample to a terrain keyword. Plan 1 emits only
   passable ground + water, so the map is trivially connected (no connectivity
   guard needed yet); :stone is introduced in Plan 2 alongside the guard."
  [^double elev ^double moist {:keys [water-level moist-low moist-high]}]
  (cond
    (< elev water-level) :water
    (< moist moist-low)  :gravel
    (< moist moist-high) :dirt
    :else                :grass))

(defn build-terrain-grid
  "Pure: produce the game grid {:width :height :tiles [...]} from noise.
   Uses gridnoise to make the shape, then renames :cells -> :tiles for the
   game world (the only naming bridge between core and game)."
  [seed {:keys [width height freq octaves persistence] :as opts}]
  (let [elev  (noise/field {:seed (+ (long seed) 1) :freq freq
                            :octaves octaves :persistence persistence})
        moist (noise/field {:seed (+ (long seed) 2) :freq freq
                            :octaves octaves :persistence persistence})
        g     (grid/generate width height
                             (fn [x y] (classify (elev x y) (moist x y) opts)))]
    {:width width :height height :tiles (:cells g)}))

(defn base-pass
  "Terrain phase: write the cell grid from noise. Reads/writes only :world's
   :grid, nothing entity-related."
  [state]
  (assoc-in state [:world :grid]
            (build-terrain-grid (:seed state) (:opts state))))

;; --- pipeline ------------------------------------------------------------

(defn init-state
  "Build the initial pipeline state. Seed resolution: opts :seed, else the
   world's :rng-seed, else the default."
  [world opts]
  (let [seed (or (:seed opts) (:rng-seed world) (:seed default-opts))
        opts (merge default-opts opts {:seed seed})]
    {:world world :seed (long seed) :opts opts :reachable nil}))

(def default-pipeline
  "Ordered vector of passes. Plan 1: terrain phase only (detail phase added in
   Task 6). The pipeline is DATA — add/remove/reorder by editing this vector,
   or override per-call with the :passes opt."
  [base-pass])

(defn generate
  "Pure: (world opts) -> world'. Runs the pipeline (or opts :passes) by
   reducing each pass over the initial state."
  ([world] (generate world {}))
  ([world opts]
   (let [passes (:passes opts default-pipeline)]
     (:world (reduce (fn [s pass] (pass s)) (init-state world opts) passes)))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clj -M:test -n sim.worldgen-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/sim/worldgen.clj test/sim/worldgen_test.clj
git commit -m "feat(worldgen): pipeline scaffold + noise terrain phase"
```

---

## Task 6: `sim.worldgen` — detail phase (`scatter-pass`)

**Files:**
- Modify: `src/sim/worldgen.clj`
- Test: `test/sim/worldgen_test.clj`

- [ ] **Step 1a: Add the `entity` alias to the test ns**

Update the `ns` form at the top of `test/sim/worldgen_test.clj` to add `[sim.entity :as entity]`:

```clojure
(ns sim.worldgen-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world :as world]
   [sim.tile :as tile]
   [sim.entity :as entity]
   [sim.worldgen :as wg]))
```

- [ ] **Step 1b: Append the failing tests**

Append to `test/sim/worldgen_test.clj`:

```clojure
(deftest scatter-places-trees-only-on-grass
  (testing "every tree sits on a :grass tile"
    (let [w    (gen test-opts)
          grid (:grid w)]
      (is (pos? (count (entity/trees w))) "at least one tree placed")
      (doseq [t (entity/trees w)]
        (let [[x y] (:pos t)]
          (is (= :grass (tile/tile-at grid x y))
              (str "tree off-grass at " [x y])))))))

(deftest scatter-places-valid-items
  (testing "items are ground items with known materials"
    (let [w     (gen test-opts)
          items (entity/items w)]
      (is (pos? (count items)) "at least one item placed")
      (doseq [i items]
        (is (= :item (:kind i)))
        (is (some? (:pos i)))
        (is (#{:wood :food :stone} (:material i)))))))

(deftest tree-spacing-respected
  (testing "no two trees are within the tree-spacing (Chebyshev) distance"
    (let [w     (gen test-opts)
          spots (map :pos (entity/trees w))]
      (doseq [[ax ay] spots [bx by] spots
              :when (not= [ax ay] [bx by])]
        (is (>= (max (Math/abs (- ax bx)) (Math/abs (- ay by))) 2)
            (str "trees too close: " [ax ay] [bx by]))))))

(deftest full-generation-is-deterministic
  (testing "same seed → identical grid AND identical entity layout (modulo :id)"
    (let [strip (fn [w] (->> (entity/all-entities w)
                             (map #(dissoc % :id))
                             set))
          a (gen test-opts)
          b (gen test-opts)]
      (is (= (:grid a) (:grid b)))
      (is (= (strip a) (strip b))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `clj -M:test -n sim.worldgen-test`
Expected: FAIL — `scatter-places-trees-only-on-grass` etc. fail because `default-pipeline` has no scatter pass yet (zero trees/items), so the "at least one tree/item" assertions fail.

- [ ] **Step 3: Add the detail phase helpers + `scatter-pass`, and extend the pipeline**

In `src/sim/worldgen.clj`, add the following AFTER `base-pass` and BEFORE the `;; --- pipeline ---` section (so it is defined before `default-pipeline` references it):

```clojure
;; --- detail phase (the "fat pass" — composed of lean helpers) -----------

(defn- chebyshev ^long [[ax ay] [bx by]]
  (max (Math/abs (long (- ax bx))) (Math/abs (long (- ay by)))))

(defn- shuffle-with
  "Deterministic Fisher-Yates using a seeded java.util.Random."
  [^java.util.Random rng coll]
  (let [arr (object-array coll)]
    (loop [i (dec (alength arr))]
      (if (pos? i)
        (let [j   (.nextInt rng (inc i))
              tmp (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp)
          (recur (dec i)))
        (vec arr)))))

(defn- tiles-of
  "All [x y] coords whose terrain == kind, row-major (deterministic order)."
  [grid kind]
  (vec (for [y (range (:height grid)) x (range (:width grid))
             :when (= kind (tile/tile-at grid x y))]
         [x y])))

(defn- rejection-sample
  "Greedily pick up to n coords from candidates such that each accepted coord
   is >= min-dist (Chebyshev) from every other accepted coord. Deterministic
   given rng."
  [rng candidates n min-dist]
  (reduce (fn [acc c]
            (if (>= (count acc) n)
              acc
              (if (every? #(>= (chebyshev c %) min-dist) acc)
                (conj acc c)
                acc)))
          []
          (shuffle-with rng candidates)))

(defn scatter-pass
  "Detail phase: read the finished terrain and decorate it. Trees on grass
   (spaced), wood near trees, food on grass, stone on gravel. Derives its own
   Random so it's independent of any other pass's draws."
  [state]
  (let [world (:world state)
        grid  (:grid world)
        opts  (:opts state)
        rng   (java.util.Random. (+ (long (:seed state)) 1000))
        grass (tiles-of grid :grass)
        trees (rejection-sample rng grass (:tree-count opts) (:tree-spacing opts))
        ;; place trees
        world (reduce (fn [w pos] (entity/add-entity w (entity/make-tree pos)))
                      world trees)
        ;; wood on a passable neighbor of the first N trees
        world (reduce (fn [w [tx ty]]
                        (if-let [spot (->> (grid/neighbors-8 grid tx ty)
                                           (filter (fn [[nx ny]]
                                                     (tile/passable?
                                                      (tile/tile-at grid nx ny))))
                                           first)]
                          (entity/add-entity w (entity/make-item :wood spot))
                          w))
                      world (take (:wood-count opts) trees))
        ;; food on grass (reuse a fresh deterministic shuffle of grass)
        food  (take (:food-count opts) (shuffle-with rng grass))
        world (reduce (fn [w pos] (entity/add-entity w (entity/make-item :food pos)))
                      world food)
        ;; stone on gravel (Plan 2 retargets this to rock adjacency)
        gravel (tiles-of grid :gravel)
        stone  (take (:stone-count opts) (shuffle-with rng gravel))
        world  (reduce (fn [w pos] (entity/add-entity w (entity/make-item :stone pos)))
                       world stone)]
    (assoc state :world world)))
```

Then update `default-pipeline` to include the detail phase:

```clojure
(def default-pipeline
  "Ordered vector of passes. Plan 1: terrain phase (base-pass) then detail
   phase (scatter-pass). The pipeline is DATA — add/remove/reorder by editing
   this vector, or override per-call with the :passes opt."
  [base-pass scatter-pass])
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clj -M:test -n sim.worldgen-test`
Expected: PASS (all base-pass tests still pass because they pin `:passes [wg/base-pass]`; the new scatter tests now pass too).

- [ ] **Step 5: Commit**

```bash
git add src/sim/worldgen.clj test/sim/worldgen_test.clj
git commit -m "feat(worldgen): detail-phase scatter (trees + haulable items)"
```

---

## Task 7: Wire generation into the world lifecycle + REPL

**Files:**
- Modify: `src/sim/world.clj`
- Modify: `dev/user.clj`
- Test: `test/sim/worldgen_test.clj`

- [ ] **Step 1: Add the failing integration test**

Append to `test/sim/worldgen_test.clj`:

```clojure
(deftest reset-world-generate-opt
  (testing "reset-world! {:generate? true} produces a varied, populated world"
    (try
      (world/reset-world! {:generate? true :seed 7 :width 48 :height 48})
      (let [w @world/world]
        (is (> (count (set (:tiles (:grid w)))) 1) "more than one terrain type")
        (is (pos? (count (entity/trees w))))
        (is (pos? (count (entity/items w)))))
      (finally
        (world/reset-world! {})))))  ; restore an empty world for other tests
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.worldgen-test`
Expected: FAIL/ERROR — `reset-world!` does not yet honor `:generate?` (world stays all-grass with no entities).

- [ ] **Step 3: Make `reset-world!` honor `:generate?`**

In `src/sim/world.clj`, add `sim.worldgen` to the `:require` (no cycle: worldgen requires entity/tile/gridnoise, never world):

```clojure
(:require
   [sim.entity :as entity]
   [sim.tile   :as tile]
   [sim.worldgen :as worldgen])
```

Then replace the `reset-world!` defn (lines ~45-47) with:

```clojure
(defn reset-world!
  "Reset the world atom. With {:generate? true}, run procedural generation
   (sim.worldgen/generate) over the fresh world; otherwise leave it
   empty-grass. opts also accepts :width :height :seed and worldgen tuning."
  ([] (reset-world! {}))
  ([opts]
   (let [w (initial-world opts)]
     (reset! world (if (:generate? opts)
                     (worldgen/generate w opts)
                     w)))))
```

- [ ] **Step 4: Add the `generate-world!` REPL helper**

In `dev/user.clj`, add to the `:require` block:

```clojure
   [sim.worldgen  :as worldgen]
   [gridnoise.noise :as noise]
   [gridnoise.grid  :as ngrid]
   [gridnoise.image :as nimage]
```

Then add this defn near `reset-world!` (after it, around line 120):

```clojure
(defn generate-world!
  "Reset to a freshly GENERATED world (terrain + trees + haulable items) and
   print status. Pass {:seed n :width w :height h} to vary it.
     (generate-world! {:seed 42})  ;; then (spawn-pawn! ...) (go!)"
  ([] (generate-world! {}))
  ([opts]
   (world/reset-world! (assoc opts :generate? true))
   (status)
   :generated))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `clj -M:test -n sim.worldgen-test`
Expected: PASS.

- [ ] **Step 6: Run the FULL suite to confirm nothing regressed**

Run: `clj -M:test`
Expected: PASS across all namespaces (`gridnoise.*`, `sim.*`).

- [ ] **Step 7: Commit**

```bash
git add src/sim/world.clj dev/user.clj test/sim/worldgen_test.clj
git commit -m "feat(worldgen): reset-world! :generate? opt + generate-world! REPL helper"
```

---

## Task 8: Render trees (sprite + flora layer + compositor) — manual verification

Render layers issue libGDX GL calls and are not unit-tested (matches the existing terrain/items/pawns/debug convention). Verification is a manual REPL/run check at the end.

**Files:**
- Modify: `src/sim/render/sprites.clj`
- Create: `src/sim/render/layers/flora.clj`
- Modify: `src/sim/render/gdx.clj`

- [ ] **Step 1: Add `tree-region` to sprites**

In `src/sim/render/sprites.clj`, after `pawn-region` (around line 92), add (tiles sheet cell `26.c` = "tree" → 0-based [col 2, row 25]):

```clojure
(def ^:private tree-cell [2 25])  ; tiles 26.c tree

(defn tree-region ^TextureRegion []
  (region :tiles (tree-cell 0) (tree-cell 1)))
```

- [ ] **Step 2: Create the flora layer**

Create `src/sim/render/layers/flora.clj` (mirrors the items layer; one sprite per tree entity, same bottom-left anchor + (height-1-y) flip):

```clojure
(ns sim.render.layers.flora
  "Flora layer: tree entities rendered as the tree sprite. Drawn ABOVE terrain
   and BELOW items/pawns (z: terrain < flora < items < pawns)."
  (:require
   [sim.entity :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  "Render tree entities in WORLD coordinates."
  [world ^SpriteBatch batch tile-size]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)]
    (doseq [t (entity/trees world)]
      (when-let [[x y] (:pos t)]
        (let [px (* (long x) ts)
              py (* (- height (long y) 1) ts)]
          (.draw batch (sprites/tree-region)
                 (float px) (float py) (float ts) (float ts)))))))
```

- [ ] **Step 3: Wire the flora layer into the compositor**

In `src/sim/render/gdx.clj`:

(a) Add to the `:require` block (after the terrain layer require, line ~30):

```clojure
   [sim.render.layers.flora   :as flora-layer]
```

(b) In the `render` method's world block, insert the flora draw between terrain and items (around line 133-134):

```clojure
          (terrain/draw     w b tile-size)
          (flora-layer/draw w b tile-size)
          (items-layer/draw w b tile-size)
          (pawns-layer/draw w b tile-size (ui/selected))
```

Note: the `render` proxy body is captured when the window is created, so this edit takes effect on the next process launch (`clj -M:run` / fresh REPL), not via live hot-reload — see CLAUDE.md "run-loop body" exception.

- [ ] **Step 4: Manual verification**

Launch the game and confirm a generated map renders with trees and items:

```
clj -M:run
```

Then in the REPL (`clj -M:repl` in a separate session, or use the in-process REPL):

```clojure
(in-ns 'user)
(generate-world! {:seed 1})          ;; varied terrain + trees + items
(spawn-pawn! "Dave" [24 24])         ;; a pawn to drive haul
(go!)
```

Confirm by eye:
- Ground shows grass/dirt/gravel variation and water patches (not uniform grass).
- Tree sprites appear on grass tiles.
- Wood/food/stone item sprites appear on the ground.
- `(status)` shows `items:` > 0.
- Optional: drive the existing haul job — `(items-on-map)` to get an item id, `(pawn-jobs)` for the pawn id, then `(assign-haul! <pawn-id> <item-id> [10 10])` and watch the pawn path to it and carry it.
- Optional: dump a noise image to eyeball the field — `(nimage/spit-png! "noise.png" (ngrid/generate 128 128 (let [f (noise/field {:seed 1})] (fn [x y] (f x y)))))`.

- [ ] **Step 5: Commit**

```bash
git add src/sim/render/sprites.clj src/sim/render/layers/flora.clj src/sim/render/gdx.clj
git commit -m "feat(render): tree sprite + flora layer in the compositor"
```

---

## Done criteria

- `clj -M:test` passes across `gridnoise.*` and `sim.*`.
- `(generate-world! {:seed n})` yields a deterministic, varied, populated map.
- Trees and items render; the existing haul job can act on scattered items.
- `gridnoise.*` imports nothing from `sim.*` (grep to confirm: `grep -r "sim\." src/gridnoise` returns nothing).

## Deferred to Plan 2 (do NOT build here)

- `gridnoise.grid/ca-step` + `flood-fill`.
- Pass 2 (CA rock) and `:stone` from elevation in `classify`.
- Pass 3 (connectivity guard) writing `:reachable`; scatter constrained to it.
- Retargeting stone items to rock adjacency.
