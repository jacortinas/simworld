(ns sim.inspect
  "PURE tile-inspection logic — no GL, no atoms. Turns (world, [x y]) into the
   concept-line strings the hover panel renders, and into the sorted list of
   selectable entities the cursor can cycle through.

   Mirrors the debug-layer discipline: this is the headless-tested core; the
   GL renderers (sim.ui.inspect-panel, sim.render.layers.selection) are dumb
   views over it. Bounds-checks here so callers (sim.input) can hand us raw,
   possibly off-map coords without taking a sim.tile dependency."
  (:require
   [clojure.string :as str]
   [sim.tile   :as tile]
   [sim.entity :as entity]))

(set! *warn-on-reflection* true)

;; Things worth selecting/labelling. Base terrain is NEVER here. Future kinds
;; (:animal :monster) append for free — describe-tile/selectable-at pick them up.
(def selectable-kinds #{:pawn :item :tree})

;; Char cap per concept line. Default font isn't monospace, so this is a rough
;; visual cap, not pixel-accurate — tune live in the REPL. A cut line is padded
;; out to exactly this length by the trailing "..." (so the cap is the cut).
(def ^:const max-line-len 28)

(defn selectable-at
  "Entities at tile [x y] whose :kind is selectable, SORTED BY :id (stable
   cycle order). Carried items (:pos nil) never match a tile, so they're
   excluded naturally."
  [world [x y]]
  (->> (entity/all-entities world)
       (filter #(and (selectable-kinds (:kind %)) (= [x y] (:pos %))))
       (sort-by :id)))

(defn- truncate
  "Cap `s` at max-line-len chars; if cut, the last 3 chars become '...' so the
   result is exactly max-line-len long."
  [s]
  (if (> (count s) max-line-len)
    (str (subs s 0 (- max-line-len 3)) "...")
    s))

(defn- terrain-line
  "Concept line for a terrain keyword: '<Type> <speed>%' when passable, else
   '<Type> (impassable)'. speed% = round(100 / move-cost)."
  [terrain-key]
  (let [{:keys [passable? move-cost]} (tile/terrain-info terrain-key)
        nm (str/capitalize (name terrain-key))]
    (if passable?
      (str nm " " (Math/round (/ 100.0 (double move-cost))) "%")
      (str nm " (impassable)"))))

(defn- entity-label
  "Short label for a selectable entity."
  [ent]
  (case (:kind ent)
    :pawn (:name ent)
    :item (str/capitalize (name (:material ent)))
    :tree "Tree"
    (str/capitalize (name (:kind ent)))))

(defn describe-tile
  "Vector of concept-line strings for tile [x y]: the terrain line first, then
   one truncated label per selectable entity (by id). nil if [x y] is off-map."
  [world [x y]]
  (let [{:keys [width height] :as grid} (:grid world)]
    ;; guard: off-map -> nil; also keeps tile-at from handing terrain-line a nil
    (when (tile/in-bounds? width height x y)
      (into [(truncate (terrain-line (tile/tile-at grid x y)))]
            (map (comp truncate entity-label))
            (selectable-at world [x y])))))
