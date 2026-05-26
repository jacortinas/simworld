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
