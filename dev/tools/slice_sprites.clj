(ns tools.slice-sprites
  "DEV-ONLY tool: slice the 32rogues cells we render into verbatim 32px loose
   PNGs under graphics/. Pure JDK ImageIO, no libGDX. Run once from a
   `clj -M:dev` REPL to (re)generate the assets; not part of the game or the
   main test suite. The manifest is the inverse of the render lookup tables that
   sim.render.sprites used to hold."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.awt.image BufferedImage)
   (javax.imageio ImageIO)))

(def ^:const cell 32)

(def manifest
  "out-name -> [sheet-path col row]. Sheets under 32rogues/; outputs to graphics/."
  {"grass"    ["32rogues/tiles.png"  4 7]
   "dirt"     ["32rogues/tiles.png"  4 8]
   "gravel"   ["32rogues/tiles.png"  4 9]
   "stone"    ["32rogues/tiles.png"  0 1]
   "wall"     ["32rogues/tiles.png"  0 2]
   "colonist" ["32rogues/rogues.png" 1 6]
   "tree"     ["32rogues/tiles.png"  2 25]
   "wood"     ["32rogues/tiles.png"  6 17]
   "rock"     ["32rogues/tiles.png"  0 18]
   "apple"    ["32rogues/items.png"  2 25]})

(defn extract
  "Fresh 32x32 ARGB image of cell (col,row) of `sheet` (top-left origin).
   Verbatim: dest and source rects are both 32px, so no scaling or filtering."
  ^BufferedImage [^BufferedImage sheet col row]
  (let [out (BufferedImage. cell cell BufferedImage/TYPE_INT_ARGB)
        g   (.createGraphics out)
        sx  (* (long col) cell)
        sy  (* (long row) cell)]
    (.drawImage g sheet
                0 0 cell cell
                sx sy (+ sx cell) (+ sy cell)
                nil)
    (.dispose g)
    out))

(defn slice-all!
  "Write every manifest entry to graphics/<name>.png. Returns the sorted output
   paths. Reads each sheet once. Run from a `clj -M:dev` REPL in the sim/ dir."
  []
  (let [sheets (atom {})]
    (doseq [[out-name [sheet-path col row]] manifest]
      (let [^BufferedImage img (or (@sheets sheet-path)
                                   (let [i (ImageIO/read (io/file sheet-path))]
                                     (swap! sheets assoc sheet-path i)
                                     i))
            target (io/file "graphics" (str out-name ".png"))]
        (io/make-parents target)
        (ImageIO/write (extract img col row) "png" target)))
    (sort (map #(str "graphics/" % ".png") (keys manifest)))))
