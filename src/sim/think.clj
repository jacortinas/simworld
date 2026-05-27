(ns sim.think
  "The pawn think-tree: a DATA priority tree walked depth-first, first valid
   leaf wins (RimWorld's ThinkNode_Priority model). Nodes reference predicates
   and job-givers by KEYWORD, resolved through the `preds`/`givers` registries —
   so the tree's structure is inert data (EDN-ready, moddable later) while the
   behavior lives in code, exactly as RimWorld's XML references class names.

   `deliberate` is pure: (world, pawn) -> job-or-nil. A leaf JobGiver mints a
   job (pure data); `sim.ai/redeliberate` routes it through `sim.job/assign`.
   Adding a behavior = add a node + register one giver; the walker never changes.

   See docs/superpowers/specs/2026-05-25-think-tree-eat-design.md."
  (:require
   [sim.defs        :as defs]
   [sim.entity      :as entity]
   [sim.job         :as job]
   [sim.reservation :as reservation]
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
  (let [pos   (:pos pawn)
        foods (->> (entity/items world)
                   (filter #(and (= :food (:material %))
                                 (:pos %)
                                 (reservation/can-reserve? world (:id %) (:id pawn)))))]
    (when (seq foods)
      (let [target (first (sort-by (juxt #(manhattan pos (:pos %)) :id) foods))]
        (job/eat (:id target))))))

(def ^:const ^:private wander-radius 5)

(defn- wander-target
  "A random passable cell within wander-radius of the pawn, or nil if boxed in."
  [world pawn]
  (let [{:keys [width height] :as grid} (:grid world)
        [x y]  (:pos pawn)
        cells  (for [dx (range (- wander-radius) (inc wander-radius))
                     dy (range (- wander-radius) (inc wander-radius))
                     :let  [nx (+ (long x) dx) ny (+ (long y) dy)]
                     :when (and (not (and (zero? dx) (zero? dy)))
                                (tile/in-bounds? width height nx ny)
                                (tile/passable? (tile/tile-at grid nx ny)))]
                 [nx ny])]
    (when (seq cells) (rand-nth (vec cells)))))

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
   WorkGiver — see docs/superpowers/specs/2026-05-27-auto-haul-design.md."
  [world pawn]
  (let [pos   (:pos pawn)
        cells (zone/stockpile-cells world)]
    (when (seq cells)
      (let [loose (->> (entity/items world)
                       (filter #(and (:pos %)
                                     (not (zone/cell-zoned? world (:pos %)))
                                     (reservation/can-reserve? world (:id %) (:id pawn)))))]
        (when (seq loose)
          (let [item (first (sort-by (juxt #(manhattan pos (:pos %)) :id) loose))
                dest (first (sort-by (juxt #(manhattan (:pos item) %) identity) cells))]
            (job/haul (:id item) dest)))))))

(def givers
  {::eat    give-eat
   ::haul   give-haul
   ::wander give-wander})

;; ---------------------------------------------------------------------------
;; The tree + walker
;; ---------------------------------------------------------------------------

(def default-tree
  "Priority order: satisfy hunger, then do work (haul), else wander. New
   behaviors slot in as nodes; the walker never changes."
  {:type :priority
   :children [{:type  :conditional
               :pred  ::hungry?
               :child {:type :job-giver :give ::eat}}
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
