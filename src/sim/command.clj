(ns sim.command
  "Player commands issued from input. RimWorld grammar: left-click selects a
   subject (writes ui-state), right-click orders the selection (writes world).

   This is the one namespace that deliberately touches BOTH atoms — it's the
   bridge between 'how you're looking at the game' (ui-state) and 'what the
   game is doing' (world). Keeping that bridge in a single file makes the
   coupling explicit and easy to audit."
  (:require
   [sim.world    :as world]
   [sim.ui-state :as ui]
   [sim.entity   :as entity]
   [sim.inspect  :as inspect]
   [sim.job      :as job]
   [sim.tile     :as tile]
   [sim.zone     :as zone]))

(set! *warn-on-reflection* true)

(defn- in-bounds? [grid tx ty]
  (and (<= 0 (long tx)) (< (long tx) (long (:width grid)))
       (<= 0 (long ty)) (< (long ty) (long (:height grid)))))

(defn pawn-at
  "First pawn standing on tile [tx ty], or nil."
  [w tx ty]
  (->> (entity/pawns w)
       (filter #(= [tx ty] (:pos %)))
       first))

(defn building-at
  "First building entity whose footprint covers tile [tx ty], or nil.
   Footprint-aware via entity/building-at, so a multi-cell building is found
   (and so deconstructed) from any of its cells."
  [w tx ty]
  (entity/building-at w [tx ty]))

(defn can-build?
  "True if a building of footprint `size` ([w h], default [1 1]) anchored at
   origin [ox oy] may be placed: EVERY footprint cell is in-bounds, on passable
   terrain, free of pawns, and free of existing buildings. The rule is
   independent of WHAT is placed (walls and doors share it), so it is the single
   predicate the build cursor and every build-* command read. Pure."
  ([world origin] (can-build? world origin [1 1]))
  ([world [ox oy] [w h]]
   (let [grid (:grid world)
         ox (long ox) oy (long oy)]
     (every? (fn [[x y]]
               (and (in-bounds? grid x y)
                    (tile/passable? (tile/tile-at grid x y))
                    (nil? (pawn-at world x y))
                    (nil? (building-at world x y))))
             (for [dy (range h) dx (range w)] [(+ ox (long dx)) (+ oy (long dy))])))))

(defn door-span
  "Clamp a drag from `start` to `current` to a 1-wide LINE (a gate fills a wall
   gap, which is a line), returning [origin [w h]]: the dominant axis sets the
   length, the other stays 1; origin is the min corner. A zero-length drag (a
   click) yields a single [1 1] cell. Pure."
  [[sx sy] [cx cy]]
  (let [sx (long sx) sy (long sy) cx (long cx) cy (long cy)
        dx (Math/abs (- cx sx))
        dy (Math/abs (- cy sy))]
    (if (>= dx dy)
      [[(min sx cx) sy] [(inc dx) 1]]      ; horizontal line at row sy
      [[sx (min sy cy)] [1 (inc dy)]])))   ; vertical line at column sx

(defn left-click!
  "RimWorld left-click: cycle the selection through the selectable entities on
   the clicked tile. Repeated clicks on one tile advance and wrap; a tile whose
   current selection isn't present starts at its first entity; an empty tile
   clears. Entities are sorted by :id (inspect/selectable-at) for a stable
   cycle order."
  [tx ty]
  (let [ids (mapv :id (inspect/selectable-at @world/world [tx ty]))
        idx (.indexOf ^java.util.List ids (ui/selected)) ; -1 when absent / nil
        nxt (cond
              (empty? ids) nil
              (neg? idx)   (first ids)                   ; new tile or nothing selected yet
              :else        (nth ids (mod (inc idx) (count ids))))]
    (ui/select! nxt))
  nil)

(defn right-click!
  "RimWorld right-click: order the selected pawn. For now the only verb is
   'go here' — move to the clicked tile if it's in-bounds and passable.
   Only PAWNS take orders: selection now spans any selectable kind (trees,
   items), so a non-pawn selection ignores right-click rather than stamping a
   dead job onto it. Context actions (haul item, harvest plant) arrive as we
   add job types and a float-menu; today, move is the universal default."
  [tx ty]
  (when-let [sel (ui/selected)]
    (let [w    @world/world
          grid (:grid w)]
      (when (and (= :pawn (:kind (entity/entity w sel)))
                 (in-bounds? grid tx ty)
                 (tile/passable? (tile/tile-at grid tx ty)))
        (swap! world/world job/assign sel (job/go-to [tx ty]) job/forced-by-player))))
  nil)

(defn commit-stockpile!
  "Commit a dragged rectangle (tile coords `start`..`current`) as a stockpile
   zone. The world-side of placement mode — sim.input handles the drag, this
   records the result. add-stockpile filters to in-bounds/passable/unzoned cells
   and no-ops on an empty result."
  [start current]
  (swap! world/world zone/add-stockpile start current)
  nil)

(defn erase-stockpile!
  "Erase a dragged rectangle (tile coords) from existing stockpile zones — the
   shift-drag twin of commit-stockpile!. Zones left empty are dropped."
  [start current]
  (swap! world/world zone/remove-cells start current)
  nil)

(defn- place-building!
  "Shared placement: add `(make-fn [tx ty])` at the cell if can-build?. The
   world-side of every build tool; sim.input handles the click and cursor. The
   constructor is the only thing that varies between walls and doors (and the
   future build menu just supplies a different one)."
  [tx ty make-fn]
  (swap! world/world
         (fn [w]
           (if (can-build? w [tx ty])
             (entity/add-entity w (make-fn [tx ty]))
             w)))
  nil)

(defn build-wall!
  "Designate a wall at tile [tx ty]: place a wall BLUEPRINT (a pawn hauls the
   material bill and constructs it), if can-build?. RimWorld's build-as-
   designation, not the old instant god-mode wall."
  [tx ty]
  (place-building! tx ty #(entity/make-blueprint :wall %)))

(defn build-door!
  "Designate a 1x1 door at tile [tx ty]: place a door BLUEPRINT, if can-build?.
   The ghost neither blocks paths nor acts as a portal until it is built."
  [tx ty]
  (place-building! tx ty #(entity/make-blueprint :door %)))

(defn build-wall-line!
  "Designate a LINE of wall blueprints along the dragged line (start..current,
   clamped to a dominant-axis line by door-span). Unlike a door span (ONE sized
   entity), each cell becomes its OWN 1x1 wall blueprint, and each is placed only
   where can-build? holds, so dragging across an obstacle fills the buildable cells
   and silently skips the rest. A click (zero-length drag) designates one wall."
  [start current]
  (let [[[ox oy] [w h]] (door-span start current)
        ox (long ox) oy (long oy)
        cells (for [dy (range h) dx (range w)] [(+ ox (long dx)) (+ oy (long dy))])]
    (swap! world/world
           (fn [wd]
             (reduce (fn [wd' cell]
                       (if (can-build? wd' cell)
                         (entity/add-entity wd' (entity/make-blueprint :wall cell))
                         wd'))
                     wd
                     cells))))
  nil)

(defn build-door-span!
  "Designate ONE door spanning the dragged line (start..current, clamped to a line
   by door-span) as a BLUEPRINT, if its whole footprint is buildable. The drag's
   length becomes the door's :size, so this is how a multi-cell gate is designated;
   a click (zero-length drag) designates a 1x1 door."
  [start current]
  (let [[origin size] (door-span start current)]
    (swap! world/world
           (fn [w]
             (if (can-build? w origin size)
               (entity/add-entity w (assoc (entity/make-blueprint :door origin) :size size))
               w))))
  nil)

(defn- remove-building
  "Remove building `b` from world `w`. If it is a partially-delivered BLUEPRINT,
   first scatter its :delivered material back onto its cell as ground items, so
   cancelling a designation REFUNDS the hauled resources (RimWorld's behavior)
   instead of silently destroying them. (A player command, not the pure tick, so
   minting item ids from the global counter here is fine.)"
  [w b]
  (-> (if (entity/blueprint? b)
        (reduce-kv (fn [w material n]
                     (reduce (fn [w _] (entity/add-entity w (entity/make-item material (:pos b))))
                             w (range n)))
                   w (:delivered b {}))
        w)
      (entity/remove-entity (:id b))))

(defn deconstruct-span!
  "Remove every building intersecting the dragged line (start..current). The
   shift-drag erase twin of build-door-span!; building-at is footprint-aware, so
   a multi-cell building is removed from any cell the line touches. A cancelled
   blueprint refunds its delivered material (see remove-building)."
  [start current]
  (let [[[ox oy] [w h]] (door-span start current)
        ox (long ox) oy (long oy)
        cells (for [dy (range h) dx (range w)] [(+ ox (long dx)) (+ oy (long dy))])]
    (swap! world/world
           (fn [wd]
             (reduce (fn [wd' [x y]]
                       (if-let [b (building-at wd' x y)]
                         (remove-building wd' b)
                         wd'))
                     wd
                     cells))))
  nil)

(defn deconstruct-building!
  "Remove the building at tile [tx ty], if any (wall or door alike). Terrain is
   untouched (it was never overwritten), so the cell simply becomes passable
   again."
  [tx ty]
  (swap! world/world
         (fn [w]
           (if-let [b (building-at w tx ty)]
             (remove-building w b)
             w)))
  nil)
