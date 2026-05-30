(ns sim.sprites-test
  "Headless safety net for rendering content: validates graphics.edn against the
   real assets without a GL context. Image files resolve, sheet cells are in
   bounds, and every terrain/thing references a known graphic. Run from sim/, the
   same working dir the game launches from."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
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
      (is (.exists (io/file path)) (str path " not found, run tests from sim/"))
      (let [[w h] (png-dims path)]
        (is (= [(quot w 32) (quot h 32)] (expected-grid k))
            "PNG grid dims should match the expected cell grid")))))

(deftest graphic-image-files-exist
  (doseq [id (defs/ids :graphic)
          :let [g (defs/graphic id)]
          path (keep :image (cons g (vals (:facings g))))]
    (is (.exists (io/file path)) (str "graphic " id " image missing: " path))))

(deftest graphic-cells-are-in-bounds
  (doseq [id (defs/ids :graphic)
          :let [g (defs/graphic id)]
          src  (cons g (vals (:facings g)))
          :when (:cell src)
          :let [[sheet c r] (:cell src)]]
    (testing (str "graphic " id " on sheet " sheet)
      (if-let [[cols rows] (expected-grid sheet)]
        (do
          (is (and (<= 0 c) (< c cols)) (str "col " c " out of 0.." (dec cols)))
          (is (and (<= 0 r) (< r rows)) (str "row " r " out of 0.." (dec rows))))
        (is false (str "unknown sheet " sheet))))))

(deftest every-terrain-and-thing-references-a-known-graphic
  (doseq [k (defs/ids :terrain)]
    (let [gid (:graphic (defs/terrain k))]
      (is (some? (defs/graphic gid)) (str "terrain " k " -> unknown graphic " gid))))
  (doseq [k (defs/ids :thing)]
    (let [gid (:graphic (defs/thing k))]
      (is (some? (defs/graphic gid)) (str "thing " k " -> unknown graphic " gid)))))
