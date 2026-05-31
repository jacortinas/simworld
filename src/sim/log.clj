(ns sim.log
  "Chronological debug log of what's happening in the sim.

   Distinct from `sim.events`: events drive gameplay forward; the log just
   records what already happened. The log lives in (:log world) as a
   PersistentQueue of entries, oldest first (head = oldest). Bounded to the last
   `max-entries` items; past the cap, eviction pops the oldest in O(1).

   Each entry is a flat map with at least `:tick` and `:type`; other keys
   are entry-specific. Flat for REPL ergonomics — `(:item entry)` beats
   `(get-in entry [:data :item])` once you've written it ten times.

   Conventions for `:type`:
     :event             - an sim.events event was applied (payload in :event)
     :job/assigned      - a pawn got a job (with :pawn :job :source :priority)
     :job/pickup        - haul phase: item picked up (:pawn :item :material)
     :job/drop          - haul phase: item dropped (:pawn :item :at)
     :job/complete      - job finished successfully (:pawn :job)
     :job/failed        - job aborted (:pawn :job :reason?)
   New types are open — just append a map with a new :type.")

(set! *warn-on-reflection* true)

(def ^:const max-entries
  "Cap on log size. Past this, oldest entries drop off."
  500)

(def empty-log
  "An empty log. A PersistentQueue so the bounded-ring eviction is O(1) (pop the
   oldest at the head) rather than recopying the whole vector on every append
   once full. Seq order is oldest-first, just like the old vector."
  clojure.lang.PersistentQueue/EMPTY)

(defn append
  "Pure: return `world` with `data` (a map) appended to `:log`. `:tick` is
   stamped automatically from the world's clock. Past the cap, the oldest entry
   is popped in O(1). A non-queue `:log` (a fresh `[]` or a hand-built world) is
   coerced to a queue on first append, so eviction always drops the OLDEST,
   never the newest."
  [world data]
  (let [entry (assoc data :tick (:clock world))
        cur   (:log world)
        q     (conj (if (instance? clojure.lang.PersistentQueue cur)
                      cur
                      (into empty-log cur))
                    entry)]
    (assoc world :log (if (> (count q) max-entries) (pop q) q))))

(defn entries
  "All log entries as a vector, oldest first. The stored form is a queue; this
   materializes it so `recent` can index and the REPL gets a familiar vector."
  [world]
  (vec (or (:log world) empty-log)))

(defn recent
  "Last `n` entries, oldest first."
  [world n]
  (let [es (entries world)
        c  (count es)]
    (subvec es (max 0 (- c (long n))))))

(defn of-type
  "Entries whose `:type` matches. `type-or-set` may be a single keyword or a
   set of keywords (e.g. #{:job/pickup :job/drop})."
  [world type-or-set]
  (let [pred (if (set? type-or-set) type-or-set #{type-or-set})]
    (filterv (fn [e] (pred (:type e))) (entries world))))

(defn since
  "Entries whose `:tick` is >= `from-tick`."
  [world from-tick]
  (filterv (fn [e] (>= (long (:tick e)) (long from-tick))) (entries world)))

(defn for-pawn
  "Entries involving `pawn-id` (i.e. `:pawn` field matches)."
  [world pawn-id]
  (filterv (fn [e] (= pawn-id (:pawn e))) (entries world)))

(defn clear
  "Pure: return `world` with the log emptied. Useful before reproducing a bug."
  [world]
  (assoc world :log empty-log))
