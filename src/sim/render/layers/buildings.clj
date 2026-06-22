(ns sim.render.layers.buildings
  "Buildings layer: building entities drawn through their :graphic at their tile
   anchor, mirroring layers/items.clj. Drawn above items and below pawns so
   walls occlude the floor but not the colonists.

   A DOOR (a :portal? building) retracts as it opens: it is drawn clipped to its
   closed fraction (1 - open-fraction), so the slab narrows over the door's
   open-ticks and the floor shows through behind it. The retract axis and the
   sprite facing follow the door's orientation (sim.door/orientation, read from
   the flanking walls): a horizontal-wall door is face-on and slides along X, a
   vertical-wall door is a strip sliding along Y. The facing selects the right
   sprite once the :door graphic gains :facings art; today it degrades to the
   single placeholder. Walls draw at full size."
  (:require
   [sim.door           :as door]
   [sim.entity         :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn- tile-anchor
  "Bottom-left world-pixel anchor [px py] for a tile at (tx, ty) using the
   (height-1-y) Y-flip all world layers share."
  [tx ty height tile-size]
  (let [ts (long tile-size)]
    [(* (long tx) ts)
     (* (- (long height) 1 (long ty)) ts)]))

(defn draw
  [world ^SpriteBatch batch tile-size now-ms]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)]
    (doseq [b (entity/buildings world)]
      (when-let [[tx ty] (:pos b)]
        (if (:portal? b)
          ;; door: each footprint cell draws the closed slab (1 - open-fraction),
          ;; retracting along the door's orientation axis with its facing; the
          ;; whole gate opens in unison (one :open). Fully open -> nothing.
          (let [[facing axis] (if (= :vertical (door/orientation world b))
                                [:left :y]      ; vertical: strip, slides on Y
                                [:down :x])     ; horizontal: face-on, slides on X
                keep          (- 1.0 (door/open-fraction b))]
            (doseq [[cx cy] (entity/footprint b)]
              (let [[px py] (tile-anchor cx cy height ts)]
                (sprites/draw-graphic-clipped! batch (:graphic b) px py ts
                                               keep axis facing now-ms))))
          ;; wall/structure: tile the sprite across every footprint cell, so a
          ;; multi-cell building fills all of its tiles.
          (doseq [[cx cy] (entity/footprint b)]
            (sprites/draw-graphic! batch (:graphic b) (tile-anchor cx cy height ts) ts now-ms)))))))
