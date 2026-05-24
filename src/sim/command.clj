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
   [sim.job      :as job]
   [sim.tile     :as tile]))

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
  "RimWorld left-click: select the pawn under the cursor. Clicking empty
   ground (no pawn) clears the selection — `select!` with nil does that."
  [tx ty]
  (ui/select! (:id (pawn-at @world/world tx ty)))
  nil)

(defn right-click!
  "RimWorld right-click: order the selected pawn. For now the only verb is
   'go here' — move to the clicked tile if it's in-bounds and passable.
   Context actions (haul item, harvest plant) arrive as we add job types
   and a float-menu; today, move is the universal default."
  [tx ty]
  (when-let [sel (ui/selected)]
    (let [w    @world/world
          grid (:grid w)]
      (when (and (in-bounds? grid tx ty)
                 (tile/passable? (tile/tile-at grid tx ty)))
        (swap! world/world job/assign sel (job/go-to [tx ty]) job/forced-by-player))))
  nil)
