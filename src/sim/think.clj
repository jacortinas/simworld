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
   [sim.regions     :as regions]
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
                                               (reservation/reservable? claims (:id %) (:id pawn))
                                               (regions/reachable? world pos (:pos %)))))]
            (when (seq items)
              (let [item    (first (sort-by (juxt #(manhattan pos (:pos %)) :id) items))
                    mat     (:material item)
                    ;; only sites this item can actually reach, so a picked-up
                    ;; delivery never dead-ends (which, pre-fix, would drop-and-retry
                    ;; the same item forever).
                    targets (->> wants
                                 (filter (fn [[b w]] (and (contains? w mat)
                                                          (regions/reachable? world (:pos item) (:pos b)))))
                                 (map first))
                    bp      (first (sort-by (juxt #(manhattan (:pos item) (:pos %)) :id) targets))]
                (when bp
                  (job/deliver (:id item) (:id bp)))))))))))

(defn- adjacent-stand-cells
  "Passable cells 8-adjacent to building b's footprint (but not on it) where a
   pawn can stand to work. A wall promotes to a path blocker, so the builder must
   work from OUTSIDE; this is the set of candidate standing spots."
  [world b]
  (let [{:keys [width height] :as grid} (:grid world)
        fp (set (entity/footprint b))]
    (->> fp
         (mapcat (fn [[x y]]
                   (for [dx [-1 0 1] dy [-1 0 1]
                         :let  [nx (+ (long x) dx) ny (+ (long y) dy) c [nx ny]]
                         :when (and (not (and (zero? dx) (zero? dy)))
                                    (not (fp c))
                                    (tile/in-bounds? width height nx ny)
                                    (tile/passable? (tile/tile-at grid nx ny)))]
                     c)))
         distinct)))

(defn- give-construct
  "Among READY blueprints (material bill fully delivered) this pawn can reserve,
   pick the nearest and mint a :construct job that stands at the nearest passable
   adjacent cell and builds it. Nil when nothing is ready or no standing cell
   exists, so the tree falls through to deliver/haul. The :construct reservation
   then stops two pawns building one site. RimWorld's Construction WorkType
   finish-the-frame WorkGiver."
  [world pawn]
  (let [claims (reservation/claims world)
        pos    (:pos pawn)
        ready  (->> (entity/blueprints world)
                    (filter #(and (empty? (material-needs %))
                                  (reservation/reservable? claims (:id %) (:id pawn)))))
        ;; Pair each ready site with its nearest REACHABLE adjacent stand cell, and
        ;; drop sites with none (boxed in / unreachable). Without this a single
        ;; un-standable ready ghost would mask every buildable site nearer to it,
        ;; and a doomed construct would walk-fail then re-pick the same site forever.
        candidates (keep (fn [b]
                           (when-let [stand (->> (adjacent-stand-cells world b)
                                                 (filter #(regions/reachable? world pos %))
                                                 (sort-by (juxt #(manhattan pos %) identity))
                                                 first)]
                             [b stand]))
                         ready)]
    (when (seq candidates)
      (let [[bp stand] (first (sort-by (fn [[b _]] [(manhattan pos (:pos b)) (:id b)]) candidates))]
        (job/construct (:id bp) stand)))))

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
  {::eat       give-eat
   ::construct give-construct
   ::deliver   give-deliver
   ::haul      give-haul
   ::wander    give-wander})

;; ---------------------------------------------------------------------------
;; Reflexes + the work-priority matrix (RimWorld's Work tab).
;;
;; Selection is three layers of DATA: (1) REFLEXES, always-first survival behaviors
;; that preempt work; (2) WORK TYPES, ordered groups of givers the pawn does in its
;; own PRIORITY order (the per-pawn matrix); (3) wander, the idle fallback. This
;; replaces the old fixed think-tree order: the WORK middle is now per-pawn data
;; (`:work-priorities` on the pawn), so the player sets who does what.
;; ---------------------------------------------------------------------------

(def reflexes
  "Always-first behaviors, in order: [pred-key giver-key]. They preempt all work
   (survival before chores). RimWorld's reflex/constant think nodes."
  [[::hungry? ::eat]])

(def work-types
  "Ordered work types. The order is the COLUMN order, which is also the tiebreak
   WITHIN a priority level. Each work type is a group of givers tried top-to-bottom
   (RimWorld's WorkType -> WorkGivers). Add a work type here (then add its giver):
   Mine/Grow/Cook slot in unchanged."
  [{:id :build :label "Build" :givers [::construct ::deliver]}
   {:id :haul  :label "Haul"  :givers [::haul]}])

(def ^:const default-priority 3)   ; 1 = highest .. 4 = lowest; 0 / absent = disabled

(def default-priorities
  "Default per-pawn priorities: every work type enabled at the middle level, so an
   un-customized pawn reproduces the previous fixed Build-then-Haul order."
  (zipmap (map :id work-types) (repeat default-priority)))

(def priority-cycle
  "Click-cycle order for a Work-tab cell: 1 (highest urgency) .. 4 (lowest), then
   off, then wrap."
  [1 2 3 4 0])

(defn next-priority
  "The priority a Work-tab cell takes when clicked: the next value in priority-cycle.
   nil/absent is treated as the default, so a first click moves off the default."
  [p]
  (let [p   (long (or p default-priority))
        idx (.indexOf ^java.util.List priority-cycle (long p))]
    (nth priority-cycle (mod (inc (max 0 idx)) (count priority-cycle)))))

(def ^:private work-column
  "work-type id -> its column index (the within-priority tiebreak)."
  (into {} (map-indexed (fn [i wt] [(:id wt) i]) work-types)))

(defn pawn-priorities
  "A pawn's work-type -> priority map, merged over the defaults. A priority of 0
   (or a work type the pawn explicitly disables) is OFF. Plain world state on the
   pawn, so it saves for free."
  [pawn]
  (merge default-priorities (:work-priorities pawn)))

(defn- enabled-work
  "The pawn's ENABLED work types (priority > 0), most-urgent first: sorted by
   (priority asc, column index), so a lower priority NUMBER wins and equal
   priorities fall back to the work-types order."
  [pawn]
  (let [prios (pawn-priorities pawn)]
    (->> work-types
         (filter #(pos? (long (get prios (:id %) 0))))
         (sort-by (juxt #(long (get prios (:id %))) #(work-column (:id %)))))))

(defn- work-job
  "First job from the pawn's enabled work types, scanned in priority order; within
   a work type the givers are tried in their listed order. Nil when no enabled work
   type yields a job."
  [world pawn]
  (some (fn [wt]
          (some (fn [g] ((givers g) world pawn)) (:givers wt)))
        (enabled-work pawn)))

(defn deliberate
  "Pure (world, pawn) -> job-or-nil. RimWorld's selection order: REFLEXES first
   (eat when hungry, always wins), then the highest-PRIORITY enabled WORK type that
   yields a job (the per-pawn :work-priorities matrix, see pawn-priorities), then
   wander. The work order is per-pawn data now, not a hardcoded tree."
  [world pawn]
  (or (some (fn [[pred-key giver-key]]
              (when ((preds pred-key) world pawn) ((givers giver-key) world pawn)))
            reflexes)
      (work-job world pawn)
      (give-wander world pawn)))
