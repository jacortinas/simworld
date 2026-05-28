(ns sim.render.interp
  "Pure render-time interpolation of an entity's DRAWN position.

   The sim keeps movement in integer ticks (sim.job's :move {:from :to :elapsed
   :cost}); this is the ONE place that turns elapsed/cost into a float, and it
   does so only for drawing — the value never flows back into world state, so
   determinism is untouched. A settled entity snaps to its cell; a gliding one
   lerps from the cell it left toward :pos (the cell it is entering), with the
   shared (height-1-y) Y-flip so it registers with the terrain underneath.

   Same pure-core / untested-GL split as the debug and selection layers: layers
   call draw-pos for WHERE, then issue the GL draw.")

(set! *warn-on-reflection* true)

(defn- interp-cell
  "The entity's logical cell as a (possibly fractional) [x y]: lerp :from -> :to
   by elapsed/cost when a :move is in flight, else the plain integer :pos."
  [entity]
  (if-let [{:keys [from to elapsed cost]} (get-in entity [:job :move])]
    (let [t        (/ (double elapsed) (double cost))
          [fx fy]  from
          [tx ty]  to]
      [(+ (double fx) (* (- (double tx) (double fx)) t))
       (+ (double fy) (* (- (double ty) (double fy)) t))])
    (:pos entity)))

(defn draw-pos
  "Bottom-left world-pixel [px py] to draw `entity` at, with the (height-1-y)
   Y-flip. Interpolates a gliding entity between cells; snaps a settled one.
   Assumes the entity has a :pos (callers already guard carried items)."
  [entity tile-size grid-height]
  (let [ts      (double tile-size)
        h       (double grid-height)
        [lx ly] (interp-cell entity)]
    [(* (double lx) ts)
     (* (- h (double ly) 1.0) ts)]))
