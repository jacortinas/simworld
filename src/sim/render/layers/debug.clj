(ns sim.render.layers.debug
  "Debug overlay layer: dev-facing visualizations drawn over the world,
   gated by the :debug? flag in sim.ui-state. First visualization is pawn
   pathfinding routes; future ones (AI state, needs, tile coords) become more
   private draw-fns called from `draw`, all under the same one toggle.

   Like the other layers this is a pure projection: the caller owns begin/end
   and sets the batch tint; we draw with the shared 1px pixel texture (the
   solid-rect trick from sim.ui.hud) and reset the tint to white when done.

   The geometry (path->segments) is split out as a pure fn so it's unit-tested
   without a GL context — see sim.debug-layer-test."
  (:require
   [sim.entity   :as entity]
   [sim.ui-state :as ui])
  (:import
   (com.badlogic.gdx.graphics Color Texture)
   (com.badlogic.gdx.graphics.g2d SpriteBatch)))

(set! *warn-on-reflection* true)

;; Line thickness (world px) of a path segment. Public: the geometry test
;; computes expected rects from it, so the value can change without editing
;; the test. ^:const (a flag, not a type hint) avoids the ^double-on-def trap.
(def ^:const line-thickness 4.0)

;; Goal marker = a small square, this fraction of a tile, on the path's end.
(def ^:const ^:private goal-size 0.4)

(def ^:private path-color (Color. 0.2 0.9 1.0 0.7))    ; cyan, semi-transparent
(def ^:private goal-color (Color. 1.0 0.85 0.2 0.9))   ; amber goal square

(defn- tile-center
  "World-pixel center [cx cy] of tile [tx ty]. The (height-1-ty) term is the
   same Y-flip terrain/pawns apply, so overlay rects register exactly with the
   sprites underneath. Returns doubles."
  [[tx ty] tile-size height]
  (let [ts (double tile-size)]
    [(+ (* (double tx) ts) (/ ts 2.0))
     (+ (* (double (- (long height) (long ty) 1)) ts) (/ ts 2.0))]))

(defn path->segments
  "Turn a path (vector of [x y] tiles) into thin axis-aligned line-segment
   rects [x y w h] (doubles, world pixels) — one per consecutive tile pair,
   each connecting the two tile centers. Paths are 4-connected so every
   segment is purely horizontal or vertical. nil / empty / single-tile → []."
  [path tile-size height]
  (let [half (/ line-thickness 2.0)]
    (mapv (fn [[a b]]
            (let [[ax ay] (tile-center a tile-size height)
                  [bx by] (tile-center b tile-size height)]
              (if (== ay by)
                ;; horizontal step: span the x-gap, line-thickness tall,
                ;; thickness centered on the shared y (hence -half).
                [(min ax bx) (- ay half) (Math/abs (double (- bx ax))) line-thickness]
                ;; vertical step: mirror — span the y-gap, thickness wide.
                [(- ax half) (min ay by) line-thickness (Math/abs (double (- by ay)))])))
          (partition 2 1 path))))

(defn remaining-path
  "The suffix of `path` from the pawn's current `path-index` onward — the road
   still ahead. index 0 → the whole path; the final index → just the goal tile;
   nil/empty → []. Clamped so a stale index can't IndexOutOfBounds the renderer.
   A pure VIEW transform: world state (the full :path) is untouched."
  [path path-index]
  (if (seq path)
    (subvec path (min (long path-index) (dec (count path))))
    []))

(defn draw
  "World-space debug overlay. No-op unless (ui/debug?). For each pawn with a
   live path, draws its route as thin segments plus a goal-tile marker, using
   the shared 1px `pixel` texture. Resets the batch tint to white when done."
  [world ^SpriteBatch batch tile-size ^Texture pixel]
  (when (ui/debug?)
    (let [height (long (:height (:grid world)))]
      (doseq [pawn (entity/pawns world)]
        (when-let [path (get-in pawn [:job :path])]
          ;; Draw only the road AHEAD: slice off tiles already walked so the
          ;; line shrinks as the pawn moves. The world's :path is untouched —
          ;; this is purely a view transform (remaining-path is pure).
          (let [ahead (remaining-path path (get-in pawn [:job :path-index] 0))]
            (.setColor batch path-color)
            (doseq [[x y w h] (path->segments ahead tile-size height)]
              (.draw batch pixel (float x) (float y) (float w) (float h)))
            ;; peek of a suffix is still the true goal tile.
            (when (seq ahead)
              (let [[cx cy] (tile-center (peek ahead) tile-size height)
                    s       (* (double tile-size) (double goal-size))
                    half    (/ s 2.0)]
                (.setColor batch goal-color)
                (.draw batch pixel
                       (float (- cx half)) (float (- cy half))
                       (float s) (float s)))))))
      (.setColor batch Color/WHITE))))
