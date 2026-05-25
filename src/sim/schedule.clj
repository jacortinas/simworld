(ns sim.schedule
  "Tiered, staggered tick scheduler — our analogue of RimWorld's TickManager.

   Two staggering mechanisms (see docs/rimworld-engine-internals.md sec 1.1):
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

;; ---------------------------------------------------------------------------
;; Bucket index. :normal is every-tick and unbucketed; only :rare/:long get a
;; physical index, since that's where avoiding the full-set scan pays off.
;; ---------------------------------------------------------------------------

(def ^:private bucketed-bands [:rare :long])

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

;; ---------------------------------------------------------------------------
;; System registry. band -> ordered vector of [name f], f : (world due) -> world.
;; For :normal, due is nil and the system selects its own targets. defonce so
;; REPL reloads keep registrations; register-system! replaces by name in place
;; so re-running registration on reload is idempotent.
;; ---------------------------------------------------------------------------

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
