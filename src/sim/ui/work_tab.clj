(ns sim.ui.work-tab
  "Work-priorities tab (RimWorld's Work tab): a top-left toggle button opens a grid
   of pawns (rows) x work types (columns); each cell shows that pawn's priority for
   that work type (1 highest .. 4 lowest, '-' = off). Clicking a cell cycles it.

   The grid reads the live work types (sim.think/work-types) and per-pawn
   priorities (sim.think/pawn-priorities); a cell click routes through sim.command
   (the world bridge). Same pure-core (button-rect / panel-layout / hit-cell) vs
   untested-GL (draw / click!) split as the build menu."
  (:require
   [sim.world     :as world]
   [sim.command   :as command]
   [sim.entity    :as entity]
   [sim.think     :as think]
   [sim.ui-state  :as ui]
   [sim.screens   :as screens]
   [sim.ui.layout :as layout])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont)))

(set! *warn-on-reflection* true)

(def ^:const ^:private btn-w 64)
(def ^:const ^:private bh 22)
(def ^:const ^:private name-w 96)
(def ^:const ^:private cell-w 52)
(def ^:const ^:private gap 2)

(defn button-rect
  "The Work toggle button (top-left). Pure UI-cam rect [x y w h]."
  [vw vh]
  (let [[x y] (layout/anchor :top-left vw vh btn-w bh)]
    [x y btn-w bh]))

(defn- col-x ^long [j] (+ layout/pad name-w gap (* (long j) (+ cell-w gap))))

(defn- row-y
  "Bottom-left y of grid row `r` (r=0 is the header row), stacked DOWNWARD from
   just under the toggle button."
  ^long [vh r]
  (- (long vh) layout/pad bh gap (* (inc (long r)) bh) (* (long r) gap)))

(defn panel-layout
  "Pure geometry for the open Work tab. `pawns` is a seq of {:id :name :priorities}.
   Returns {:headers [{:label :rect}] :names [{:pawn-id :label :rect}]
            :cells [{:pawn-id :wt-id :priority :rect}]}."
  [vh pawns]
  (let [wts think/work-types]
    {:headers (map-indexed (fn [j wt] {:label (:label wt) :rect [(col-x j) (row-y vh 0) cell-w bh]}) wts)
     :names   (map-indexed (fn [k p] {:pawn-id (:id p) :label (:name p)
                                      :rect [layout/pad (row-y vh (inc k)) name-w bh]}) pawns)
     :cells   (vec (for [k (range (count pawns)) j (range (count wts))
                         :let [p (nth pawns k) wt (nth wts j)]]
                     {:pawn-id  (:id p) :wt-id (:id wt)
                      :priority (get (:priorities p) (:id wt))
                      :rect     [(col-x j) (row-y vh (inc k)) cell-w bh]}))}))

(defn hit-cell
  "Pure: the {:pawn-id :wt-id} priority cell under screen click (sx,sy), or nil.
   Screen coords are Y-down; each Y-up rect is flipped against `vh`."
  [vh pawns sx sy]
  (let [vh (double vh) sx (double sx) sy (double sy)]
    (some (fn [{:keys [pawn-id wt-id rect]}]
            (let [[x y w h] rect
                  top (- vh (+ (double y) (double h)))]
              (when (and (<= (double x) sx (+ (double x) (double w)))
                         (<= top sy (+ top (double h))))
                {:pawn-id pawn-id :wt-id wt-id})))
          (:cells (panel-layout vh pawns)))))

(defn- in-rect? [vh [x y w h] sx sy]
  (let [top (- (double vh) (+ (double y) (double h)))]
    (and (<= (double x) (double sx) (+ (double x) (double w)))
         (<= top (double sy) (+ top (double h))))))

(def ^:private border      (Color. 0.45 0.55 0.65 1.0))
(def ^:private btn-fill    (Color. 0.16 0.16 0.20 1.0))
(def ^:private btn-open    (Color. 0.20 0.34 0.46 1.0))
(def ^:private header-fill (Color. 0.22 0.24 0.30 1.0))
(def ^:private name-fill   (Color. 0.14 0.15 0.19 0.95))
(def ^:private on-fill     (Color. 0.20 0.42 0.30 1.0))   ; enabled work cell
(def ^:private off-fill    (Color. 0.18 0.16 0.16 0.95))  ; disabled work cell
(def ^:private label-color (Color. 0.95 0.97 1.0 1.0))

(defn- pawn-list
  "World pawns as {:id :name :priorities} (priorities merged over defaults), id-
   sorted so draw and click agree on row order."
  [world]
  (mapv (fn [p] {:id (:id p) :name (:name p) :priorities (think/pawn-priorities p)})
        (entity/pawns world)))

(defn draw
  "Draw the top-left Work button (always), and the grid panel when open."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel world]
  (let [vw    (.getWidth Gdx/graphics)
        vh    (.getHeight Gdx/graphics)
        open? (ui/work-tab-open?)
        brect (button-rect vw vh)]
    (screens/draw-button! batch font pixel border (if open? btn-open btn-fill) label-color brect "Work")
    (when open?
      (let [pawns (pawn-list world)
            {:keys [headers names cells]} (panel-layout vh pawns)]
        (doseq [{:keys [label rect]} headers]
          (screens/draw-button! batch font pixel border header-fill label-color rect label))
        (doseq [{:keys [label rect]} names]
          (screens/draw-button! batch font pixel border name-fill label-color rect label))
        (doseq [{:keys [priority rect]} cells]
          (let [p   (long (or priority 0))
                lbl (if (pos? p) (str p) "-")]
            (screens/draw-button! batch font pixel border (if (pos? p) on-fill off-fill) label-color rect lbl)))))
    (screens/hover-cursor! [brect])))

(defn click!
  "Offer screen click (sx,sy) to the Work tab. The button toggles the panel; a
   cell cycles that pawn's priority for that work type (via sim.command). Returns
   true if consumed. Reads the live world for the pawn rows (like sim.command)."
  [sx sy]
  (let [vw (.getWidth Gdx/graphics)
        vh (.getHeight Gdx/graphics)]
    (cond
      (in-rect? vh (button-rect vw vh) sx sy)
      (do (ui/toggle-work-tab!) true)

      (ui/work-tab-open?)
      (if-let [{:keys [pawn-id wt-id]} (hit-cell vh (pawn-list @world/world) sx sy)]
        (do (command/cycle-work-priority! pawn-id wt-id) true)
        false)

      :else false)))
