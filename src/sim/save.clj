(ns sim.save
  "Save/load via Nippy.

   The headline pitch of building this in Clojure: the data structure that
   runs the game is the save file. No DTOs, no schema definitions, no Unity
   ScriptableObject dance.

   Caveat the architecture notes flag: Nippy makes serialization easy; it
   does NOT make schema migration easy. When we add or rename keys on
   :entities or :grid, old saves will load with the old shape and the new
   code may not handle them. Migration logic is a TODO for when we have a
   shape worth migrating."
  (:require
   [sim.entity     :as entity]
   [sim.schedule   :as schedule]
   [taoensso.nippy :as nippy]))

(set! *warn-on-reflection* true)

(def ^:dynamic *save-dir* "saves")

(defn- ensure-dir! []
  (.mkdirs (java.io.File. ^String *save-dir*)))

(defn- path
  ^String [slot]
  (str *save-dir* "/" slot ".npy"))

(defn save!
  "Freeze the world to saves/<slot>.npy. Default slot is 'autosave'.
   The :schedule bucket index is DERIVED state — stripped before freezing
   (smaller saves, no drift) and rebuilt on load via schedule/reindex."
  ([world] (save! world "autosave"))
  ([world slot]
   (ensure-dir!)
   (nippy/freeze-to-file (path slot) (dissoc world :schedule))
   slot))

(defn load!
  "Thaw saves/<slot>.npy, rebuild the derived schedule index, and re-seed the
   process-global entity id counter past the loaded ids. Returns the world value
   (does NOT touch the WORLD atom — but DOES advance the id counter, the one
   entity invariant a frozen save can't carry; see entity/seed-id-counter!).
   Handles both new (stripped) saves and older saves lacking :schedule. Throws
   if the file is missing or corrupt."
  ([] (load! "autosave"))
  ([slot]
   (let [world (schedule/reindex (nippy/thaw-from-file (path slot)))]
     (entity/seed-id-counter! world)
     world)))
