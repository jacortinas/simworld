(ns sim.sprites-test
  "Validates the sprite-sheet mappings against the real 32rogues files —
   without a GL context. This is the headless safety net for rendering: it
   catches a wrong/out-of-range cell, a missing terrain mapping, or the asset
   path failing to resolve, none of which the compiler would flag.

   Run from the project dir (`sim/`), same as the game launches — the relative
   `32rogues/...` path is exactly what sim.render.sprites uses at runtime, so
   a green test also proves that path resolves."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [sim.render.sprites :as sprites]
   [sim.defs :as defs]))

(def ^:private sheet-files
  {:tiles    "32rogues/tiles.png"
   :rogues   "32rogues/rogues.png"
   :items    "32rogues/items.png"
   :animated "32rogues/animated-tiles.png"})

(def ^:private expected-grid    ; [cols rows] of 32px cells
  {:tiles    [17 26]
   :rogues   [7 7]
   :items    [11 26]
   :animated [11 12]})

(defn- png-dims
  "Read [width height] in pixels from a PNG's IHDR (bytes 16-23, big-endian)."
  [path]
  (with-open [in (io/input-stream path)]
    (let [b (byte-array 24)]
      (.read in b)
      (letfn [(u32 [off]
                (reduce (fn [acc i] (+ (* acc 256) (bit-and (aget b i) 0xff)))
                        0 (range off (+ off 4))))]
        [(u32 16) (u32 20)]))))

(deftest sheets-exist-and-match-expected-grid
  (doseq [[k path] sheet-files]
    (testing (name k)
      (is (.exists (io/file path))
          (str path " not found — run tests from sim/"))
      (let [[w h] (png-dims path)]
        (is (= [(quot w 32) (quot h 32)] (expected-grid k))
            "PNG grid dims should match the expected cell grid")))))

(deftest every-terrain-type-has-a-sprite
  (doseq [k (defs/ids :terrain)]
    (is (contains? sprites/terrain->cell k)
        (str "terrain " k " has no sprite cell — would fall back to grass"))))

(deftest mapped-cells-are-in-bounds
  (doseq [[k [sheet c r]] sprites/terrain->cell]
    (let [[cols rows] (expected-grid sheet)]
      (testing (str "terrain " (name k) " on sheet " (name sheet))
        (is (and (<= 0 c) (< c cols)) (str "col " c " out of 0.." (dec cols)))
        (is (and (<= 0 r) (< r rows)) (str "row " r " out of 0.." (dec rows))))))
  (let [[rc rr] (expected-grid :rogues)
        [c r]   @#'sprites/pawn-cell]
    (testing "pawn cell"
      (is (and (<= 0 c) (< c rc)) (str "col " c " out of 0.." (dec rc)))
      (is (and (<= 0 r) (< r rr)) (str "row " r " out of 0.." (dec rr))))))

(deftest every-item-material-has-a-sprite
  (doseq [m (defs/ids :material)]
    (is (contains? sprites/material->cell m)
        (str "item material " m " has no sprite cell"))))

(deftest item-cells-in-bounds
  (doseq [[m [sheet c r]] sprites/material->cell]
    (let [[cols rows] (expected-grid sheet)]
      (testing (str "item " (name m) " on sheet " (name sheet))
        (is (and (<= 0 c) (< c cols)) (str "col " c " out of 0.." (dec cols)))
        (is (and (<= 0 r) (< r rows)) (str "row " r " out of 0.." (dec rows)))))))
