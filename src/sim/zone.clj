(ns sim.zone
  "Stockpile zones: rectangular cell regions the player drags out, where pawns
   will deposit hauled items (the haul behavior is a later spec). Pure model +
   rectangle geometry; nothing here touches input or rendering.

   Zones are plain world state under `:zones` (a vector of
   {:id n :kind :stockpile :cells #{[x y]...}}), saved with the world. A cell
   belongs to at most one zone. `:next-zone-id` is a monotonic counter so each
   zone has a stable identity for future move/delete.

   The rectangle core (`cells-in-rect`) is payload-agnostic — a future building
   placement tool reuses it; only `add-stockpile`'s 'what to create' differs."
  (:require
   [sim.tile :as tile]))

(set! *warn-on-reflection* true)

(defn cells-in-rect
  "Set of [x y] cells in the INCLUSIVE rectangle spanned by `a` and `b`,
   normalized so drag direction doesn't matter."
  [[ax ay] [bx by]]
  (let [x0 (min (long ax) (long bx)) x1 (max (long ax) (long bx))
        y0 (min (long ay) (long by)) y1 (max (long ay) (long by))]
    (into #{} (for [x (range x0 (inc x1))
                    y (range y0 (inc y1))]
                [x y]))))

(defn zones
  "Seq of zone maps in the world."
  [world]
  (:zones world))

(defn stockpile-cells
  "Union of every stockpile zone's cells."
  [world]
  (into #{} (mapcat :cells) (:zones world)))

(defn cell-zoned?
  "Is `cell` part of any stockpile zone?"
  [world cell]
  (contains? (stockpile-cells world) cell))

(defn- zonable?
  "A cell joins a stockpile only if it's in-bounds, passable, and not already
   part of another zone (a cell belongs to at most one zone)."
  [world existing [x y :as cell]]
  (let [{:keys [width height] :as grid} (:grid world)]
    (and (tile/in-bounds? width height x y)
         (tile/passable? (tile/tile-at grid x y))
         (not (contains? existing cell)))))

(defn add-stockpile
  "Add a stockpile zone covering the rectangle from `a` to `b`, restricted to
   in-bounds, passable, currently-unzoned cells. Adds NOTHING if that set is
   empty. Returns world'."
  [world a b]
  (let [existing (stockpile-cells world)
        cells    (into #{} (filter #(zonable? world existing %)) (cells-in-rect a b))]
    (if (empty? cells)
      world
      (let [id (or (:next-zone-id world) 1)]
        (-> world
            (update :zones (fnil conj []) {:id id :kind :stockpile :cells cells})
            (assoc :next-zone-id (inc (long id))))))))
