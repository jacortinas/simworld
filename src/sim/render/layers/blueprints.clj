(ns sim.render.layers.blueprints
  "Blueprints layer: unbuilt building DESIGNATIONS drawn as translucent ghosts of
   their real sprite, with a construction-progress bar. Drawn just above the
   buildings layer (which now draws only BUILT structures) and below pawns, so a
   ghost reads as 'planned here' over the floor.

   A ghost is tinted CYAN while it still needs material and GREEN once every
   material in its :cost is delivered (ready to build). A thin bar across the
   bottom of its footprint fills with construction work (work-done / work-to-build).

   Same pure-core / untested-GL split as the debug and selection layers: ready?,
   work-fraction and progress-bar-rects are pure and headless-tested; the tint
   blit and the bar fill are the GL edge."
  (:require
   [sim.defs           :as defs]
   [sim.entity         :as entity]
   [sim.render.sprites :as sprites])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Pure core (headless-tested): the bill/work reads + the bar geometry.
;; ---------------------------------------------------------------------------

(defn ready?
  "Is every material in the blueprint's :cost fully delivered (so a constructor
   can start)? Reads the bill from the def at use time. A def with no :cost is
   vacuously ready."
  [blueprint]
  (let [cost      (:cost (defs/thing (:def blueprint)) {})
        delivered (:delivered blueprint {})]
    (every? (fn [[m n]] (>= (long (get delivered m 0)) (long n))) cost)))

(defn work-fraction
  "Construction progress in [0.0, 1.0]: work-done / work-to-build (read from the
   def). 0.0 when nothing is built yet or the def has no work target."
  ^double [blueprint]
  (let [wt (long (:work-to-build (defs/thing (:def blueprint)) 1))]
    (if (pos? wt)
      (min 1.0 (/ (double (:work-done blueprint 0)) (double wt)))
      0.0)))

(defn progress-bar-rects
  "Background + fill rects [[x y w h] [x y w h]] for a thin work bar along the
   bottom edge of a footprint whose bottom-left world-pixel is [px py] and whose
   pixel width is `fw-px`. frac in [0,1] is clamped. Pure; the layer tints the
   first rect dark and the second the work color."
  [px py fw-px tile-size frac]
  (let [ts (long tile-size)
        bh (max 2 (quot ts 6))
        w  (long fw-px)
        f  (long (* (long fw-px) (min 1.0 (max 0.0 (double frac)))))]
    [[(long px) (long py) w bh]
     [(long px) (long py) f bh]]))

;; ---------------------------------------------------------------------------
;; GL draw (untested edge).
;; ---------------------------------------------------------------------------

(def ^:private ghost-tint (Color. 0.40 0.72 1.0 0.50))   ; cyan: needs material
(def ^:private ready-tint (Color. 0.45 0.95 0.60 0.55))  ; green: ready to build
(def ^:private bar-bg     (Color. 0.0 0.0 0.0 0.55))
(def ^:private bar-fill   (Color. 0.40 0.92 0.55 1.0))

(defn- tile-anchor
  "Bottom-left world-pixel [px py] of tile (tx, ty) with the (height-1-y) flip
   every world layer shares."
  [tx ty height tile-size]
  (let [ts (long tile-size)]
    [(* (long tx) ts) (* (- (long height) 1 (long ty)) ts)]))

(defn draw
  [world ^SpriteBatch batch tile-size ^Texture pixel now-ms]
  (let [height (long (:height (:grid world)))
        ts     (long tile-size)]
    (doseq [b (entity/blueprints world)]
      (let [tint (if (ready? b) ready-tint ghost-tint)]
        ;; 1. ghost the real sprite across every footprint cell.
        (doseq [[cx cy] (entity/footprint b)]
          (sprites/draw-graphic-tinted! batch (:graphic b) (tile-anchor cx cy height ts) ts tint now-ms))
        ;; 2. a work-progress bar along the footprint's bottom row.
        (let [[ox oy]            (:pos b)
              [w _]              (:size b [1 1])
              [px py]            (tile-anchor ox oy height ts)   ; oy = lowest world row -> screen bottom
              fw                 (* (long w) ts)
              [[bx by bw bh]
               [_  _  fwfill _]] (progress-bar-rects px py fw ts (work-fraction b))]
          (.setColor batch bar-bg)
          (.draw batch pixel (float bx) (float by) (float bw) (float bh))
          (.setColor batch bar-fill)
          (.draw batch pixel (float bx) (float by) (float fwfill) (float bh))
          (.setColor batch Color/WHITE))))))
