# Thing-Defs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make entity *types* data-driven by adding a `:thing` def category (per-type construction templates) and routing the `make-*` constructors through a generic `make-thing` that reads them.

**Architecture:** A fourth Def DB category, `:thing`, holds one EDN construction template per entity type (`:colonist`, `:tree`, `:wood`, `:food`, `:stone`). A new `sim.entity/make-thing` reads the immutable template, copies its content-keys onto a fresh instance, and stamps engine fields (`:id`/`:def`/`:pos`); the three existing constructors become thin wrappers, so no call site changes. Construction fails fast on an unknown def-id (unlike use-time lookups, which degrade).

**Tech Stack:** Clojure 1.12 on the JVM, `clojure.spec.alpha` (load-time def validation), `clojure.test` via the cognitect test-runner (`clj -M:test`).

**Source spec:** `docs/superpowers/specs/2026-05-29-thing-defs-design.md`

---

## Conventions for this plan

- **Run all tests:** `clj -M:test` · **one namespace:** `clj -M:test -n sim.<ns>-test`. Runs directly in the shell on this macOS machine; headless tests need no windowing alias.
- **Source order matters at cold start** (`clj -M:test` compiles each file top-to-bottom in one pass): define a fn *before* its callers within a file. `make-thing` must appear before the wrappers; `template-keys` before `make-thing`.
- **The Def DB is a shared global** (`sim.defs/db`). Tests that swap in alternate sources must restore it (see Task 2, Step 1) — `make-thing`'s fail-fast turns a partial registry into a hard error.
- **Never commit without explicit approval** is the repo norm; the `git commit` steps below are written out, but pause for the operator's go-ahead per their workflow.

## File Structure

| File | New/Modified | Responsibility |
|---|---|---|
| `resources/defs/things.edn` | **New** | The thing-def content: one construction template per entity type. |
| `src/sim/defs.clj` | Modified | Register the `:thing` category (source path, spec, `entry-spec`) + add the `thing` lookup. |
| `src/sim/entity.clj` | Modified | `make-thing` core + `template-keys`; `make-pawn`/`make-item`/`make-tree` become wrappers; require `sim.defs`. |
| `test/sim/defs_test.clj` | Modified | Thing-def load/lookup/validation coverage; harden the fixture to restore the global registry. |
| `test/sim/entity_test.clj` | Modified | `make-thing` template/back-ref/fail-fast coverage; require `sim.defs` for the restore fixture. |
| `CLAUDE.md` | Modified | Mark the construction-time seam closed; add `things.edn` to files-to-know. |
| `docs/superpowers/specs/2026-05-25-content-state-split-design.md` | Modified | Update the deferred-seam note to "resolved." |

---

## Task 1: Add the `:thing` def category to the Def DB

**Files:**
- Create: `resources/defs/things.edn`
- Modify: `src/sim/defs.clj` (specs after line ~39; `entry-spec` ~41-45; `default-sources` ~52-55; new `thing` lookup near the other lookups ~139-152)
- Test: `test/sim/defs_test.clj`

- [ ] **Step 1: Create the thing-def content file**

Create `resources/defs/things.edn`:

```clojure
;; Thing-defs — loaded by sim.defs into (:thing db). Content, not code.
;; A thing-def is a CONSTRUCTION TEMPLATE for an entity TYPE (RimWorld's
;; ThingDef): an entity instance references its type by :def and copies these
;; construction-time defaults at sim.entity/make-thing time. :kind is the cheap
;; category axis (:pawn/:item/:tree) the entity queries filter on; :ticker-type
;; is the scheduler band (:never/:rare/:long). Item types carry a :material
;; keyword referencing the orthogonal materials.edn stuff table (weight, etc.).
{:colonist {:kind :pawn :ticker-type :never :move-ticks 15
            :needs {:food 1.0 :rest 1.0 :recreation 1.0}
            :traits #{} :skills {}}
 :tree     {:kind :tree :ticker-type :never}
 :wood     {:kind :item :ticker-type :long :material :wood}
 :food     {:kind :item :ticker-type :long :material :food}
 :stone    {:kind :item :ticker-type :long :material :stone}}
```

- [ ] **Step 2: Write the failing tests**

Add to `test/sim/defs_test.clj` (the ns already has `use-fixtures` reloading defs before each test):

```clojure
(deftest thing-lookup-returns-construction-template
  (testing "a known thing-def resolves to its template"
    (let [c (defs/thing :colonist)]
      (is (= :pawn (:kind c)))
      (is (= :never (:ticker-type c)))
      (is (= 15 (:move-ticks c)))
      (is (= {:food 1.0 :rest 1.0 :recreation 1.0} (:needs c)))))
  (testing "an item thing-def references its material orthogonally"
    (is (= :item (:kind (defs/thing :wood))))
    (is (= :wood (:material (defs/thing :wood)))))
  (testing "an unknown thing-def is nil (callers fail-fast at construction)"
    (is (nil? (defs/thing :no-such-thing)))))

(deftest thing-defs-load-and-validate
  (testing "the bundled registry includes the :thing category"
    (is (contains? (defs/load!) :thing)))
  (testing "ids enumerates every shipped thing type"
    (is (= #{:colonist :tree :wood :food :stone} (defs/ids :thing)))))

(deftest load-rejects-malformed-thing-entry
  (testing "an unknown :ticker-type throws (the band set is closed)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)ticker"
         (defs/load-sources! {:thing [{:colonist {:kind :pawn :ticker-type :sometimes}}]}))))
  (testing "a missing required :kind throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)kind"
         (defs/load-sources! {:thing [{:bad {:ticker-type :never}}]})))))
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `clj -M:test -n sim.defs-test`
Expected: FAIL/ERROR — `defs/thing` is unresolved (the var doesn't exist yet) and/or the `:thing` category is absent.

- [ ] **Step 4: Add the thing specs to `sim.defs`**

In `src/sim/defs.clj`, after the existing `::need-entry` spec (around line 39), add:

```clojure
(s/def ::kind        keyword?)                 ; open: adding :building is a data edit
(s/def ::ticker-type #{:never :rare :long})    ; closed: the scheduler's bands
(s/def ::move-ticks  (s/and number? pos?))
(s/def ::needs       (s/map-of keyword? (s/and number? #(<= 0.0 (double %) 1.0))))
(s/def ::material    keyword?)                 ; item thing-def -> materials.edn stuff ref
(s/def ::traits      (s/coll-of keyword? :kind set?))
(s/def ::skills      (s/map-of keyword? number?))
(s/def ::thing-entry (s/keys :req-un [::kind ::ticker-type]
                             :opt-un [::move-ticks ::needs ::material ::traits ::skills]))
```

`::material` is **not** an existing spec in `defs.clj` (only `::material-entry` is), and `::thing-entry` references it for item defs that carry `:material`, so the `(s/def ::material keyword?)` line above is required — without it `s/valid?` throws "Unable to resolve spec" when validating `:wood`/`:food`/`:stone`.

- [ ] **Step 5: Register the category (spec map + source path)**

In `src/sim/defs.clj`, add `:thing ::thing-entry` to `entry-spec`:

```clojure
(def ^:private entry-spec
  "Category -> the spec each of its entries must satisfy."
  {:terrain  ::terrain-entry
   :material ::material-entry
   :need     ::need-entry
   :thing    ::thing-entry})
```

And add `:thing ["defs/things.edn"]` to `default-sources`:

```clojure
(def default-sources
  {:terrain  ["defs/terrain.edn"]
   :material ["defs/materials.edn"]
   :need     ["defs/needs.edn"]
   :thing    ["defs/things.edn"]})
```

- [ ] **Step 6: Add the `thing` lookup**

In `src/sim/defs.clj`, alongside the other lookups (after `material`, around line 142), add:

```clojure
(defn thing
  "Thing-def (construction template) for type `k`, or nil. Unlike the terrain/
   material use-time lookups (which fall back), a nil here means an undefined
   type: callers that CONSTRUCT from a def-id treat it as an error — see
   sim.entity/make-thing's fail-fast."
  [k]
  (get-in @db [:thing k]))
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `clj -M:test -n sim.defs-test`
Expected: PASS — all prior 11 tests plus the 3 new ones (the top-level `(load!)` now picks up `things.edn`).

- [ ] **Step 8: Commit**

```bash
git add resources/defs/things.edn src/sim/defs.clj test/sim/defs_test.clj
git commit -F - <<'EOF'
feat(defs): add :thing construction-template category

A per-type thing-def registry (RimWorld's ThingDef): :kind/:ticker-type/
:move-ticks/:needs/:material/:traits/:skills per entity type, spec-validated
at load (closed :ticker-type set catches band typos). The thing lookup returns
nil for unknown ids — construction callers fail-fast on that, use-time readers
degrade. Foundation for make-thing.
EOF
```

---

## Task 2: Route the constructors through `make-thing`

**Files:**
- Modify: `src/sim/entity.clj` (require `sim.defs`; replace `make-pawn`/`make-item`/`make-tree` block, lines ~22-76, with `template-keys` + `make-thing` + wrappers)
- Test: `test/sim/entity_test.clj` (require `sim.defs` + restore fixture; add `make-thing` tests)
- Modify: `test/sim/defs_test.clj` (harden the fixture to restore the global registry)

- [ ] **Step 1: Harden the `defs_test` fixture to restore the global registry**

`make-thing` (next steps) throws on a missing def, so a sibling test ns that runs after `defs_test` must not inherit a partial registry. `load-sources-merges-later-wins` resets the global db to `:material` only; reload **after** each test too, so `defs_test` always leaves the full registry behind.

In `test/sim/defs_test.clj`, change the fixture:

```clojure
;; Reload the bundled defs before AND after each test: before so the shared
;; global registry is full regardless of order; after so a test that swapped in
;; alternate sources (load-sources!) can't leave it partial for a sibling ns
;; whose entities construct via sim.entity/make-thing (which fails fast on a
;; missing def).
(use-fixtures :each (fn [t] (defs/load!) (t) (defs/load!)))
```

- [ ] **Step 2: Run `defs_test` to confirm the fixture change is green**

Run: `clj -M:test -n sim.defs-test`
Expected: PASS (14 tests — behavior unchanged, just an extra reload).

- [ ] **Step 3: Write the failing `make-thing` tests**

In `test/sim/entity_test.clj`, add `sim.defs` to the requires and a restore fixture, then the tests:

```clojure
(ns sim.entity-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs     :as defs]
   [sim.entity   :as entity]
   [sim.schedule :as schedule]))

;; make-* now reads thing-defs from the shared global registry; reload the
;; bundled defs before each test so a sibling ns swapping in alternate sources
;; can't leave the registry partial. Mirrors sim.defs-test's fixture.
(use-fixtures :each (fn [t] (defs/load!) (t)))
```

Add these deftests:

```clojure
(deftest make-thing-stamps-def-backref-and-template
  (testing "a constructed pawn carries its :def back-ref and the template content"
    (let [p (entity/make-thing :colonist [3 4])]
      (is (= :colonist (:def p)))
      (is (= :pawn (:kind p)))
      (is (= :never (:ticker-type p)))
      (is (= 15 (:move-ticks p)))
      (is (= {:food 1.0 :rest 1.0 :recreation 1.0} (:needs p)))
      (is (= [3 4] (:pos p)))
      (is (some? (:id p)))))
  (testing "an item thing copies its material from the def"
    (let [w (entity/make-thing :wood [1 1])]
      (is (= :item (:kind w)))
      (is (= :long (:ticker-type w)))
      (is (= :wood (:material w)))
      (is (= :wood (:def w))))))

(deftest make-thing-throws-on-unknown-def
  (testing "constructing an undefined type fails fast (no silent fallback)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown thing-def"
                          (entity/make-thing :no-such-type [0 0])))))

(deftest wrappers-preserve-entity-shape
  (testing "make-pawn yields a named pawn with runtime scaffolding"
    (let [p (entity/make-pawn "Riker" [2 2])]
      (is (= :pawn (:kind p)))
      (is (= "Riker" (:name p)))
      (is (= :colonist (:def p)))
      (is (contains? p :job))
      (is (contains? p :carrying))))
  (testing "make-item yields an item with :carried-by scaffolding"
    (is (contains? (entity/make-item :stone [0 0]) :carried-by))))
```

- [ ] **Step 4: Run the new tests to verify they fail**

Run: `clj -M:test -n sim.entity-test`
Expected: FAIL/ERROR — `entity/make-thing` is unresolved.

- [ ] **Step 5: Implement `make-thing` + wrappers in `sim.entity`**

In `src/sim/entity.clj`, add `[sim.defs :as defs]` to the `:require`:

```clojure
  (:require
   [sim.defs     :as defs]
   [sim.schedule :as schedule]))
```

Then **replace** the constructor block (current `make-pawn` through `make-tree`, lines ~22-76) with the following. Order matters: `template-keys` then `make-thing` then the wrappers (cold-start compiles top-to-bottom):

```clojure
(def ^:private template-keys
  "Thing-def keys copied verbatim onto a constructed entity (construction-time
   content — designer-tunable). Engine fields (:id/:def/:pos) and runtime
   scaffolding (:job/:carrying/:carried-by, added by the typed wrappers) are NOT
   here: content and mechanism never mix in one map."
  [:kind :ticker-type :move-ticks :needs :material :traits :skills])

(defn make-thing
  "Construct an entity instance of thing-def `def-id` at [x y]. Reads the
   immutable def for construction-time content (kind, ticker-type, needs,
   move-ticks, material, traits, skills) and stamps the engine fields (id, :def
   back-ref, pos). Pure — does NOT insert into the world (use sim.world/spawn-pawn
   or sim.entity/add-entity).

   Throws if `def-id` is unknown: constructing an undefined type is a caller bug,
   not a degraded runtime reference (contrast defs/terrain, which falls back to
   grass). This fail-fast asymmetry is what makes construction-time def reads
   safe — a silent fallback would spawn the wrong entity."
  [def-id [x y]]
  (let [d (or (defs/thing def-id)
              (throw (ex-info (str "Unknown thing-def: " def-id) {:def-id def-id})))]
    (-> (select-keys d template-keys)
        (assoc :id (next-id!) :def def-id :pos [x y]))))

;; ---------------------------------------------------------------------------
;; Typed constructors — thin wrappers over make-thing. They add only runtime
;; scaffolding (never content): a pawn starts with no job and carries nothing;
;; a ground item is carried by no one. An item's :pos becomes nil and
;; :carried-by the pawn's id on pickup; the pawn's :carrying is the item id.
;; ---------------------------------------------------------------------------

(defn make-pawn
  "Construct a new pawn (the :colonist thing-def) named `name` at [x y]."
  [name pos]
  (-> (make-thing :colonist pos)
      (assoc :name name :job nil :carrying nil)))

(defn make-item
  "Construct an item of thing-def `type` at [x y]. `type` is the item thing-def
   id (== material keyword for the current 1:1 content: :wood/:food/:stone)."
  [type pos]
  (assoc (make-thing type pos) :carried-by nil))

(defn make-tree
  "Construct a tree (the :tree thing-def) at [x y]. Pure — does NOT insert."
  [pos]
  (make-thing :tree pos))
```

- [ ] **Step 6: Run the entity tests to verify they pass**

Run: `clj -M:test -n sim.entity-test`
Expected: PASS — the new `make-thing` tests plus the unchanged `make-tree-shape`, `trees-query-filters-by-kind`, `ticker-type-defaults`, and `add-entity-maintains-schedule-index`.

- [ ] **Step 7: Run the FULL suite to prove no regressions**

This is the migration guard: ~40 test call-sites construct entities via `make-pawn`/`make-item`/`make-tree`, and several namespaces share the global registry, so the full run proves both behavior preservation and order-independence.

Run: `clj -M:test`
Expected: PASS — 0 failures, 0 errors across all `sim.*-test` namespaces.

- [ ] **Step 8: Commit**

```bash
git add src/sim/entity.clj test/sim/entity_test.clj test/sim/defs_test.clj
git commit -F - <<'EOF'
feat(entity): construct entities from thing-defs via make-thing

make-thing reads a type's construction template from the Def DB, copies its
content keys onto a fresh instance, and stamps :id/:def/:pos; make-pawn/
make-item/make-tree become thin wrappers (no call-site churn). Unknown def-id
throws — construction fails fast where use-time lookups degrade. The entity now
carries a :def back-ref. defs-test restores the shared registry after each test
so fail-fast construction stays order-independent.
EOF
```

---

## Task 3: Record the closed seam in the docs

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-05-25-content-state-split-design.md`

- [ ] **Step 1: Update the seam note in `CLAUDE.md`**

In the "Content/state split (the Def DB)" decision, replace the **Seam left** sentence:

Find:
```
construction-time content (pawn starting `:needs`, `:ticker-type` defaults) is
still hard-coded in `make-*` — making it def-driven would force a
defs-before-entities load order, so it waits for a thing-def step.
```
Replace with:
```
construction-time content (pawn starting `:needs`, `:ticker-type`, `:move-ticks`)
is now def-driven via the `:thing` category (`resources/defs/things.edn`):
`sim.entity/make-thing` reads a per-type template and the `make-*` constructors
are thin wrappers. The feared load-order coupling was a non-issue — `sim.defs`
depends on nothing in `sim`, so the require graph loads defs before any
construction (`sim.entity` → `sim.defs`). Entities carry a `:def` back-ref.
Construction fails fast on an unknown def-id (use-time lookups still degrade).
See `docs/superpowers/specs/2026-05-29-thing-defs-design.md`.
```

- [ ] **Step 2: Add `things.edn` + the `thing` lookup to files-to-know in `CLAUDE.md`**

In the `src/sim/defs.clj` files-to-know entry, add `thing` to the listed lookups and add a line for the new EDN. Find:
```
`material`/`need`/`need-decay`/`ids`; `load!`/`load-sources!` (the mod seam).
```
Replace with:
```
`material`/`need`/`need-decay`/`thing`/`ids`; `load!`/`load-sources!` (the mod seam).
```
And under the `resources/defs/...` files-to-know line, append `things.edn` (per-type construction templates: kind, ticker-type, starting needs, move-ticks, material).

- [ ] **Step 3: Mark the deferral resolved in the content/state-split spec**

In `docs/superpowers/specs/2026-05-25-content-state-split-design.md`, "Out of scope / future" section, find:
```
- **Thing-defs for ticker-type + starting-needs** (construction-time content) —
  deferred to avoid the defs-before-entities load-order coupling.
```
Replace with:
```
- **Thing-defs for ticker-type + starting-needs** (construction-time content) —
  RESOLVED in `2026-05-29-thing-defs-design.md`: the `:thing` def category +
  `sim.entity/make-thing`. The load-order coupling was a non-issue (the require
  graph loads `sim.defs` before any construction).
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md docs/superpowers/specs/2026-05-25-content-state-split-design.md
git commit -F - <<'EOF'
docs(thing-defs): record the closed construction-time seam

Update the content/state-split decision + files-to-know in CLAUDE.md and mark
the deferred thing-def seam resolved in the content/state-split spec.
EOF
```

---

## Self-Review

**1. Spec coverage** — every spec section maps to a task:

| Spec section | Task |
|---|---|
| `defs/things.edn` content shape | T1 S1 |
| `:thing` in `default-sources` + `entry-spec` + specs | T1 S4-S5 |
| `thing` lookup | T1 S6 |
| `make-thing` + `select-keys` template merge | T2 S5 |
| Wrappers delegate (no call-site churn) | T2 S5 |
| `sim.entity` requires `sim.defs` (load-order) | T2 S5 |
| `:material` denormalized onto item instance | T2 S5 (from def via `template-keys`) + T2 S3 test |
| Fail-fast on unknown def-id | T2 S5 + T2 S3 test |
| `:def` additive / save back-compat | covered by full-suite run (T2 S7) incl. save round-trip tests; no schema change needed |
| Behavioral equivalence (migration guard) | T2 S7 (full suite, incl. `ticker-type-defaults`) |
| Testing: load/spec/construction/fail-fast/never-saved | T1 S2, T2 S3, T2 S7 |
| Docs (CLAUDE.md + spec note) | T3 |

**2. Placeholder scan** — no TBD/TODO; every code step shows complete code; every run step gives the exact command and expected result. One conditional note (T1 S4: add `::material` only if not already defined) is an explicit instruction, not a placeholder.

**3. Type consistency** — `make-thing` signature `[def-id [x y]]` is used identically by all three wrappers; `template-keys` lists exactly the keys the EDN templates provide and the tests assert (`:kind :ticker-type :move-ticks :needs :material :traits :skills`); `defs/thing` returns the same map shape the tests read; the `:def` back-ref key name is consistent across `make-thing`, the tests, and the docs.

**Note on the never-saved invariant:** the spec lists a save round-trip assertion. If the existing `sim.save`/`sim.defs` test suite does not already assert "no `:thing`/`:defs` key in a saved world," that assertion is structurally guaranteed (defs live in `sim.defs`, never in the world map) and the full-suite run will confirm no save regression. If the operator wants it explicit, add one assertion to the existing save test — but it is not required for correctness.
