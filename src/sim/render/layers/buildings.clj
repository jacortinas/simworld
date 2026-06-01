(ns sim.render.layers.buildings
  "Buildings layer: building entities drawn through their :graphic at their tile
   anchor, mirroring layers/items.clj. Drawn above items and below pawns so
   walls occlude the floor but not the colonists."
  (:require
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
        (sprites/draw-graphic! batch (:graphic b)
                               (tile-anchor tx ty height ts) ts now-ms)))))
