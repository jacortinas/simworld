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
   [sim.defs   :as defs]
   [sim.tile   :as tile]
   [sim.entity :as entity]))

(set! *warn-on-reflection* true)

;; Things worth selecting/labelling. Base terrain is NEVER here. Future kinds
;; (:animal :monster) append for free: describe-tile/selectable-at pick them up.
;; :building is selectable so a wall/door can be inspected and selected; it is
;; matched by FOOTPRINT (any of its cells), not just its origin (selectable-at).
(def selectable-kinds #{:pawn :item :tree :building})

;; Char cap per concept line. Default font isn't monospace, so this is a rough
;; visual cap, not pixel-accurate — tune live in the REPL. A cut line is padded
;; out to exactly this length by the trailing "..." (so the cap is the cut).
(def ^:const max-line-len 28)

(defn selectable-at
  "Entities at tile [x y] whose :kind is selectable, SORTED BY :id (stable
   cycle order). A building matches by FOOTPRINT (any cell of its :size rect), so
   a multi-cell wall/door is selectable from any of its cells; everything else
   matches its single :pos. Carried items (:pos nil) never match a tile, so
   they're excluded naturally."
  [world [x y]]
  (->> (entity/all-entities world)
       (filter (fn [e]
                 (and (selectable-kinds (:kind e))
                      (if (= :building (:kind e))
                        (entity/building-covers? e [x y])
                        (= [x y] (:pos e))))))
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
    :building (let [base (str/capitalize (name (:def ent :building)))]   ; "Wall" / "Door"
                (if (entity/blueprint? ent) (str base " (blueprint)") base))
    (str/capitalize (name (:kind ent)))))

(defn- blueprint-progress-line
  "A second concept line for a blueprint: its delivered/needed material tallies
   and construction work percent, e.g. 'stone 2/5, built 40%'. Reads :cost and
   :work-to-build from the def. Indented two spaces to read as a sub-line."
  [ent]
  (let [d         (defs/thing (:def ent))
        cost      (:cost d {})
        delivered (:delivered ent {})
        wt        (max 1 (long (:work-to-build d 1)))
        wpct      (long (Math/round (* 100.0 (/ (double (:work-done ent 0)) (double wt)))))
        mats      (->> (sort cost)
                       (map (fn [[m n]] (str (name m) " " (get delivered m 0) "/" n)))
                       (str/join ", "))]
    (str "  " (if (seq mats) (str mats ", ") "") "built " wpct "%")))

(defn- entity-lines
  "Concept line(s) for one selectable entity: its label, plus a progress sub-line
   for a blueprint. Most entities yield one line; a blueprint yields two."
  [ent]
  (let [label (truncate (entity-label ent))]
    (if (and (= :building (:kind ent)) (entity/blueprint? ent))
      [label (truncate (blueprint-progress-line ent))]
      [label])))

(defn describe-tile
  "Vector of concept-line strings for tile [x y]: the terrain line first, then the
   label line(s) per selectable entity (by id) -- a blueprint adds a progress
   sub-line. nil if [x y] is off-map."
  [world [x y]]
  (let [{:keys [width height] :as grid} (:grid world)]
    ;; guard: off-map -> nil; also keeps tile-at from handing terrain-line a nil
    (when (tile/in-bounds? width height x y)
      (into [(truncate (terrain-line (tile/tile-at grid x y)))]
            (mapcat entity-lines)
            (selectable-at world [x y])))))
