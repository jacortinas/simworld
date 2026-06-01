(ns sim.pathgrid
  "Derived per-cell traversal-cost grid (RimWorld's int[] pathGrid, in Clojure's
   primitive idiom). costs[idx] = terrain move-cost, or Double/POSITIVE_INFINITY
   if the terrain is impassable OR a blocking building occupies the cell.

   PathGrid is a PURE function of (terrain grid, building set), so it is MEMOIZED
   by [grid identity, building-set identity] in a size-1 defonce atom, never
   stored in the world. (:kinds :building) is a persistent sorted-set whose
   identity changes on every building add/remove (and never on pawn/item
   changes), so the memo invalidates exactly when passability changes. This is
   the Stage 1 regions-cache trick one layer up: it cannot go stale, needs no
   chokepoint wiring, and is not saved.

   Depends ONLY on sim.tile (reads the world by raw get-in), so pathfinding/
   regions can require it without an entity cycle."
  (:require
   [sim.tile :as tile]))

(set! *warn-on-reflection* true)

(defn build
  "Per-cell cost double-array for `grid`, INFINITY at each blocking building.
   `buildings` is a seq of building entities; one with :blocks-path? and an
   in-bounds :pos stamps INFINITY at its cell. Pure."
  [{:keys [width height tiles]} buildings]
  (let [width  (long width)
        height (long height)
        n      (* width height)
        costs  (double-array n)]
    (dotimes [i n]
      (let [t (nth tiles i)]
        (aset costs i (if (tile/passable? t)
                        (double (tile/move-cost t))
                        Double/POSITIVE_INFINITY))))
    (doseq [b buildings]
      (when (:blocks-path? b)
        (let [[x y] (:pos b)]
          (when (and x y (tile/in-bounds? width height x y))
            (aset costs (tile/idx width x y) Double/POSITIVE_INFINITY)))))
    {:width width :height height :costs costs}))

(defn cost
  "Traversal cost at (x, y); INFINITY if out of bounds or blocked."
  ^double [pathgrid x y]
  (let [{:keys [width height]} pathgrid
        ^doubles costs (:costs pathgrid)]
    (if (tile/in-bounds? width height x y)
      (aget costs (tile/idx width x y))
      Double/POSITIVE_INFINITY)))

(defn passable?
  "True iff (x, y) is in-bounds and its cost is finite."
  [pathgrid x y]
  (< (cost pathgrid x y) Double/POSITIVE_INFINITY))

;; Size-1 memo: {:grid <grid> :bset <building-id-set> :pathgrid <pg>}.
(defonce ^:private cache (atom nil))

(defn- building-seq
  "Building entities of `world` (read by raw get-in; no sim.entity dependency)."
  [world]
  (keep #(get-in world [:entities %]) (get-in world [:kinds :building])))

(defn for-world
  "Memoized PathGrid for `world`, keyed on [grid identity, building-set identity].
   A new grid or building set rebuilds; otherwise returns the cached value. A
   world with no buildings yields a terrain-only PathGrid."
  [world]
  (let [grid (:grid world)
        bset (get-in world [:kinds :building])
        c    @cache]
    (if (and c (identical? grid (:grid c)) (identical? bset (:bset c)))
      (:pathgrid c)
      (let [pg (build grid (building-seq world))]
        (reset! cache {:grid grid :bset bset :pathgrid pg})
        pg))))
