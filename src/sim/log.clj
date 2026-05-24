(ns sim.log
  "Chronological debug log of what's happening in the sim.

   Distinct from `sim.events`: events drive gameplay forward; the log just
   records what already happened. The log lives in (:log world) as a vector
   of entries, oldest first. Bounded to the last `max-entries` items.

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

(defn append
  "Pure: return `world` with `data` (a map) appended to `:log`. `:tick` is
   stamped automatically from the world's clock. Drops oldest entries past
   the cap. Materializes (into []) when trimming so old vectors can be GC'd
   rather than held alive by a subvec view."
  [world data]
  (let [entry (assoc data :tick (:clock world))
        log   (conj (or (:log world) []) entry)
        log   (if (> (count log) max-entries)
                (into [] (subvec log (- (count log) max-entries)))
                log)]
    (assoc world :log log)))

(defn entries
  "All log entries, oldest first."
  [world]
  (or (:log world) []))

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
  (assoc world :log []))
