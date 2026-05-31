(ns sim.render.graphic
  "Pure render-side helpers for graphic defs. No GL here: sim.render.sprites
   turns a source into a TextureRegion and the layers issue the draw. Headless-
   tested, the same pure-core / untested-GL split as every layer.

   Four pure fns: which way a moving pawn faces (facing-for), which source and
   whether to mirror it (source-for), the world-pixel quad given the tile-unit
   draw-size/offset (draw-rect), and the wall-clock animation frame index (frame,
   relocated here when the one-fn sim.render.anim ns was removed).")

(set! *warn-on-reflection* true)

(defn facing-for
  "Facing keyword (:up/:down/:left/:right) for a pawn's in-flight :move segment
   {:from [fx fy] :to [tx ty]}, or :down when idle (nil move) or the segment is
   zero-length. Horizontal motion dominates, so diagonals collapse to :left/:right;
   pure vertical is :up/:down. Grid Y grows downward on the render flip, so a
   to-cell with smaller y (dy<0) is :up."
  [move]
  (if-let [{:keys [from to]} move]
    (let [[fx fy] from
          [tx ty] to
          dx (- (long tx) (long fx))
          dy (- (long ty) (long fy))]
      (cond
        (pos? dx) :right
        (neg? dx) :left
        (neg? dy) :up
        (pos? dy) :down
        :else     :down))
    :down))

(defn source-for
  "Resolve [source flip?] for `graphic` at `facing`. `source` is a map carrying
   :cell or :image (the shared draw-size/anim live on the graphic, not the source).
   A non-directional graphic ignores facing and never flips. A directional graphic:
   :up/:down/:left use the :facings override if present else the base source;
   :right uses an explicit :facings :right as-is (asymmetric art) else the resolved
   :left source flipped horizontally (the cheap mirror default)."
  [graphic facing]
  (let [base    (select-keys graphic [:cell :image])
        facings (:facings graphic)
        pick    (fn [f] (or (get facings f) base))]
    (cond
      (not (:directional graphic)) [base false]
      (= facing :right)            (if-let [r (:right facings)]
                                     [r false]
                                     [(pick :left) true])
      :else                        [(pick facing) false])))

(defn draw-rect
  "World-pixel [x y w h] to draw `graphic` at, given the layer-computed bottom-left
   anchor [px py] (already Y-flipped) and the pixel `tile-size`. :draw-size [sw sh]
   (tile units, default [1 1]) scales the quad; :draw-offset [ox oy] (tile units,
   default [0 0]) shifts it. A [1 2] sprite grows two tiles tall from the anchor,
   so it extends upward."
  [graphic [px py] tile-size]
  (let [ts      (double tile-size)
        [sw sh] (:draw-size graphic [1 1])
        [ox oy] (:draw-offset graphic [0 0])]
    [(+ (double px) (* (double ox) ts))
     (+ (double py) (* (double oy) ts))
     (* (double sw) ts)
     (* (double sh) ts)]))

(defn frame
  "0-based frame index of a `frames`-long loop at `fps`, at wall-clock `now-ms`.
   Pure real-time animation math: same (now-ms, fps, frames) gives the same frame,
   so it never perturbs determinism. now-ms is non-negative, so the integer quot
   floors as intended. sim.render.sprites/graphic-region uses this to step an
   animated graphic's cell column."
  [now-ms fps frames]
  (mod (quot (* (long now-ms) (long fps)) 1000) (long frames)))
