(ns sim.entity
  "Pawn/entity creation and queries.

   Entities are persistent maps keyed by integer id in `(:entities world)`.
   This is the 'maps of components' strategy from the architecture notes —
   readable, REPL-friendly, fine up to a few hundred entities. When we later
   need 10k+ entities we'll move to column-store, but the public API in this
   namespace is what callers depend on, so it stays stable.

   Two DERIVED indexes ride alongside `(:entities world)`, both rebuilt on load
   and stripped on save (the map of entities is the only source of truth):
   `:ticker-type` (:never/:rare/:long) places an entity in the scheduler's
   rare/long bucket index (see sim.schedule); `:kind` (:pawn/:item/:tree) places
   it in the `:kinds` index — a per-kind sorted set of ids that pawns/items/trees
   read, turning their O(all-entities) filter into an O(of-that-kind) lookup.
   add-entity/remove-entity are the lifecycle chokepoint that keeps BOTH in sync;
   reindex/`reindex-kinds` rebuild them."
  (:require
   [sim.defs     :as defs]
   [sim.schedule :as schedule]))

(set! *warn-on-reflection* true)

(def ^:private id-counter (atom 0))

(defn- next-id! [] (swap! id-counter inc))

(def ^:private template-keys
  "Thing-def keys copied verbatim onto a constructed entity (construction-time
   content — designer-tunable). Engine fields (:id/:def/:pos) and runtime
   scaffolding (:job/:carrying/:carried-by, added by the typed wrappers) are NOT
   here: content and mechanism never mix in one map. Keep in sync with
   sim.defs/::thing-entry's opt-keys — a content key absent here is silently
   dropped at construction (acceptable at small scale; derive one from the other
   when thing-defs proliferate)."
  [:kind :ticker-type :move-ticks :needs :material :traits :skills :graphic :blocks-path? :portal?])

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
;; a ground item is carried by no one.
;;
;; An item lives at `:pos [x y]` while on the ground. On pickup its `:pos`
;; becomes nil and `:carried-by` is the pawn's id; the pawn's `:carrying` is the
;; item's id; drop reverses both. The item stores only its `:material` keyword
;; (copied from the thing-def); the material DEFS (weight/char) are content in
;; sim.defs (resources/defs/materials.edn), resolved at use time.
;; ---------------------------------------------------------------------------

(defn make-pawn
  "Construct a new pawn (the :colonist thing-def) named `name` at [x y].
   Pure — does NOT insert into the world. Use `sim.world/spawn-pawn` for that."
  [name pos]
  (-> (make-thing :colonist pos)
      (assoc :name name :job nil :carrying nil)))

(defn make-item
  "Construct an item of thing-def `def-id` at [x y]. `def-id` is the item
   thing-def id (== material keyword for the current 1:1 content:
   :wood/:food/:stone)."
  [def-id pos]
  (assoc (make-thing def-id pos) :carried-by nil))

;; Trees are passable flora — A* reads only terrain, so they never affect
;; pathfinding (RimWorld's model: walk through, chop later). Inert until a
;; future chop job; rendered now.
(defn make-tree
  "Construct a tree (the :tree thing-def) at [x y]. Pure — does NOT insert."
  [pos]
  (make-thing :tree pos))

;; A wall: a built, path-blocking edifice. Inert (no band system reads :building
;; in Spec 1); its :blocks-path? feeds sim.pathgrid. The :built state is runtime
;; scaffolding (like a pawn's :job) -- Specs 2/3 add :blueprint/:frame states.
(defn make-building
  "Construct a built wall (the :wall thing-def) at [x y]. Pure -- does NOT insert."
  [pos]
  (-> (make-thing :wall pos)
      (assoc :state :built)))

;; A door: a built, PASSABLE building. Shares make-building's :built scaffolding,
;; but carries :portal? true (copied from the :door thing-def), which sim.pathgrid
;; reads to mark its cell a portal and sim.regions reads to flood it as its own
;; 1-cell region. Inert otherwise (no band system reads it yet).
(defn make-door
  "Construct a built door (the :door thing-def) at [x y]. Pure -- does NOT insert."
  [pos]
  (-> (make-thing :door pos)
      (assoc :state :built)))

;; ---------------------------------------------------------------------------
;; Queries — operate on the world map.
;; Defined before any helpers that consume them so a cold-start load resolves
;; forward references correctly (REPL reloads are forgiving; `clj -M` is not).
;; ---------------------------------------------------------------------------

(defn entity
  "Look up an entity by id. Returns nil if not present."
  [world id]
  (get-in world [:entities id]))

(defn all-entities
  "Sequence of all entities in the world."
  [world]
  (vals (:entities world)))

;; pawns/items/trees read the derived :kinds index, NOT (vals :entities): an
;; O(of-that-kind) lookup, ascending by id (the index is a sorted-set per kind).
;; `keep` (not `map`) defensively skips an id missing from :entities, mirroring
;; sim.schedule/due-entities. The ascending order makes per-kind iteration
;; deterministic without a per-call sort.

(defn pawns
  "Sequence of all pawn entities, ascending by id."
  [world]
  (keep #(entity world %) (get-in world [:kinds :pawn])))

(defn items
  "Sequence of all item entities, ascending by id."
  [world]
  (keep #(entity world %) (get-in world [:kinds :item])))

(defn trees
  "Sequence of all tree entities, ascending by id."
  [world]
  (keep #(entity world %) (get-in world [:kinds :tree])))

(defn buildings
  "Sequence of all building entities, ascending by id."
  [world]
  (keep #(entity world %) (get-in world [:kinds :building])))

(defn items-at
  "Sequence of all items currently at [x y]."
  [world pos]
  (->> (items world)
       (filter #(= pos (:pos %)))))

(defn entity-at
  "Return the first entity at [x y], or nil. Linear scan — fine at toy scale,
   will need a spatial index later."
  [world pos]
  (->> (all-entities world)
       (filter #(= pos (:pos %)))
       first))

;; ---------------------------------------------------------------------------
;; Kind index — DERIVED state mirroring :schedule: a per-kind sorted set of ids,
;; maintained at the add-entity/remove-entity chokepoint, rebuilt from :entities
;; by reindex-kinds on load (sim.save strips it before freezing). Sorted sets
;; (not hash sets) make pawns/items/trees iterate ascending by id.
;; ---------------------------------------------------------------------------

(def ^:private known-kinds [:pawn :item :tree])

(defn empty-kinds
  "Fresh :kinds value: an empty sorted-set per known kind. add-entity fnil-creates
   any kind absent here, so a future kind indexes itself without editing this."
  []
  (zipmap known-kinds (repeat (sorted-set))))

(defn- index-kind
  "Add an entity's id to its kind's set, creating the set if absent."
  [world entity]
  (update-in world [:kinds (:kind entity)] (fnil conj (sorted-set)) (:id entity)))

(defn- unindex-kind
  "Remove an entity's id from its kind's set. No-op on a world without :kinds."
  [world entity]
  (if (get-in world [:kinds (:kind entity)])
    (update-in world [:kinds (:kind entity)] disj (:id entity))
    world))

(defn reindex-kinds
  "Rebuild :kinds from scratch from (:entities world). Idempotent. Used on load
   and as a repair when the index might be stale or absent."
  [world]
  (reduce index-kind
          (assoc world :kinds (empty-kinds))
          (vals (:entities world))))

;; ---------------------------------------------------------------------------
;; Updates — return modified world maps
;; ---------------------------------------------------------------------------

(defn add-entity
  "Insert an entity and register it in the derived indexes (the lifecycle
   chokepoint): the :kinds set for its kind and the schedule bucket for its
   ticker-type. index-kind fnil-creates a missing :kinds; schedule/register is a
   no-op on a world without :schedule."
  [world entity]
  (-> world
      (assoc-in [:entities (:id entity)] entity)
      (index-kind entity)
      (schedule/register entity)))

(defn remove-entity
  "Remove an entity and unregister it from both derived indexes."
  [world id]
  (if-let [e (get-in world [:entities id])]
    (-> world
        (update :entities dissoc id)
        (unindex-kind e)
        (schedule/unregister e))
    world))

(defn update-entity
  "Apply f to the entity with the given id; (f entity & args) -> new-entity."
  [world id f & args]
  (if (get-in world [:entities id])
    (apply update-in world [:entities id] f args)
    world))

(defn move-entity
  "Set the position of an entity to [x y]."
  [world id pos]
  (update-entity world id assoc :pos pos))

;; ---------------------------------------------------------------------------
;; Id-counter maintenance. The id counter is the ONE piece of entity state that
;; lives outside the world map (it's process-global; contrast :next-zone-id,
;; which sim.zone keeps IN the world and so saves for free). Because the counter
;; isn't frozen into a save, a save loaded into a fresh process starts the
;; counter at 0 while the loaded entities already hold high ids — the next spawn
;; would collide. Re-seed past the loaded ids on load (see sim.save/load!).
;; ---------------------------------------------------------------------------

(defn max-entity-id
  "Highest entity id in `world`, or 0 if there are none. Pure."
  ^long [world]
  (reduce max 0 (keys (:entities world))))

(defn seed-id-counter!
  "Advance the global id counter past every id already in `world`, never
   backward. Returns the new counter value. Call after loading a save into a
   fresh process so the next constructed entity can't reuse a loaded id."
  [world]
  (reset! id-counter (max @id-counter (max-entity-id world))))
