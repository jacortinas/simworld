(ns sim.ui.inspect-panel
  "Bottom-right hover inspect pane: the concept lines for the tile under the
   cursor, right-aligned on a translucent rect, just above the HUD status bar.

   A dumb view, like the HUD: it reads (ui/hover), asks sim.inspect for the
   lines, and draws them. All logic is in sim.inspect (headless-tested); this
   file only does GL. Consumes NO clicks. Drawn under the fixed UI camera, in
   the same block as hud/draw.

   Reuses the 1px pixel texture (the solid-rect trick from sim.ui.hud) for the
   panel background and the shared BitmapFont for text. Right-alignment uses a
   GlyphLayout to measure each line's pixel width."
  (:require
   [sim.ui-state :as ui]
   [sim.inspect  :as inspect])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont GlyphLayout)))

(set! *warn-on-reflection* true)

;; Mirrors sim.ui.hud/bar-h: the panel floats just above the 30px status bar.
(def ^:const ^:private bar-h 30)
(def ^:const ^:private pad 6)            ; inner padding, panel px
(def ^:const ^:private right-margin 8)   ; gap from the right viewport edge
(def ^:const ^:private gap-above-bar 6)  ; gap between panel bottom and the bar

(def ^:private panel-color (Color. 0.10 0.10 0.13 0.92)) ; matches hud bar-color
(def ^:private text-color  (Color. 0.90 0.93 0.98 1.0))

(defn draw
  "Render the hover panel. No-op when nothing is hovered or the hovered tile is
   off-map (describe-tile -> nil). `world` supplies terrain/entities; the panel
   always reflects the HOVERED tile, never the selection."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel world]
  (when-let [hov (ui/hover)]
    (when-let [lines (inspect/describe-tile world hov)]
      (let [vw       (.getWidth Gdx/graphics)
            cap      (.getCapHeight font)
            line-h   (+ cap pad)
            layout   (GlyphLayout.)
            ;; widest line drives the panel width
            widths   (mapv (fn [s] (.setText layout font ^String s) (.width layout)) lines)
            text-w   (double (reduce max 0.0 widths))
            panel-w  (float (+ text-w (* 2 pad)))
            panel-h  (float (+ (* line-h (count lines)) pad))
            panel-x  (float (- vw panel-w right-margin))
            panel-y  (float (+ bar-h gap-above-bar))
            text-r   (float (- (+ panel-x panel-w) pad))] ; right edge for text
        ;; background
        (.setColor batch panel-color)
        (.draw batch pixel panel-x panel-y panel-w panel-h)
        (.setColor batch Color/WHITE)
        ;; lines top-to-bottom, right-aligned within the panel
        (.setColor font text-color)
        (dotimes [i (count lines)]
          (let [s  ^String (nth lines i)
                w  (double (nth widths i))
                ;; top line at the top of the panel; libGDX text y is the
                ;; baseline (top of the glyphs), so offset down by cap.
                ty (float (- (+ panel-y panel-h) pad (* i line-h)))
                tx (float (- text-r w))]
            (.draw font batch s tx ty)))))))
