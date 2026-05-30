(ns sim.entity
  "Pawn/entity creation and queries.

   Entities are persistent maps keyed by integer id in `(:entities world)`.
   This is the 'maps of components' strategy from the architecture notes —
   readable, REPL-friendly, fine up to a few hundred entities. When we later
   need 10k+ entities we'll move to column-store, but the public API in this
   namespace is what callers depend on, so it stays stable.

   `:ticker-type` (:never/:rare/:long) places an entity in the scheduler's
   rare/long bucket index. add-entity/remove-entity are the lifecycle
   chokepoint that keeps that index in sync — see sim.schedule."
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

(defn pawns
  "Sequence of all pawn entities."
  [world]
  (filter #(= :pawn (:kind %)) (all-entities world)))

(defn items
  "Sequence of all item entities."
  [world]
  (filter #(= :item (:kind %)) (all-entities world)))

(defn trees
  "Sequence of all tree entities."
  [world]
  (filter #(= :tree (:kind %)) (all-entities world)))

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
;; Updates — return modified world maps
;; ---------------------------------------------------------------------------

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
