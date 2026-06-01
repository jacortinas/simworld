(ns sim.ui.hud
  "Bottom HUD bar: the pause/play button plus a one-line status readout, fixed
   to the bottom of the viewport.

   Drawn under the UI camera (screen pixels, Y-up, origin bottom-left), so
   'bottom of the viewport' is just small Y — resize-stable without needing the
   live height. Hit-testing runs against libGDX touchDown coords (Y-DOWN from
   the top), so click! reads the live framebuffer height to flip the
   bottom-anchored rects. `button` is the single source of truth for geometry.

   The whole bar eats clicks (so a tap on the status text never leaks through
   as a world command); only the button rect actually toggles pause."
  (:require
   [sim.clock    :as clock]
   [sim.screens  :as screens]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Pixmap Pixmap$Format Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont)))

(set! *warn-on-reflection* true)

(def ^:const ^:private bar-h 30)

;; Button geometry in UI-cam coords: x,y = BOTTOM-left (y from viewport bottom),
;; w,h = size. Bottom-anchored, so it never depends on the viewport height.
(def ^:private button {:x 8 :y 4 :w 96 :h 22})

(def ^:private bar-color    (Color. 0.10 0.10 0.13 0.92))
(def ^:private btn-color    (Color. 0.16 0.16 0.20 1.0))
(def ^:private border-color (Color. 0.45 0.75 0.95 1.0))
(def ^:private label-color  (Color. 0.95 0.97 1.0  1.0))
(def ^:private text-color   (Color. 0.80 0.85 0.92 1.0))

(defn make-pixel-texture
  "A 1x1 white texture — tint + stretch it through the SpriteBatch to draw
   solid rects, no second renderer. MUST be created on the GL thread (gdx
   create); the caller owns and disposes it."
  ^Texture []
  (let [pm (Pixmap. 1 1 Pixmap$Format/RGBA8888)]
    (.setColor pm Color/WHITE)
    (.fill pm)
    (let [t (Texture. pm)] (.dispose pm) t)))

(defn click!
  "Screen click (sx,sy) in libGDX touchDown coords (Y-DOWN from top). Returns
   true if the click landed on the HUD bar (consuming it); toggles pause only
   when it's on the button rect."
  [sx sy]
  (let [h    (.getHeight Gdx/graphics)
        sx   (long sx)
        sy   (long sy)
        bx   (long (:x button))
        bw   (long (:w button))
        bh   (long (:h button))
        top  (- h (+ (long (:y button)) bh))   ; button rect, flipped to Y-down
        on-button? (and (<= bx sx (+ bx bw)) (<= top sy (+ top bh)))]
    (cond
      on-button?            (do (clock/toggle-pause!) true)
      (>= sy (- h bar-h))   true            ; elsewhere on the bar: eat, no action
      :else                 false)))        ; world click — let it through

(defn- ^String status-text [world]
  (str (cond (not (clock/running?*)) "STOPPED"
             (clock/paused?*)        "PAUSED"
             :else                   "RUNNING")
       "    tick "  (:clock world 0)
       "    pawns " (count (filter #(= :pawn (:kind %)) (vals (:entities world))))
       "    items " (count (filter #(and (= :item (:kind %)) (:pos %))
                                   (vals (:entities world))))
       "    log "   (count (:log world))
       "    sel "   (or (ui/selected) "-")
       "    zoom "  (format "%.2f" (double (:zoom (ui/camera))))))

(defn draw
  "Render the bottom bar: full-width background, the pause/play button, and the
   status line, all on one baseline. Reads live viewport dims from Gdx so it
   stays pinned to the bottom across resizes."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel world]
  ;; All draw coords precomputed as floats — Clojure widens (+ float float) to
  ;; double, which would force reflective Batch/Font.draw calls every frame.
  (let [vw       (float (.getWidth Gdx/graphics))
        bx       (float (:x button))
        by       (float (:y button))
        bw       (float (:w button))
        bh       (float (:h button))
        cap      (.getCapHeight font)
        label    (if (clock/paused?*) ">  PLAY" "||  PAUSE")
        bar-ty   (float (/ (+ bar-h cap) 2.0))           ; center line in [0,bar-h]
        btn-ty   (float (+ by (/ (+ bh cap) 2.0)))       ; center label on button
        label-x  (float (+ bx 13))
        status-x (float (+ bx bw 18))
        ix       (float (+ bx 2))
        iy       (float (+ by 2))
        iw       (float (- bw 4))
        ih       (float (- bh 4))]
    ;; full-width bar background, pinned to the bottom
    (.setColor batch bar-color)
    (.draw batch pixel (float 0) (float 0) vw (float bar-h))
    ;; button: border rect, then inset fill
    (.setColor batch border-color)
    (.draw batch pixel bx by bw bh)
    (.setColor batch btn-color)
    (.draw batch pixel ix iy iw ih)
    (.setColor batch Color/WHITE)                        ; reset tint before text
    (.setColor font label-color)
    (.draw font batch label label-x btn-ty)
    (.setColor font text-color)
    (.draw font batch (status-text world) status-x bar-ty)
    ;; Placement-mode banner just above the bar, so the stateful zoning mode is
    ;; visible (the only feedback besides the live drag preview). ASCII only.
    (when (= :zone-stockpile (ui/mode))
      (.setColor font label-color)
      (.draw font batch "STOCKPILE ZONING  -  drag to place, right-click or Esc to cancel"
             (float 8) (float (+ bar-h 18))))
    ;; Hand-cursor on the pause/play button. The pause-menu overrides this when
    ;; open by calling hover-cursor! AFTER composing :play's draw.
    (screens/hover-cursor! [[(:x button) (:y button) (:w button) (:h button)]])))
