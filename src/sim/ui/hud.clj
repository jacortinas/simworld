(ns sim.ui.hud
  "Bottom HUD bar: a one-line status readout fixed to the bottom of the viewport.

   Drawn under the UI camera (screen pixels, Y-up, origin bottom-left), so
   'bottom of the viewport' is just small Y, resize-stable without needing the
   live height. Hit-testing in click! runs against libGDX touchDown coords
   (Y-DOWN from the top), so it reads the live framebuffer height to flip the
   bottom-anchored bar.

   Time controls (pause + speed) live in their own top-right cluster now
   (sim.ui.time-controls), per the sim.ui.layout information hierarchy. The
   bottom bar is pure status: it still EATS clicks (so a tap on the status text
   never leaks through as a world command) but has no button of its own."
  (:require
   [sim.clock         :as clock]
   [sim.entity        :as entity]
   [sim.ui-state      :as ui]
   [sim.ui.build-menu :as build-menu])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Pixmap Pixmap$Format Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont)))

(set! *warn-on-reflection* true)

(def ^:const ^:private bar-h 30)

(def ^:private bar-color   (Color. 0.10 0.10 0.13 0.92))
(def ^:private label-color (Color. 0.95 0.97 1.0  1.0))
(def ^:private text-color  (Color. 0.80 0.85 0.92 1.0))

(defn make-pixel-texture
  "A 1x1 white texture, tint + stretch it through the SpriteBatch to draw solid
   rects, no second renderer. MUST be created on the GL thread (gdx create); the
   caller owns and disposes it."
  ^Texture []
  (let [pm (Pixmap. 1 1 Pixmap$Format/RGBA8888)]
    (.setColor pm Color/WHITE)
    (.fill pm)
    (let [t (Texture. pm)] (.dispose pm) t)))

(defn click!
  "Screen click (sx,sy) in libGDX touchDown coords (Y-DOWN from top). Returns
   true if the click landed on the bottom bar (consuming it, so it can't leak
   as a world command), false otherwise. The bar has no interactive widgets, it
   just absorbs taps that hit it."
  [_sx sy]
  (let [h  (.getHeight Gdx/graphics)
        sy (long sy)]
    (>= sy (- h bar-h))))

(defn- status-text ^String [world]
  (str (cond (not (clock/running?*)) "STOPPED"
             (clock/paused?*)        "PAUSED"
             :else                   (str "RUNNING " (long (clock/speed*)) "x"))
       "    tick "  (:clock world 0)
       ;; Counts read the derived :kinds index instead of an O(all-entities) scan
       ;; per frame: pawn count is an O(1) sorted-set count; grounded items iterate
       ;; only the item kind (the :pos filter excludes carried items).
       "    pawns " (count (get-in world [:kinds :pawn]))
       "    items " (count (filter :pos (entity/items world)))
       "    log "   (count (:log world))
       "    sel "   (or (ui/selected) "-")
       "    zoom "  (format "%.2f" (double (:zoom (ui/camera))))))

(defn draw
  "Render the bottom bar: full-width background plus the status line on one
   baseline. Reads live viewport dims from Gdx so it stays pinned to the bottom
   across resizes."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel world]
  ;; All draw coords precomputed as floats: Clojure widens (+ float float) to
  ;; double, which would force reflective Batch/Font.draw calls every frame.
  (let [vw       (float (.getWidth Gdx/graphics))
        cap      (.getCapHeight font)
        bar-ty   (float (/ (+ bar-h cap) 2.0))           ; center line in [0,bar-h]
        ;; Start the status text PAST the bottom-left build-menu category row so
        ;; the buttons (which share this bar's vertical strip) never cover it.
        status-x (float (+ (build-menu/category-row-width) 12))]
    ;; full-width bar background, pinned to the bottom
    (.setColor batch bar-color)
    (.draw batch pixel (float 0) (float 0) vw (float bar-h))
    (.setColor batch Color/WHITE)                        ; reset tint before text
    (.setColor font text-color)
    (.draw font batch (status-text world) status-x bar-ty)
    ;; Placement-mode banner just above the bar, so the stateful zoning mode is
    ;; visible (the only feedback besides the live drag preview). ASCII only.
    (when (= :zone-stockpile (ui/mode))
      (.setColor font label-color)
      (.draw font batch "STOCKPILE ZONING  -  drag to place, right-click or Esc to cancel"
             (float 8) (float (+ bar-h 18))))))
