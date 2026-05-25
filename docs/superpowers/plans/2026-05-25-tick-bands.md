# Tick Bands & Staggering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the uniform full-world 30 Hz tick with a tiered, staggered scheduler (`:normal`/`:rare`/`:long` bands + physical bucket index) so per-tick cost scales with active work, not total entity count.

**Architecture:** A new dependency-free `sim.schedule` namespace owns bucket math, a derived per-band bucket index in `(:schedule world)`, and a band→systems registry. `sim.entity` add/remove maintain the index (the lifecycle chokepoint). `sim.simulation/tick` becomes `advance-clock → schedule/run`. Pawns are `:ticker-type :never` (ticked every tick by normal systems; needs/idle work self-throttled via a `due?` predicate); items are `:ticker-type :long` (bucketed). The index is derived, never persisted — rebuilt by `reindex` on load.

**Tech Stack:** Clojure 1.12, `clojure.test` via cognitect test-runner (`clj -M:test`), nippy for save/load.

**Design spec:** `docs/superpowers/specs/2026-05-24-tick-bands-design.md`

---

## File Structure

- **Create** `src/sim/schedule.clj` — bands, bucket math (`home-bucket`/`due-bucket`/`due?`), index (`empty-index`/`register`/`unregister`/`reindex`), system registry (`register-system!`/`clear-systems!`), scheduler (`run*`/`run`). No domain requires (resolves ids via `get-in`) → no namespace cycle.
- **Create** `test/sim/schedule_test.clj` — pure headless tests.
- **Modify** `src/sim/entity.clj` — `:ticker-type` defaults in `make-*`; `add-entity`/`remove-entity` maintain the index.
- **Modify** `src/sim/world.clj` — `initial-world` seeds `:schedule`.
- **Modify** `src/sim/ai.clj` — split `decide` into `advance-job` + `redeliberate`; remove `decide`.
- **Modify** `src/sim/simulation.clj` — define + register built-in systems; `tick → advance-clock → schedule/run`; remove `step-pawns`.
- **Modify** `src/sim/save.clj` — `dissoc :schedule` on save; `reindex` on load.
- **Modify** `test/sim/simulation_test.clj` — `needs-decay` ticks 250 (rare cadence).
- **Modify** `src/sim/job.clj` — one docstring reference (`sim.ai/decide` → `sim.ai/advance-job`).
- **Modify** `CLAUDE.md` — vocabulary, status, files-to-know.

---

## Task 1: `sim.schedule` — bands + pure bucket math

**Files:**
- Create: `src/sim/schedule.clj`
- Test: `test/sim/schedule_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/sim/schedule_test.clj`:

```clojure
(ns sim.schedule-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.schedule :as schedule]))

(deftest bands-shape
  (is (= 1 (:normal schedule/bands)))
  (is (= 125 (:rare schedule/bands)))
  (is (= 1000 (:long schedule/bands))))

(deftest bucket-math
  (testing "home-bucket is id mod interval"
    (is (= 7 (schedule/home-bucket 7 125)))
    (is (= 5 (schedule/home-bucket 130 125))))
  (testing "due-bucket is clock mod interval"
    (is (= 0 (schedule/due-bucket 0 125)))
    (is (= 4 (schedule/due-bucket 129 125))))
  (testing "due? is true exactly when home-bucket == due-bucket"
    (is (true?  (schedule/due? 7 125 7)))     ; clock 7, id 7 -> both bucket 7
    (is (true?  (schedule/due? 132 125 7)))   ; clock 132 -> bucket 7
    (is (false? (schedule/due? 8 125 7)))))   ; clock 8 -> bucket 8, id bucket 7
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.schedule-test`
Expected: FAIL — "Could not locate sim/schedule__init.class" / namespace not found.

- [ ] **Step 3: Write minimal implementation**

Create `src/sim/schedule.clj`:

```clojure
(ns sim.schedule
  "Tiered, staggered tick scheduler — our analogue of RimWorld's TickManager.

   Two staggering mechanisms (see docs/rimworld-engine-internals.md §1.1):
   1. Physical bucket index (rare/long bands): each band is `interval` buckets
      of entity-ids; an entity's home bucket is fixed at (mod id interval).
      Each tick only the bucket at (mod clock interval) runs -> true O(active).
   2. `due?` predicate: same math, used to sub-throttle periodic work inside
      per-tick (normal) systems for the few entities (pawns) we iterate anyway.

   The index in (:schedule world) is DERIVED state — never persisted; rebuilt
   from (:entities world) by `reindex` on load. This namespace has NO domain
   requires (it resolves ids via get-in) so nothing here creates a cycle with
   sim.entity, which depends on it.")

(set! *warn-on-reflection* true)

(def bands
  "Tick interval (in ticks) per band. At 30 Hz: rare ~4.2s, long ~33s —
   RimWorld's 60-TPS cadence (250/2000) halved for our 30 Hz."
  {:normal 1 :rare 125 :long 1000})

(defn home-bucket
  "The fixed bucket an entity-id occupies for a band of `interval` buckets.
   Ids are a monotonic counter, so plain mod distributes evenly."
  ^long [^long id ^long interval]
  (mod id interval))

(defn due-bucket
  "Which bucket fires at this clock value."
  ^long [^long clock ^long interval]
  (mod clock interval))

(defn due?
  "Sub-throttle predicate: is id's home bucket the one firing now? Used by
   normal-band systems to do periodic work on staggered ticks."
  [^long clock ^long interval ^long id]
  (= (home-bucket id interval) (due-bucket clock interval)))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.schedule-test`
Expected: PASS — "Ran 2 tests ... 0 failures, 0 errors."

- [ ] **Step 5: Commit**

```bash
git add src/sim/schedule.clj test/sim/schedule_test.clj
git commit -m "feat(schedule): bands + pure bucket math (home/due/due?)"
```

---

## Task 2: `sim.schedule` — bucket index (empty-index, register, unregister, reindex)

**Files:**
- Modify: `src/sim/schedule.clj`
- Test: `test/sim/schedule_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/sim/schedule_test.clj`:

```clojure
(defn- item [id]  {:id id :kind :item :ticker-type :long})
(defn- rare [id]  {:id id :kind :test :ticker-type :rare})
(defn- never [id] {:id id :kind :pawn :ticker-type :never})

(deftest empty-index-shape
  (let [idx (schedule/empty-index)]
    (is (= 125 (count (:rare idx))))
    (is (= 1000 (count (:long idx))))
    (is (every? #(= #{} %) (:rare idx)))
    (is (nil? (:normal idx)) "normal band is unbucketed")))

(deftest register-places-in-home-bucket
  (let [w {:schedule (schedule/empty-index)}]
    (testing ":long entity lands in its long home bucket"
      (let [w' (schedule/register w (item 130))]
        (is (contains? (get-in w' [:schedule :long 130]) 130))))
    (testing ":rare entity lands in its rare home bucket (id mod 125)"
      (let [w' (schedule/register w (rare 130))]
        (is (contains? (get-in w' [:schedule :rare 5]) 130))))
    (testing ":never entity is not indexed"
      (is (= w (schedule/register w (never 7)))))))

(deftest unregister-removes
  (let [w (-> {:schedule (schedule/empty-index)}
              (schedule/register (item 130)))]
    (is (empty? (get-in (schedule/unregister w (item 130))
                        [:schedule :long 130])))))

(deftest register-no-op-without-schedule
  (testing "worlds lacking :schedule are untouched (backward compatible)"
    (let [w {:entities {}}]
      (is (= w (schedule/register w (item 5)))))))

(deftest reindex-rebuilds-and-is-idempotent
  (let [w  {:entities {1 (item 130) 2 (rare 130) 3 (never 9)}}
        w1 (schedule/reindex w)
        w2 (schedule/reindex w1)]
    (is (contains? (get-in w1 [:schedule :long 130]) 1))
    (is (contains? (get-in w1 [:schedule :rare 5]) 2))
    (is (= (:schedule w1) (:schedule w2)) "reindex is idempotent")))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.schedule-test`
Expected: FAIL — "Unable to resolve symbol: empty-index".

- [ ] **Step 3: Write minimal implementation**

Append to `src/sim/schedule.clj`:

```clojure
(def ^:private bucketed-bands
  "Bands with a physical bucket index. :normal is every-tick, no index."
  [:rare :long])

(defn empty-index
  "Fresh :schedule value: a vector of `interval` empty id-sets per bucketed band."
  []
  (reduce (fn [m band]
            (assoc m band (vec (repeat (long (bands band)) #{}))))
          {}
          bucketed-bands))

(defn register
  "Add an entity to its ticker-type bucket. No-op for :never (or any
   ticker-type not in the index, or a world without :schedule)."
  [world entity]
  (let [tt       (:ticker-type entity)
        interval (bands tt)]
    (if (and interval (get-in world [:schedule tt]))
      (update-in world [:schedule tt (home-bucket (:id entity) interval)]
                 (fnil conj #{}) (:id entity))
      world)))

(defn unregister
  "Remove an entity from its ticker-type bucket. No-op for :never."
  [world entity]
  (let [tt       (:ticker-type entity)
        interval (bands tt)]
    (if (and interval (get-in world [:schedule tt]))
      (update-in world [:schedule tt (home-bucket (:id entity) interval)]
                 (fnil disj #{}) (:id entity))
      world)))

(defn reindex
  "Rebuild :schedule from scratch from (:entities world). Idempotent. Used on
   load and as a repair when the index might be stale or absent."
  [world]
  (reduce register
          (assoc world :schedule (empty-index))
          (vals (:entities world))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.schedule-test`
Expected: PASS — "Ran 7 tests ... 0 failures, 0 errors."

- [ ] **Step 5: Commit**

```bash
git add src/sim/schedule.clj test/sim/schedule_test.clj
git commit -m "feat(schedule): bucket index (empty-index/register/unregister/reindex)"
```

---

## Task 3: `sim.schedule` — system registry + scheduler (`run*`/`run`)

**Files:**
- Modify: `src/sim/schedule.clj`
- Test: `test/sim/schedule_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/sim/schedule_test.clj`:

```clojure
(deftest run*-dispatch-order-and-bands
  (testing "normal systems run every tick with nil due; rare systems get the due bucket"
    (let [w0 (-> {:clock 0
                  :entities {1 (rare 1) 2 (rare 2)}}
                 schedule/reindex)
          ;; normal system appends a marker; rare system appends due ids
          sysmap {:normal [[:mark (fn [w _due] (update w :trace (fnil conj []) :normal))]]
                  :rare   [[:collect (fn [w due] (update w :seen (fnil into [])
                                                         (map :id) due))]]}
          ;; clock 1 -> rare bucket 1 -> entity 1 due
          w1 (schedule/run* (assoc w0 :clock 1) sysmap)]
      (is (= [:normal] (:trace w1)) "normal system ran once")
      (is (= [1] (:seen w1)) "only the due rare entity (id 1) was processed"))))

(deftest run*-rare-coverage-exactly-once
  (testing "over one full rare interval, every rare entity is processed exactly once"
    (let [ids (range 1 30)
          w0  (-> {:clock 0
                   :entities (into {} (map (fn [i] [i (rare i)])) ids)}
                  schedule/reindex)
          sysmap {:rare [[:collect (fn [w due]
                                     (update w :seen (fnil into []) (map :id) due))]]}
          swept (reduce (fn [w c] (schedule/run* (assoc w :clock c) sysmap))
                        w0
                        (range (:rare schedule/bands)))
          freq  (frequencies (:seen swept))]
      (is (= (set ids) (set (keys freq))) "every id processed at least once")
      (is (every? #(= 1 %) (vals freq)) "...and exactly once"))))

(deftest upsert-system-replaces-by-name-preserving-order
  ;; Pure test — does NOT touch the global registry (which sim.simulation
  ;; populates at load), so it can't poison other namespaces' tests.
  (let [f1 (fn [w _] (update w :log (fnil conj []) :a1))
        f2 (fn [w _] (update w :log (fnil conj []) :b))
        f3 (fn [w _] (update w :log (fnil conj []) :a2))
        v  (-> [] (schedule/upsert-system :a f1)
                  (schedule/upsert-system :b f2)
                  (schedule/upsert-system :a f3))]
    (is (= [:a :b] (mapv first v)) "names in order; :a kept its slot")
    (is (= [:a2 :b] (:log (reduce (fn [w [_ f]] (f w nil)) {} v)))
        "the replacement fn (a2) is used; order preserved")))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.schedule-test`
Expected: FAIL — "Unable to resolve symbol: run*".

- [ ] **Step 3: Write minimal implementation**

Append to `src/sim/schedule.clj`:

```clojure
;; --- system registry ---
;; band -> ordered vector of [name f], where f : (world due-entities) -> world.
;; For :normal, due-entities is nil and the system selects its own targets.
;; defonce so REPL reloads don't drop registrations; register-system! replaces
;; by name in place so re-running registration on reload is idempotent.

(defonce ^:private systems (atom {:normal [] :rare [] :long []}))

(defn upsert-system
  "Pure: add [name f] to a band's system vector, or replace the existing entry
   with that name IN PLACE (preserving order). Extracted so it can be tested
   without mutating the global registry."
  [v name f]
  (let [v (or v [])
        i (first (keep-indexed (fn [i [n _]] (when (= n name) i)) v))]
    (if i (assoc v i [name f]) (conj v [name f]))))

(defn register-system!
  "Register (or replace, by name, preserving order) a system fn on a band.
   f : (world due-entities) -> world. Returns name."
  [band name f]
  (swap! systems update band upsert-system name f)
  name)

(defn clear-systems!
  "Drop all registered systems (test/reset helper)."
  []
  (reset! systems {:normal [] :rare [] :long []}))

(defn- due-entities
  "Entity maps in `band`'s bucket firing at `clock`. Missing ids (removed
   without unregister) are skipped defensively."
  [world band ^long clock]
  (let [interval (long (bands band))
        ids      (get-in world [:schedule band (due-bucket clock interval)])]
    (keep #(get-in world [:entities %]) ids)))

(defn run*
  "Pure scheduler step given an explicit systems map. Assumes (:clock world)
   is already advanced for this tick. Normal systems run every tick (nil due);
   rare/long systems run over their due bucket's entities."
  [world systems-map]
  (let [clock    (long (:clock world))
        run-band (fn [w band due]
                   (reduce (fn [w [_ f]] (f w due)) w (get systems-map band)))]
    (-> world
        (run-band :normal nil)
        (as-> w (run-band w :rare (due-entities w :rare clock)))
        (as-> w (run-band w :long (due-entities w :long clock))))))

(defn run
  "Scheduler step using the globally registered systems."
  [world]
  (run* world @systems))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.schedule-test`
Expected: PASS — "Ran 10 tests ... 0 failures, 0 errors."

- [ ] **Step 5: Commit**

```bash
git add src/sim/schedule.clj test/sim/schedule_test.clj
git commit -m "feat(schedule): system registry + run*/run scheduler step"
```

---

## Task 4: `sim.entity` — ticker-type defaults + index maintenance

**Files:**
- Modify: `src/sim/entity.clj` (add `:require [sim.schedule]`; `make-*` add `:ticker-type`; `add-entity`/`remove-entity` maintain index)
- Test: `test/sim/entity_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `test/sim/entity_test.clj`:

```clojure
(deftest ticker-type-defaults
  (is (= :never (:ticker-type (entity/make-pawn "P" [0 0]))))
  (is (= :long  (:ticker-type (entity/make-item :stone [0 0]))))
  (is (= :never (:ticker-type (entity/make-tree [0 0])))))

(deftest add-entity-maintains-schedule-index
  (testing "adding a :long item registers it in its long bucket"
    (let [item (entity/make-item :wood [3 3])
          w    (-> {:entities {} :schedule (sim.schedule/empty-index)}
                   (entity/add-entity item))]
      (is (contains? (get-in w [:schedule :long (sim.schedule/home-bucket
                                                 (:id item) 1000)])
                     (:id item)))))
  (testing "removing it clears the bucket"
    (let [item (entity/make-item :wood [3 3])
          w    (-> {:entities {} :schedule (sim.schedule/empty-index)}
                   (entity/add-entity item))
          w'   (entity/remove-entity w (:id item))]
      (is (empty? (get-in w' [:schedule :long (sim.schedule/home-bucket
                                               (:id item) 1000)]))))))
```

Add `sim.schedule` to the test ns `:require`:

```clojure
(ns sim.entity-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.entity :as entity]
   [sim.schedule :as sim.schedule]))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.entity-test`
Expected: FAIL — `ticker-type-defaults` fails (`:ticker-type` is nil).

- [ ] **Step 3: Write minimal implementation**

In `src/sim/entity.clj`, add the require to the `ns` form:

```clojure
(ns sim.entity
  "..."
  (:require
   [sim.schedule :as schedule]))
```

Add `:ticker-type` to each constructor:
- `make-pawn` map: add `:ticker-type :never`
- `make-item` map: add `:ticker-type :long`
- `make-tree` map: add `:ticker-type :never`

Replace `add-entity` and `remove-entity` (currently `entity.clj:116-122`):

```clojure
(defn add-entity
  "Insert an entity and register it in the schedule index (the lifecycle
   chokepoint). On a world without :schedule, register is a no-op."
  [world entity]
  (-> world
      (assoc-in [:entities (:id entity)] entity)
      (schedule/register entity)))

(defn remove-entity
  "Remove an entity and unregister it from the schedule index."
  [world id]
  (if-let [e (get-in world [:entities id])]
    (-> world
        (update :entities dissoc id)
        (schedule/unregister e))
    world))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `clj -M:test -n sim.entity-test`
Expected: PASS — both new tests green; existing `make-tree-shape` / `trees-query-filters-by-kind` still green (they use `{:entities {}}`, so register is a no-op).

- [ ] **Step 5: Commit**

```bash
git add src/sim/entity.clj test/sim/entity_test.clj
git commit -m "feat(entity): ticker-type defaults + schedule index maintenance"
```

---

## Task 5: `sim.world` — seed `:schedule` in initial-world

**Files:**
- Modify: `src/sim/world.clj` (require schedule; add `:schedule` key)
- Test: `test/sim/simulation_test.clj` (small assertion added)

- [ ] **Step 1: Write the failing test**

Append to `test/sim/simulation_test.clj` (and add `[sim.schedule :as schedule]` to its `:require`):

```clojure
(deftest initial-world-has-empty-schedule
  (let [w (world/initial-world {})]
    (is (= 125 (count (get-in w [:schedule :rare]))))
    (is (= 1000 (count (get-in w [:schedule :long]))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clj -M:test -n sim.simulation-test`
Expected: FAIL — `:schedule` is nil, counts are 0/nil.

- [ ] **Step 3: Write minimal implementation**

In `src/sim/world.clj` add to the `ns` `:require`: `[sim.schedule :as schedule]`.

In `initial-world`, add `:schedule (schedule/empty-index)` to the returned map (alongside `:clock`, `:grid`, etc.). Update the docstring shape comment to mention `:schedule`.

- [ ] **Step 4: Run test to verify it passes**

Run: `clj -M:test -n sim.simulation-test`
Expected: PASS for `initial-world-has-empty-schedule`. (`needs-decay` may now FAIL — fixed in Task 6.)

- [ ] **Step 5: Commit**

```bash
git add src/sim/world.clj test/sim/simulation_test.clj
git commit -m "feat(world): seed empty schedule index in initial-world"
```

---

## Task 6: `sim.ai` + `sim.simulation` — split decide, register systems, rewire tick

**Files:**
- Modify: `src/sim/ai.clj` (add `advance-job`/`redeliberate`; remove `decide`)
- Modify: `src/sim/simulation.clj` (systems + registration; `tick`; remove `step-pawns`)
- Modify: `test/sim/simulation_test.clj` (`needs-decay` ticks 250)
- Modify: `src/sim/job.clj` (docstring reference)

- [ ] **Step 1: Update the behavioral test first**

In `test/sim/simulation_test.clj`, change `needs-decay` to tick past one rare interval so the staggered decay is guaranteed to fire regardless of pawn id:

```clojure
(deftest needs-decay
  (let [w0 (-> (world/initial-world {})
               (entity/add-entity (entity/make-pawn "tester" [0 0])))
        pawn-before (first (entity/pawns w0))
        ;; 250 ticks > 2x rare interval (125) -> >= 1 decay fire for any id
        w-after     (nth (iterate simulation/tick w0) 250)
        pawn-after  (first (entity/pawns w-after))]
    (is (< (get-in pawn-after [:needs :food])
           (get-in pawn-before [:needs :food]))
        "food need should have decayed after 250 ticks (rare cadence)")))
```

- [ ] **Step 2: Run to confirm it fails meaningfully**

Run: `clj -M:test -n sim.simulation-test`
Expected: FAIL — `needs-decay` errors/fails because `simulation/tick` still calls the old `step-pawns`/`ai/decide` path and `:schedule` systems aren't wired (decay won't happen via the new path yet). This confirms the test now targets the new behavior.

- [ ] **Step 3: Rewrite `sim.ai` — split decide**

Replace the body of `src/sim/ai.clj` from `decide` onward. Keep `move-period`, `moves-this-tick?` (stays private), and `random-step` (private) unchanged. Replace `decide` with:

```clojure
(defn advance-job
  "Job-execution step for one pawn — runs every tick. Clears a finished job;
   otherwise advances it (the physical step is gated by the pawn's move
   cadence). Pure: (world pawn) -> world."
  [world pawn]
  (cond
    (and (:job pawn) (job/done? (:job pawn)))
    (entity/update-entity world (:id pawn) assoc :job nil)

    (:job pawn)
    (if (nil? (get-in pawn [:job :path]))
      (job/advance world pawn)
      (if (moves-this-tick? world pawn)
        (job/advance world pawn)
        world))

    :else world))

(defn redeliberate
  "Idle-pawn behavior (currently random wander). The CALLER sub-throttles this
   to the rare band; this fn just acts. Pure: (world pawn) -> world."
  [world pawn]
  (if (:job pawn)
    world
    (entity/update-entity world (:id pawn) #(random-step world %))))
```

Update the `sim.ai` ns docstring to describe `advance-job`/`redeliberate` instead of `decide`.

- [ ] **Step 4: Rewrite `sim.simulation` — systems, registration, tick**

Replace `src/sim/simulation.clj` entirely with:

```clojure
(ns sim.simulation
  "The per-tick world transformation.

   `tick` is a PURE function: (tick world) -> new-world. It advances the clock
   then runs the staggered scheduler (sim.schedule), which dispatches the
   registered band systems. Most things do NOT run every tick — only :normal
   systems do; :rare/:long work runs over the staggered bucket due this tick.
   See docs/superpowers/specs/2026-05-24-tick-bands-design.md."
  (:require
   [sim.ai       :as ai]
   [sim.entity   :as entity]
   [sim.events   :as events]
   [sim.schedule :as schedule]))

(set! *warn-on-reflection* true)

(defn advance-clock [world] (update world :clock inc))

;; ---------------------------------------------------------------------------
;; Systems. Each: (world due-entities) -> world. :normal systems get nil due
;; and select their own targets; :rare/:long get the due bucket's entities.
;; ---------------------------------------------------------------------------

(defn process-events-system
  "Normal: drain and apply the event queue."
  [world _due]
  (let [[evs world'] (events/drain world)]
    (events/apply-events world' evs)))

;; 0.0125 = old per-tick rate (0.0001) x rare interval (125), so the effective
;; decay-per-second is unchanged now that decay fires ~once per 125 ticks.
(def ^:private ^:const need-decay-per-rare 0.0125)

(defn- decay-needs
  "Decrement each need toward 0 by one rare-tick's worth."
  [pawn]
  (update pawn :needs
          (fn [needs]
            (reduce-kv
             (fn [m k v] (assoc m k (max 0.0 (- (double v) need-decay-per-rare))))
             {} needs))))

(defn decay-needs-system
  "Normal + sub-throttled: decay each pawn's needs on its staggered rare tick."
  [world _due]
  (let [clock    (long (:clock world))
        interval (long (:rare schedule/bands))]
    (reduce (fn [w pawn]
              (if (schedule/due? clock interval (:id pawn))
                (entity/update-entity w (:id pawn) decay-needs)
                w))
            world
            (entity/pawns world))))

(defn advance-jobs-system
  "Normal: every pawn with a job advances it (clearing finished jobs)."
  [world _due]
  (reduce (fn [w stale]
            (if-let [pawn (entity/entity w (:id stale))]
              (ai/advance-job w pawn)
              w))
          world
          (entity/pawns world)))

(defn redeliberate-idle-system
  "Normal + sub-throttled: idle pawns pick behavior on their staggered rare tick."
  [world _due]
  (let [clock    (long (:clock world))
        interval (long (:rare schedule/bands))]
    (reduce (fn [w stale]
              (let [pawn (entity/entity w (:id stale))]
                (if (and pawn (schedule/due? clock interval (:id stale)))
                  (ai/redeliberate w pawn)
                  w)))
            world
            (entity/pawns world))))

(defn deteriorate-system
  "Long: would deteriorate the due :item entities. Stub until items deteriorate."
  [_world due]
  ;; `due` is the long bucket's items; no-op for now.
  (reduce (fn [w _item] w) _world due))

;; ---------------------------------------------------------------------------
;; Registration. Idempotent across reloads (register-system! replaces by name).
;; Order within :normal: events -> decay -> advance-jobs -> redeliberate, so
;; needs decay before any decision, matching the old step-pawns ordering.
;; ---------------------------------------------------------------------------

(schedule/register-system! :normal ::process-events process-events-system)
(schedule/register-system! :normal ::decay-needs    decay-needs-system)
(schedule/register-system! :normal ::advance-jobs   advance-jobs-system)
(schedule/register-system! :normal ::redeliberate   redeliberate-idle-system)
(schedule/register-system! :long   ::deteriorate    deteriorate-system)

;; ---------------------------------------------------------------------------

(defn tick
  "Advance the world one fixed-timestep tick: bump the clock, then run the
   staggered scheduler over the registered systems."
  [world]
  (-> world advance-clock schedule/run))
```

- [ ] **Step 5: Fix the stale docstring in `sim.job`**

In `src/sim/job.clj` (~line 7), change the comment `is cleared back to nil by \`sim.ai/decide\`.` to `is cleared back to nil by \`sim.ai/advance-job\`.`

- [ ] **Step 6: Run the simulation + ai-affected tests**

Run: `clj -M:test -n sim.simulation-test`
Expected: PASS — `clock-advances`, `events-drain-on-tick`, `needs-decay` (250 ticks), `initial-world-has-empty-schedule`, `pathfinding-trivial-cases` all green.

Then run the job suite (uses `job/advance` directly, unaffected, but confirm):
Run: `clj -M:test -n sim.job-test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/sim/ai.clj src/sim/simulation.clj src/sim/job.clj test/sim/simulation_test.clj
git commit -m "feat(simulation): tick via staggered scheduler; split ai/decide into advance-job + redeliberate"
```

---

## Task 7: `sim.save` — don't persist the derived index; reindex on load

**Files:**
- Modify: `src/sim/save.clj` (require schedule; dissoc on save; reindex on load)
- Test: `test/sim/schedule_test.clj` (reindex-from-stripped equivalence — pure, no file I/O)

- [ ] **Step 1: Write the failing test**

Append to `test/sim/schedule_test.clj`:

```clojure
(deftest reindex-equals-incremental-after-strip
  (testing "stripping :schedule then reindex reproduces the incremental index"
    (let [incremental (-> {:entities {} :schedule (schedule/empty-index)}
                          (as-> w (reduce (fn [w e] (assoc-in w [:entities (:id e)] e))
                                          w [(item 130) (rare 130) (never 9)]))
                          ;; mimic add-entity registering each
                          (as-> w (reduce schedule/register w (vals (:entities w)))))
          stripped    (dissoc incremental :schedule)
          rebuilt     (schedule/reindex stripped)]
      (is (= (:schedule incremental) (:schedule rebuilt))))))
```

- [ ] **Step 2: Run to verify it passes already (reindex correctness)**

Run: `clj -M:test -n sim.schedule-test`
Expected: PASS — this asserts the property that lets us drop `:schedule` from saves. (It exercises existing `reindex`; if it fails, the bug is in Task 2.)

- [ ] **Step 3: Implement save/load changes**

In `src/sim/save.clj`, add to the `ns` `:require`: `[sim.schedule :as schedule]`.

Change `save!`'s freeze line to strip the derived index:

```clojure
   (nippy/freeze-to-file (path slot) (dissoc world :schedule))
```

Change `load!`'s thaw to rebuild the index (handles both new stripped saves and old saves lacking `:schedule`):

```clojure
(defn load!
  "Thaw saves/<slot>.npy and rebuild the derived schedule index. Returns the
   world value (does NOT touch the atom). Throws if missing or corrupt."
  ([] (load! "autosave"))
  ([slot]
   (schedule/reindex (nippy/thaw-from-file (path slot)))))
```

- [ ] **Step 4: Run the full suite**

Run: `clj -M:test`
Expected: PASS — all namespaces green (schedule, entity, simulation, job, command, inspect, selection-layer, ui-state, sprites, debug-layer, worldgen, gridnoise).

- [ ] **Step 5: Commit**

```bash
git add src/sim/save.clj test/sim/schedule_test.clj
git commit -m "feat(save): drop derived schedule index from saves; reindex on load"
```

---

## Task 8: Docs + REPL sanity + final verification

**Files:**
- Modify: `CLAUDE.md`
- Test: full suite + a REPL smoke test

- [ ] **Step 1: Update `CLAUDE.md`**

Under "Loop & timing model", add a bullet defining tick bands:

```markdown
- **Tick bands & staggering** — `sim.schedule` tiers updates into `:normal`
  (every tick), `:rare` (125 ticks ~4.2s), `:long` (1000 ticks ~33s), keyed by
  each entity's `:ticker-type`. Rare/long use a physical bucket index
  (`(:schedule world)`) so only ~1/interval of a band runs per tick (true
  O(active)). Pawns are `:ticker-type :never` — ticked every tick by normal
  systems; their needs/idle work self-throttle via `schedule/due?`. The index
  is DERIVED — not persisted; `schedule/reindex` rebuilds it on load.
```

Under "Load-bearing architectural decisions", add:

```markdown
- **Scheduler, not a uniform tick.** `simulation/tick` = `advance-clock ->
  schedule/run`. Per-tick work is registered band systems, not a hand-written
  pipeline. New periodic work = `register-system!` on a band; new bucketed
  entity kinds = a `:ticker-type`. `entity/add-entity`/`remove-entity` are the
  index chokepoint (they call `schedule/register`/`unregister`).
```

Under "Files to know", add:
```markdown
- `src/sim/schedule.clj` — tick-band scheduler: bucket math, derived bucket
  index, band->systems registry, `run`
```

Update the "What's working" section to note Layer status includes tick bands.

- [ ] **Step 2: REPL smoke test (manual, headless-ish)**

Run a quick non-GL REPL check that ticking a populated world is stable and needs decay over time:

```bash
clj -M:dev -e "(require '[sim.world :as w] '[sim.simulation :as s] '[sim.entity :as e]) (let [w0 (-> (w/initial-world {}) (e/add-entity (e/make-pawn \"a\" [1 1])) (e/add-entity (e/make-item :stone [2 2]))) wn (nth (iterate s/tick w0) 300)] (println :clock (:clock wn) :food (get-in (first (e/pawns wn)) [:needs :food]) :item-bucketed? (boolean (some #(contains? % (:id (first (e/items wn)))) (get-in wn [:schedule :long])))))"
```

Expected: prints `:clock 300`, a `:food` value `< 1.0` (decayed), and `:item-bucketed? true`.

- [ ] **Step 3: Full suite + reflection check**

Run: `clj -M:test`
Expected: PASS — 0 failures, 0 errors across all namespaces.

(Reflection warnings surface at compile during the test run because every touched ns has `(set! *warn-on-reflection* true)`; there should be none from the new code.)

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document tick bands & staggering scheduler in CLAUDE.md"
```

---

## Self-Review notes (addressed)

- **Spec coverage:** bands+intervals (T1), two mechanisms (buckets T2/T3 + `due?` T1 used in T6), ticker-type defaults (T4), `:schedule` data shape (T2/T5), `sim.schedule` API (T1–T3), built-in systems + registration (T6), tick integration (T6), entity-lifecycle chokepoint (T4), save/load reindex (T7), movement-speed separation (T6 — `moves-this-tick?` stays inside `advance-job`), testing incl. coverage property (T3), docs (T8). The deterioration stub (T6) matches the spec's "long-band wiring + stub."
- **Type/name consistency:** `home-bucket`/`due-bucket`/`due?`/`empty-index`/`register`/`unregister`/`reindex`/`register-system!`/`clear-systems!`/`run*`/`run` used consistently across tasks; system fns are `(world due-entities) -> world` everywhere; `need-decay-per-rare = 0.0125` defined once.
- **No placeholders:** every code step is complete; the only stub (`deteriorate-system`) is intentional and spec-sanctioned.
