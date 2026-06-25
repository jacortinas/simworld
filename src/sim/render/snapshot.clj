(ns sim.render.snapshot
  "Headless software renderer: rasterize a world to a PNG with Java2D, with NO
   libGDX window and NO GL context. A second pure projection of the world (the
   GL layers are the first), built for self-validation: construct a scene, tick
   it, snapshot it, and look at the image. It mirrors the GL compositor's layer
   ORDER and the (height-1-y) anchor.

   Coordinate note: sim.render.interp/draw-pos returns image-top-left pixels here
   directly. The GL world is y-up anchored bottom-left and the image is y-down
   anchored top-left; those two flips cancel, so draw-pos's py is exactly the
   image row. The same interpolation the GL renderer uses (gliding pawns) is
   reused with no conversion.

   This is a READ-only view (like every layer): it never returns or mutates a
   world. Runs in any plain JVM (java.awt is headless-capable), so a dev script
   can drive it without a display."
  (:require
   [sim.defs          :as defs]
   [sim.entity        :as entity]
   [sim.tile          :as tile]
   [sim.zone          :as zone]
   [sim.render.interp :as interp])
  (:import
   (java.awt Color BasicStroke RenderingHints)
   (java.awt.image BufferedImage)
   (java.io File)
   (javax.imageio ImageIO)))

(set! *warn-on-reflection* true)

(def ^:const default-tile-px 16)

;; ---------------------------------------------------------------------------
;; Colors. Content (terrain) comes from the def DB; entity colors are a small
;; fixed palette chosen for legibility in a snapshot, NOT the real sprites
;; (that's the GL renderer's job). Unknown material -> magenta, so a content gap
;; SHOWS rather than hides.
;; ---------------------------------------------------------------------------

(defn- ->color
  "[r g b] floats in 0..1 (optional alpha 0..1) -> java.awt.Color."
  (^Color [rgb] (->color rgb 1.0))
  (^Color [[r g b] a]
   (Color. (float r) (float g) (float b) (float a))))

(def ^:private material-colors
  {:wood  (Color. 139 90 43)
   :stone (Color. 130 130 138)
   :food  (Color. 205 60 55)})

(def ^:private unknown-color (Color. 220 40 200))   ; magenta: a content gap shows

(def ^:private wall-color      (Color. 58 58 70))
(def ^:private door-color      (Color. 150 105 55))
(def ^:private ghost-stroke    (Color. 40 200 255))            ; cyan blueprint outline
(def ^:private ghost-fill      (Color. 40 200 255 60))         ; translucent cyan
(def ^:private ready-fill      (Color. 70 220 120 70))         ; green once fully delivered
(def ^:private work-bar-color  (Color. 80 230 130))
(def ^:private deliver-bar-bg  (Color. 0 0 0 120))
(def ^:private pawn-fill       (Color. 235 235 235))
(def ^:private pawn-outline    (Color. 25 25 25))
(def ^:private grid-line       (Color. 0 0 0 28))

;; ---------------------------------------------------------------------------
;; Layer draws. Each takes the Graphics2D + world + tile px + grid height and
;; paints in place, mirroring the GL layer order: terrain, zones, items,
;; buildings (built + blueprints), pawns.
;; ---------------------------------------------------------------------------

(defn- cell-top-left
  "Image top-left [px py] of integer cell [x y]. Equals interp/draw-pos for a
   settled entity; the y-flip puts world y=0 at the bottom of the image."
  [x y ts height]
  [(* (long x) (long ts)) (* (- (long height) (long y) 1) (long ts))])

(defn- draw-terrain [^java.awt.Graphics2D g world ts height]
  (let [grid  (:grid world)
        width (long (:width grid))]
    (dotimes [y height]
      (dotimes [x width]
        (let [t       (tile/tile-at grid x y)
              [px py] (cell-top-left x y ts height)]
          (.setColor g (->color (tile/terrain-color t)))
          (.fillRect g px py ts ts)
          (.setColor g grid-line)
          (.drawRect g px py ts ts))))))

(defn- draw-zones [^java.awt.Graphics2D g world ts height]
  (.setColor g (Color. 230 210 60 70))
  (doseq [[x y] (zone/stockpile-cells world)]
    (let [[px py] (cell-top-left x y ts height)]
      (.fillRect g px py ts ts))))

(defn- draw-items [^java.awt.Graphics2D g world ts height]
  (let [ts  (long ts)
        pad (max 2 (quot ts 4))
        sz  (- ts (* 2 pad))]
    (doseq [it (entity/items world)
            :when (:pos it)]
      (let [[x y]   (:pos it)
            [px py] (cell-top-left x y ts height)]
        (.setColor g (get material-colors (:material it) unknown-color))
        (.fillRect g (+ px pad) (+ py pad) sz sz)))))

(defn- draw-progress-bar
  "A thin work-progress bar across the bottom of a footprint rect."
  [^java.awt.Graphics2D g px py w h frac]
  (let [bar-h (max 2 (quot (long h) 6))
        by    (- (+ (long py) (long h)) bar-h)
        fw    (long (* (long w) (min 1.0 (max 0.0 (double frac)))))]
    (.setColor g deliver-bar-bg)
    (.fillRect g px by w bar-h)
    (.setColor g work-bar-color)
    (.fillRect g px by fw bar-h)))

(defn- draw-buildings [^java.awt.Graphics2D g world ts height]
  (let [ts (long ts)]
    (.setStroke g (BasicStroke. (float (max 1.0 (/ ts 12.0)))))
    (doseq [b (entity/buildings world)]
      (let [cells (entity/footprint b)
            [ox oy] (reduce (fn [[mnx mny] [x y]] [(min mnx x) (min mny y)]) [Long/MAX_VALUE Long/MAX_VALUE] cells)
            [w h]  (:size b [1 1])
            [px _] (cell-top-left ox oy ts height)
            ;; footprint top-left in image space is the MAX world-y row's top
            top-py (second (cell-top-left ox (+ (long oy) (long h) -1) ts height))
            rw     (* (long w) ts)
            rh     (* (long h) ts)]
        (cond
          (entity/built? b)
          (do (.setColor g (if (= :door (:def b)) door-color wall-color))
              (.fillRect g px top-py rw rh))

          (entity/blueprint? b)
          (let [d            (defs/thing (:def b))
                cost         (:cost d {})
                delivered    (:delivered b {})
                fully?       (every? (fn [[m n]] (>= (long (get delivered m 0)) (long n))) cost)
                work-to      (long (:work-to-build d 1))
                work-done    (long (:work-done b 0))]
            (.setColor g (if fully? ready-fill ghost-fill))
            (.fillRect g px top-py rw rh)
            (.setColor g ghost-stroke)
            (.drawRect g px top-py (dec rw) (dec rh))
            (draw-progress-bar g px top-py rw rh (/ (double work-done) (double work-to)))))))))

(defn- draw-pawns [^java.awt.Graphics2D g world ts height]
  (let [ts  (long ts)
        pad (max 1 (quot ts 6))
        d   (- ts (* 2 pad))]
    (.setStroke g (BasicStroke. (float (max 1.0 (/ ts 10.0)))))
    (doseq [p (entity/pawns world)
            :when (:pos p)]
      (let [[px py] (interp/draw-pos p ts height)]
        (.setColor g pawn-fill)
        (.fillOval g (+ (long px) pad) (+ (long py) pad) d d)
        (.setColor g pawn-outline)
        (.drawOval g (+ (long px) pad) (+ (long py) pad) d d)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- draw-ui-rect
  "Draw one UI button-spec {:rect [x y w h] :label :fill} over the world image.
   Rects are UI-cam coords (Y-up, origin bottom-left), flipped to image space so a
   bottom-left-anchored widget lands bottom-left of the image. Lets a UI overlay
   (the build menu, future work-priority grid) be validated by eye, since the GL
   widgets can't run headlessly."
  [^java.awt.Graphics2D g img-h {:keys [rect label fill]}]
  (let [[x y w h] rect
        x (long x) w (long w) h (long h)
        top (- (long img-h) (+ (long y) h))]
    (.setColor g (if fill (->color (subvec fill 0 3) (nth fill 3 1.0)) (Color. 41 41 51)))
    (.fillRect g x top w h)
    (.setColor g (Color. 140 128 102))
    (.drawRect g x top w h)
    (when label
      (.setColor g Color/WHITE)
      (.drawString g ^String (str label) (+ x 5) (+ top (- h 7))))))

(defn render-image
  "Rasterize `world` to a BufferedImage. opts: {:tile-px N :ui-rects [...]}.
   :ui-rects (optional) is a seq of {:rect [x y w h] :label :fill [r g b a]} UI
   button-specs drawn over the world, for validating UI overlays by eye. Pure;
   opens no window. The layer order mirrors the GL compositor."
  (^BufferedImage [world] (render-image world {}))
  (^BufferedImage [world {:keys [tile-px ui-rects] :or {tile-px default-tile-px}}]
   (let [grid   (:grid world)
         width  (long (:width grid))
         height (long (:height grid))
         ts     (long tile-px)
         img    (BufferedImage. (* width ts) (* height ts) BufferedImage/TYPE_INT_ARGB)
         g      (.createGraphics img)]
     (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
     (draw-terrain   g world ts height)
     (draw-zones     g world ts height)
     (draw-items     g world ts height)
     (draw-buildings g world ts height)
     (draw-pawns     g world ts height)
     (doseq [r ui-rects] (draw-ui-rect g (.getHeight img) r))
     (.dispose g)
     img)))

(defn write-png!
  "Render `world` and write it as a PNG to `path` (creating parent dirs).
   Returns the absolute path. opts as render-image."
  ([world path] (write-png! world path {}))
  ([world path opts]
   (let [f (File. ^String path)]
     (when-let [parent (.getParentFile f)] (.mkdirs parent))
     (ImageIO/write (render-image world opts) "png" f)
     (.getAbsolutePath f))))
