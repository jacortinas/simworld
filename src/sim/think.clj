(ns sim.think
  "The pawn think-tree: a DATA priority tree walked depth-first, first valid
   leaf wins (RimWorld's ThinkNode_Priority model). Nodes reference predicates
   and job-givers by KEYWORD, resolved through the `preds`/`givers` registries —
   so the tree's structure is inert data (EDN-ready, moddable later) while the
   behavior lives in code, exactly as RimWorld's XML references class names.

   `deliberate` is pure: (world, pawn) -> job-or-nil. A leaf JobGiver mints a
   job (pure data); `sim.ai/redeliberate` routes it through `sim.job/assign`.
   Adding a behavior = add a node + register one giver; the walker never changes."
  (:require
   [sim.defs        :as defs]
   [sim.entity      :as entity]
   [sim.job         :as job]
   [sim.reservation :as reservation]
   [sim.rng         :as rng]
   [sim.tile        :as tile]
   [sim.zone        :as zone]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Predicates — (world, pawn) -> boolean. Referenced by keyword from the tree.
;; ---------------------------------------------------------------------------

(defn- hungry?
  "True when the pawn's :food need is below the food def's :seek-below threshold
   (content, from resources/defs/needs.edn). Absent threshold -> never hungry."
  [_world pawn]
  (let [food      (double (get-in pawn [:needs :food] 1.0))
        threshold (double (or (:seek-below (defs/need :food)) 0.0))]
    (< food threshold)))

(def preds
  {::hungry? hungry?})

;; ---------------------------------------------------------------------------
;; Job-givers — (world, pawn) -> job-or-nil. Referenced by keyword from the tree.
;; ---------------------------------------------------------------------------

(defn- manhattan ^long [[ax ay] [bx by]]
  (+ (Math/abs (- (long ax) (long bx)))
     (Math/abs (- (long ay) (long by)))))

(defn- give-eat
  "Among ground food items this pawn can reserve, pick the nearest by Manhattan
   distance (ties broken by id for determinism) and mint an :eat job. Pathing is
   deferred to job execution, so this stays cheap (no per-candidate A*)."
  [world pawn]
  (let [pos    (:pos pawn)
        claims (reservation/claims world)
        foods  (->> (entity/items world)
                    (filter #(and (= :food (:material %))
                                  (:pos %)
                                  (reservation/reservable? claims (:id %) (:id pawn)))))]
    (when (seq foods)
      (let [target (first (sort-by (juxt #(manhattan pos (:pos %)) :id) foods))]
        (job/eat (:id target))))))

(defn- material-needs
  "Blueprint b's outstanding material bill: material -> units still needed
   (cost - delivered), positive entries only. Reads :cost from the def."
  [b]
  (let [cost      (:cost (defs/thing (:def b)) {})
        delivered (:delivered b {})]
    (into {} (keep (fn [[m n]]
                     (let [r (- (long n) (long (get delivered m 0)))]
                       (when (pos? r) [m r])))
                   cost))))

(defn- in-flight-deliveries
  "Map of [blueprint-id material] -> count of active :deliver jobs carrying that
   material toward that blueprint. This is the OVER-DELIVERY guard reservations
   can't give: a claim is per-item, so without this two pawns would both fetch
   stone for a blueprint that needs only one more unit."
  [world]
  (reduce (fn [m p]
            (let [j (:job p)]
              (if (and j (= :deliver (:type j))
                       (not (#{:complete :failed} (:state j))))
                (if-let [mat (:material (entity/entity world (:item-id j)))]
                  (update m [(:blueprint-id j) mat] (fnil inc 0))
                  m)
                m)))
          {}
          (entity/pawns world)))

(defn- give-deliver
  "Among loose material items this pawn can reserve whose material some blueprint
   still wants (its bill minus what is already delivered AND already in flight),
   pick the nearest to the pawn (Manhattan, ties by :id) and deliver it to the
   nearest still-wanting blueprint to that item. Nil when nothing is wanted or no
   matching reservable item exists, so the tree falls through. The in-flight cap
   stops the colony over-fetching for a site that's nearly satisfied. In RimWorld
   terms this is the Construction WorkType's haul-to-blueprint WorkGiver."
  [world pawn]
  (let [bps (entity/blueprints world)]
    (when (seq bps)
      (let [in-flight (in-flight-deliveries world)
            ;; per blueprint, the UNCOMMITTED need: outstanding bill minus in-flight
            wants     (->> bps
                           (map (fn [b]
                                  [b (into {} (keep (fn [[m r]]
                                                      (let [u (- (long r) (long (get in-flight [(:id b) m] 0)))]
                                                        (when (pos? u) [m u])))
                                                    (material-needs b)))]))
                           (remove (fn [[_ w]] (empty? w))))]
        (when (seq wants)
          (let [wanted-mats (into #{} (mapcat (comp keys second)) wants)
                claims      (reservation/claims world)
                pos         (:pos pawn)
                items       (->> (entity/items world)
                                 (filter #(and (:pos %)
                                               (contains? wanted-mats (:material %))
                                               (reservation/reservable? claims (:id %) (:id pawn)))))]
            (when (seq items)
              (let [item    (first (sort-by (juxt #(manhattan pos (:pos %)) :id) items))
                    mat     (:material item)
                    targets (->> wants (filter (fn [[_ w]] (contains? w mat))) (map first))
                    bp      (first (sort-by (juxt #(manhattan (:pos item) (:pos %)) :id) targets))]
                (when bp
                  (job/deliver (:id item) (:id bp)))))))))))

(def ^:const ^:private wander-radius 5)

(defn- wander-target
  "A passable cell within wander-radius of the pawn, or nil if boxed in. Seeded
   deterministically from (world :rng-seed, tick, pawn id): the choice is a pure
   function of (world, pawn) — same inputs pick the same cell, and it varies
   across pawns and across ticks WITHOUT coupling to entity iteration order
   (the determinism the parallel-job path needs; see sim.rng)."
  [world pawn]
  (let [{:keys [width height] :as grid} (:grid world)
        [x y]  (:pos pawn)
        cells  (for [dx (range (- wander-radius) (inc wander-radius))
                     dy (range (- wander-radius) (inc wander-radius))
                     :let  [nx (+ (long x) dx) ny (+ (long y) dy)]
                     :when (and (not (and (zero? dx) (zero? dy)))
                                (tile/in-bounds? width height nx ny)
                                (tile/passable? (tile/tile-at grid nx ny)))]
                 [nx ny])
        seed   (rng/derive-seed (:rng-seed world 0) (:clock world 0) (:id pawn))]
    (first (rng/pick seed cells))))

(defn- give-wander
  "Mint a go-to job to a random nearby passable cell (the aimless fallback)."
  [world pawn]
  (when-let [c (wander-target world pawn)]
    (job/go-to c)))

(defn- give-haul
  "Among grounded items NOT already in a stockpile that this pawn can reserve,
   pick the nearest to the pawn (Manhattan, ties by :id) and haul it to the
   nearest stockpile cell to that item (ties by [x y]). Nil when there is nothing
   to haul or no stockpile exists, so the tree falls through to wander. An item
   already on a stockpile cell is excluded, so a hauled item is never re-picked
   (self-terminating). In RimWorld terms this is the :hauling WorkType's first
   WorkGiver."
  [world pawn]
  (let [pos   (:pos pawn)
        cells (zone/stockpile-cells world)]
    (when (seq cells)
      (let [claims (reservation/claims world)
            loose  (->> (entity/items world)
                        (filter #(and (:pos %)
                                      (not (contains? cells (:pos %)))
                                      (reservation/reservable? claims (:id %) (:id pawn)))))]
        (when (seq loose)
          (let [item (first (sort-by (juxt #(manhattan pos (:pos %)) :id) loose))
                dest (first (sort-by (juxt #(manhattan (:pos item) %) identity) cells))]
            (job/haul (:id item) dest)))))))

(def givers
  {::eat     give-eat
   ::deliver give-deliver
   ::haul    give-haul
   ::wander  give-wander})

;; ---------------------------------------------------------------------------
;; The tree + walker
;; ---------------------------------------------------------------------------

(def default-tree
  "Priority order: satisfy hunger, then deliver material to a build site, then
   generic hauling to a stockpile, else wander. Deliver outranks haul so a colony
   stocks its blueprints before tidying loose items. New behaviors slot in as
   nodes; the walker never changes."
  {:type :priority
   :children [{:type  :conditional
               :pred  ::hungry?
               :child {:type :job-giver :give ::eat}}
              {:type :job-giver :give ::deliver}
              {:type :job-giver :give ::haul}
              {:type :job-giver :give ::wander}]})

(defn- walk
  "Walk one node, returning a job or nil. Priority: first valid child wins.
   Conditional: descend only if the pred holds. Job-giver: call the giver."
  [world pawn node]
  (case (:type node)
    :priority    (some #(walk world pawn %) (:children node))
    :conditional (when ((preds (:pred node)) world pawn)
                   (walk world pawn (:child node)))
    :job-giver   ((givers (:give node)) world pawn)))

(defn deliberate
  "Walk the think-tree for `pawn`; return the first job a leaf yields, or nil.
   Pure. The 2-arg form uses `default-tree`; the 3-arg takes a custom tree."
  ([world pawn] (deliberate world pawn default-tree))
  ([world pawn tree] (walk world pawn tree)))
