(ns sim.render.layers.items
  "Items layer: ground items drawn through their :graphic. Carried items
   (:pos nil) are filtered out."
  (:require
   [sim.entity :as entity]
   [sim.defs :as defs]
   [sim.render.graphic :as graphic]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(defn draw
  [world ^SpriteBatch batch tile-size now-ms]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)]
    (doseq [item (entity/items world)]
      (when-let [[x y] (:pos item)]
        (when-let [gr (defs/graphic (:graphic item))]
          (when-let [region (sprites/graphic-region gr :down now-ms)]
            (let [px (* (long x) ts)
                  py (* (- height (long y) 1) ts)
                  [gx gy gw gh] (graphic/draw-rect gr [px py] ts)]
              (.draw batch region (float gx) (float gy) (float gw) (float gh)))))))))
