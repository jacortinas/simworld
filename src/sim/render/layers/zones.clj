(ns sim.render.layers.zones
  "Stockpile-zone floor overlay: translucent fills on zoned cells, plus the live
   drag-preview rectangle while placing. Drawn just above terrain so flora /
   items / pawns render on top.

   Same discipline as sim.render.layers.selection: cell-rect / cells->rects /
   drag-preview-cells are pure geometry (tested in sim.zones-layer-test); draw is
   the untested GL view. Reuses the shared 1px pixel texture for solid rects and
   the (grid-height-1-y) Y-flip so fills register with the sprites underneath."
  (:require
   [sim.ui-state :as ui]
   [sim.zone     :as zone])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

(def ^:private zone-color    (Color. 0.2 0.8 0.3 0.30))  ; translucent green
(def ^:private preview-color (Color. 0.4 0.9 0.5 0.45))  ; brighter live preview

(defn cell-rect
  "Fill rect [px py w h] (world px, doubles) for tile [x y], with the
   (grid-height-1-y) Y-flip used by every world layer."
  [[x y] tile-size grid-height]
  (let [ts (double tile-size)]
    [(* (double x) ts)
     (* (double (- (long grid-height) (long y) 1)) ts)
     ts ts]))

(defn cells->rects
  "Fill rects for a set/seq of cells."
  [cells tile-size grid-height]
  (mapv #(cell-rect % tile-size grid-height) cells))

(defn drag-preview-cells
  "Cells the in-progress drag spans, or nil. Pure geometry (does NOT filter
   passability — that happens at commit, in sim.zone/add-stockpile)."
  [ui-drag]
  (when-let [{:keys [start current]} ui-drag]
    (zone/cells-in-rect start current)))

(defn draw
  "Fill committed stockpile cells, then the live drag preview on top. Resets the
   batch tint to white when done."
  [world ^SpriteBatch batch tile-size ^Texture pixel]
  (let [height (long (:height (:grid world)))]
    (.setColor batch zone-color)
    (doseq [[x y w h] (cells->rects (zone/stockpile-cells world) tile-size height)]
      (.draw batch pixel (float x) (float y) (float w) (float h)))
    (when-let [cells (drag-preview-cells (ui/drag))]
      (.setColor batch preview-color)
      (doseq [[x y w h] (cells->rects cells tile-size height)]
        (.draw batch pixel (float x) (float y) (float w) (float h))))
    (.setColor batch Color/WHITE)))
