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
  "True if a building may be placed at [x y]: in-bounds, terrain passable, no
   pawn on the cell, and no existing building there. The rule is independent of
   WHAT is placed (walls and doors share it), so it is the single predicate the
   build cursor and every build-* command read. Pure."
  [world [x y]]
  (let [grid (:grid world)]
    (and (in-bounds? grid x y)
         (tile/passable? (tile/tile-at grid x y))
         (nil? (pawn-at world x y))
         (nil? (building-at world x y)))))

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
  "Place one wall building at tile [tx ty], if can-build?."
  [tx ty]
  (place-building! tx ty entity/make-building))

(defn build-door!
  "Place one door building at tile [tx ty], if can-build?. A door is a passable
   portal building (see sim.entity/make-door); placement validity is identical
   to a wall's."
  [tx ty]
  (place-building! tx ty entity/make-door))

(defn deconstruct-building!
  "Remove the building at tile [tx ty], if any (wall or door alike). Terrain is
   untouched (it was never overwritten), so the cell simply becomes passable
   again."
  [tx ty]
  (swap! world/world
         (fn [w]
           (if-let [b (building-at w tx ty)]
             (entity/remove-entity w (:id b))
             w)))
  nil)
