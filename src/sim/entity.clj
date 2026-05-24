(ns sim.entity
  "Pawn/entity creation and queries.

   Entities are persistent maps keyed by integer id in `(:entities world)`.
   This is the 'maps of components' strategy from the architecture notes —
   readable, REPL-friendly, fine up to a few hundred entities. When we later
   need 10k+ entities we'll move to column-store, but the public API in this
   namespace is what callers depend on, so it stays stable.")

(set! *warn-on-reflection* true)

(def ^:private id-counter (atom 0))

(defn- next-id! [] (swap! id-counter inc))

(defn make-pawn
  "Construct a new pawn at the given [x y]. Pure — does NOT insert into the world.
   Use `sim.world/spawn-pawn` for that."
  [name [x y]]
  {:id       (next-id!)
   :kind     :pawn
   :name     name
   :pos      [x y]
   :needs    {:food 1.0 :rest 1.0 :recreation 1.0}
   :traits   #{}
   :skills   {}
   :job      nil
   :carrying nil
   :path     nil})

;; ---------------------------------------------------------------------------
;; Items
;;
;; An item lives at `:pos [x y]` while on the ground. When picked up by a
;; pawn, its `:pos` becomes nil and `:carried-by` is the pawn's id; the
;; pawn's `:carrying` is the item's id. Drop reverses both.
;; ---------------------------------------------------------------------------

(def item-defs
  "Static info for each material. :char is what the renderer shows."
  {:stone {:char \s :weight 1.0}
   :wood  {:char \w :weight 0.7}
   :food  {:char \f :weight 0.3}})

(defn make-item
  "Construct an item of the given material at [x y]."
  [material [x y]]
  {:id         (next-id!)
   :kind       :item
   :material   material
   :pos        [x y]
   :carried-by nil})

;; ---------------------------------------------------------------------------
;; Trees -- passable flora. A* reads only terrain, so trees never affect
;; pathfinding (RimWorld's model: walk through, chop later). Inert until a
;; future chop job; rendered now.
;; ---------------------------------------------------------------------------

(defn make-tree
  "Construct a tree entity at [x y]. Pure -- does NOT insert into the world."
  [[x y]]
  {:id      (next-id!)
   :kind    :tree
   :pos     [x y]
   :species :tree})

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
  [world entity]
  (assoc-in world [:entities (:id entity)] entity))

(defn remove-entity
  [world id]
  (update world :entities dissoc id))

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
