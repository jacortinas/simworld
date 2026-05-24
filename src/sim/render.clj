(ns sim.render
  "Console renderer.

   Draws an ASCII view of the grid into the terminal. Precedence at each
   tile: pawn (@) > overlay char > item char > terrain char.

   Two things to know:
     1. We pre-build the full frame as one big string and emit it with a
        single `print` + `flush`. Per-line `println` would flicker badly.
     2. The renderer is pure-ish: (frame world) returns a string. (draw!)
        is the impure half that pushes it to stdout."
  (:require
   [sim.entity :as entity]
   [sim.tile   :as tile]))

(set! *warn-on-reflection* true)

(def ^:private ansi-clear "[2J[H")

(defn- pawns-by-pos
  "Index pawns by their [x y] tile for O(1) lookup during render."
  [world]
  (into {}
        (comp (filter #(= :pawn (:kind %)))
              (map (juxt :pos identity)))
        (vals (:entities world))))

(defn- items-by-pos
  "Index ground-items by [x y] for O(1) render lookup.
   Items being carried (:pos nil) are skipped."
  [world]
  (into {}
        (comp (filter (fn [i] (and (= :item (:kind i)) (:pos i))))
              (map (juxt :pos identity)))
        (vals (:entities world))))

(defn frame
  "Build a single frame string for the given world. Pure — returns a string.

   `overlay` is an optional `{[x y] char}` map that draws characters on top
   of the terrain but underneath pawns. Used for debug visualizations like
   path overlays and aggro radii — anything that shouldn't be part of the
   world state itself."
  (^String [world] (frame world nil))
  (^String [world overlay]
   (let [{:keys [grid clock entities]} world
         {:keys [^long width ^long height]} grid
         pawn-here (pawns-by-pos world)
         item-here (items-by-pos world)
         sb        (StringBuilder.)
         pawn-count (count (filter #(= :pawn (:kind %)) (vals entities)))
         item-count (count (filter #(and (= :item (:kind %)) (:pos %))
                                   (vals entities)))]
     (.append sb (str "Tick " clock
                      " | pawns " pawn-count
                      " | items " item-count
                      " | events " (count (:events world))
                      " | log " (count (:log world))
                      "\n"))
     (.append sb (apply str (repeat (+ width 2) \-)))
     (.append sb \newline)
     (dotimes [y height]
       (.append sb \|)
       (dotimes [x width]
         (let [pos  [x y]
               pawn (pawn-here pos)
               over (when overlay (overlay pos))
               item (item-here pos)
               ch   (cond
                      pawn \@
                      over over
                      item (:char (entity/item-defs (:material item)))
                      :else (:char (tile/terrain-info (tile/tile-at grid x y))))]
           (.append sb ch)))
       (.append sb \|)
       (.append sb \newline))
     (.append sb (apply str (repeat (+ width 2) \-)))
     (.append sb \newline)
     (.toString sb))))

(defn draw-with-overlay!
  "Draw the world with an `{[x y] char}` overlay. Same as `draw!` but for
   debug visualizations."
  [world overlay]
  (print ansi-clear)
  (print (frame world overlay))
  (flush))

(defn draw!
  "Write a frame for `world` to stdout. Impure."
  [world]
  (print ansi-clear)
  (print (frame world))
  (flush))
