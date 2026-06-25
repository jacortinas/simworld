(ns sim.ui.build-menu
  "Bottom-left build menu (RimWorld's Architect): a row of category buttons, and
   an open category shows a column of buildables above it. Selecting a buildable
   just ARMS its tool by setting ui-state/:mode, the same thing the old per-key
   build modes did, so the menu is a SELECTOR over the sim.tools registry, not a
   new placement mechanism. The armed item is DERIVED (the one whose :mode equals
   the live mode), so the only menu state is which category is open (ui-state).

   Same pure-core / untested-draw split as sim.ui.time-controls: the *-rects and
   hit fns are pure geometry/hit-test (headless-tested); draw/click! read live Gdx
   dims + ui-state and dispatch to sim.ui-state."
  (:require
   [sim.ui-state  :as ui]
   [sim.screens   :as screens]
   [sim.ui.layout :as layout])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont)))

(set! *warn-on-reflection* true)

;; Build-menu content: ordered categories, each an ordered list of items. An item
;; ARMS a ui-state :mode (an existing sim.tools/by-mode key), so adding a buildable
;; is one entry here, no new dispatch. Orders/Deconstruct uses the :deconstruct mode.
(def categories
  [{:id :structure :label "Structure"
    :items [{:id :wall :label "Wall" :mode :build}
            {:id :door :label "Door" :mode :build-door}]}
   {:id :zone :label "Zone"
    :items [{:id :stockpile :label "Stockpile" :mode :zone-stockpile}]}
   {:id :orders :label "Orders"
    :items [{:id :deconstruct :label "Deconstruct" :mode :deconstruct}]}])

(def ^:const ^:private cw 92)    ; button width
(def ^:const ^:private bh 22)    ; button height
(def ^:const ^:private gap 4)    ; gap between category buttons
(def ^:const ^:private igap 2)   ; gap between stacked item buttons

(defn category-row-width
  "Total pixel width of the bottom-left category row (from the screen's left edge
   through the last button). The bottom HUD status text starts past this so the
   row never overlaps it. Pure."
  []
  (+ layout/pad (* (count categories) (+ cw gap))))

(defn- category-index
  "Index of category `cat-id` in `categories`, or nil."
  [cat-id]
  (first (keep-indexed (fn [i c] (when (= cat-id (:id c)) i)) categories)))

(defn category-row-rects
  "Pure: the bottom-left row of category buttons, left to right. UI-cam coords
   (Y-up). Anchored at the bottom-left corner, so it needs no viewport dims."
  []
  (map-indexed (fn [i c]
                 {:kind :category :id (:id c) :label (:label c)
                  :rect [(+ layout/pad (* i (+ cw gap))) layout/pad cw bh]})
               categories))

(defn item-column-rects
  "Pure: buildable buttons for the OPEN category `cat-id`, stacked UPWARD above
   that category's button. [] when nothing is open."
  [cat-id]
  (if-let [i (category-index cat-id)]
    (let [x (+ layout/pad (* i (+ cw gap)))]
      (map-indexed (fn [j item]
                     {:kind :item :id (:id item) :label (:label item) :mode (:mode item)
                      :rect [x (+ layout/pad bh gap (* j (+ bh igap))) cw bh]})
                   (:items (nth categories i))))
    []))

(defn all-rects
  "Row + open-category column: the full clickable surface for open category `cat-id`."
  [cat-id]
  (concat (category-row-rects) (item-column-rects cat-id)))

(defn hit
  "Pure hit-test: the rect-spec under screen click (sx,sy) for open category
   `cat-id`, or nil. Screen coords are libGDX touch coords (Y-down from the top),
   so each Y-up rect is flipped against `vh` before testing."
  [cat-id vh sx sy]
  (let [vh (double vh) sx (double sx) sy (double sy)]
    (some (fn [{:keys [rect] :as spec}]
            (let [[x y w h] rect
                  x (double x) y (double y) w (double w) h (double h)
                  top (- vh (+ y h))]
              (when (and (<= x sx (+ x w)) (<= top sy (+ top h)))
                spec)))
          (all-rects cat-id))))

(def ^:private border      (Color. 0.55 0.50 0.40 1.0))
(def ^:private fill        (Color. 0.16 0.16 0.20 1.0))
(def ^:private fill-open   (Color. 0.32 0.29 0.18 1.0))   ; the expanded category
(def ^:private fill-armed  (Color. 0.20 0.42 0.30 1.0))   ; the armed buildable (matches time-controls active)
(def ^:private label-color (Color. 0.95 0.97 1.0 1.0))

(defn- button-fill
  "Highlight rule: the open category and the armed item (its :mode == the live
   mode) get accent fills; everything else is the neutral fill."
  [spec open mode]
  (cond
    (and (= :category (:kind spec)) (= (:id spec) open))   fill-open
    (and (= :item (:kind spec)) (= (:mode spec) mode))     fill-armed
    :else                                                  fill))

(defn draw
  "Draw the bottom-left build menu: the category row, plus the open category's
   buildable column above it. Highlights the open category and the armed item."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel]
  (let [open  (ui/build-menu-open)
        mode  (ui/mode)
        rects (all-rects open)]
    (doseq [{:keys [rect label] :as spec} rects]
      (screens/draw-button! batch font pixel border (button-fill spec open mode) label-color rect label))
    (screens/hover-cursor! (mapv :rect rects))))

(defn click!
  "Offer screen click (sx,sy) to the menu. Returns true if it landed on a button
   (consuming it). A category toggles open/closed; an item ARMS its tool (sets
   :mode) and leaves the category open, so you can place several or switch items."
  [sx sy]
  (let [vh (.getHeight Gdx/graphics)]
    (if-let [spec (hit (ui/build-menu-open) vh sx sy)]
      (do (case (:kind spec)
            :category (ui/toggle-build-menu-category! (:id spec))
            :item     (ui/set-mode! (:mode spec)))
          true)
      false)))
