(ns sim.ui.time-controls
  "Top-right time-control cluster: pause/play plus the 1x/2x/3x speed buttons
   (RimWorld's speed widget). Anchored at the :top-right slot per sim.ui.layout
   (global + interactive). Exactly one button is 'active' at a time: the pause
   button while the sim is paused, otherwise the button for the current speed.

   Same pure-core / untested-draw split the render layers use:
     button-rects / hit-id   PURE geometry + hit-test (headless-tested)
     draw / click!           read live Gdx dims, then dispatch to sim.clock

   click! talks to sim.clock DIRECTLY: the HUD layer is allowed to depend on the
   clock (sim.ui.hud already does). Only the sim.input PROXY must stay clock-
   free, which is why the 1/2/3 KEYS route through an injected callback instead."
  (:require
   [sim.clock     :as clock]
   [sim.screens   :as screens]
   [sim.ui.layout :as layout])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch BitmapFont)))

(set! *warn-on-reflection* true)

(def ^:const ^:private bh 22)            ; button height
(def ^:const ^:private pw 30)            ; pause button width
(def ^:const ^:private sw 26)            ; speed button width
(def ^:const ^:private gap 4)            ; space between buttons

;; Buttons left -> right: pause, then the three speeds. Each carries the speed
;; multiplier it selects (nil for pause). The cluster width is DERIVED from
;; this, so adding a 4th speed is one entry here and nothing else.
(def ^:private specs
  [{:id :pause   :label "||" :speed nil :w pw}
   {:id :speed-1 :label "1"  :speed 1.0 :w sw}
   {:id :speed-2 :label "2"  :speed 2.0 :w sw}
   {:id :speed-3 :label "3"  :speed 3.0 :w sw}])

(def ^:private cluster-w
  (+ (reduce + (map :w specs)) (* gap (dec (count specs)))))

(defn button-rects
  "Pure: ordered [{:id :label :speed :rect [x y w h]} ...] for the cluster
   anchored top-right of a `vw` x `vh` viewport. UI-cam coords (Y-up). :speed is
   the multiplier the button selects (nil for pause)."
  [vw vh]
  (let [[x0 y] (layout/anchor :top-right vw vh cluster-w bh)]
    (first
     (reduce (fn [[acc x] {:keys [id label speed w]}]
               [(conj acc {:id id :label label :speed speed :rect [x y w bh]})
                (+ (double x) (double w) gap)])
             [[] (double x0)]
             specs))))

(defn hit-id
  "Pure hit-test: the id of the button under screen click (sx,sy), or nil.
   Screen coords are libGDX touch coords (Y-DOWN from the top), so each Y-up
   rect is flipped against `vh` before testing."
  [rects vh sx sy]
  (let [vh (double vh) sx (double sx) sy (double sy)]
    (some (fn [{:keys [id rect]}]
            (let [[x y w h] rect
                  x   (double x) y (double y) w (double w) h (double h)
                  top (- vh (+ y h))]
              (when (and (<= x sx (+ x w)) (<= top sy (+ top h)))
                id)))
          rects)))

(def ^:private border      (Color. 0.45 0.75 0.95 1.0))
(def ^:private fill        (Color. 0.16 0.16 0.20 1.0))
(def ^:private fill-active (Color. 0.20 0.42 0.30 1.0))   ; greenish "this is live"
(def ^:private label-color (Color. 0.95 0.97 1.0  1.0))

(defn- active-id
  "Which button is highlighted right now: :pause when paused, else the speed
   button matching the live multiplier. Reads sim.clock (a draw-side helper,
   not pure geometry)."
  []
  (if (clock/paused?*)
    :pause
    (case (long (clock/speed*))
      1 :speed-1
      2 :speed-2
      3 :speed-3
      nil)))

(defn draw
  "Draw the top-right time cluster. The active button gets the highlighted fill;
   the pause button shows > (play) while paused, || (pause) while running."
  [^SpriteBatch batch ^BitmapFont font ^Texture pixel]
  (let [vw     (.getWidth Gdx/graphics)
        vh     (.getHeight Gdx/graphics)
        rects  (button-rects vw vh)
        active (active-id)]
    (doseq [{:keys [id label rect]} rects]
      (let [lbl (if (= id :pause) (if (clock/paused?*) ">" "||") label)
            fc  (if (= id active) fill-active fill)]
        (screens/draw-button! batch font pixel border fc label-color rect lbl)))
    ;; Hand cursor over the cluster. This is the ONLY hover-cursor! caller on the
    ;; play screen now (the bottom HUD bar dropped its button), so nothing fights
    ;; it for the cursor each frame.
    (screens/hover-cursor! (mapv :rect rects))))

(defn click!
  "Offer screen click (sx,sy) to the cluster. Returns true if it landed on a
   button (consuming it). Pause toggles; a speed button sets that speed AND
   resumes -- selecting a speed un-pauses, RimWorld-style."
  [sx sy]
  (let [vw    (.getWidth Gdx/graphics)
        vh    (.getHeight Gdx/graphics)
        rects (button-rects vw vh)]
    (if-let [id (hit-id rects vh sx sy)]
      (do (if (= id :pause)
            (clock/toggle-pause!)
            (let [speed (:speed (first (filter #(= id (:id %)) rects)))]
              (clock/set-speed! speed)
              (clock/resume!)))
          true)
      false)))
