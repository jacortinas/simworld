(ns sim.pixel-art-test
  "Tests for the pixel-art authoring renderer (Java2D, headless). Verifies the grid
   -> image sizing, palette/transparency mapping, and that the authored sprite
   grids are well-formed (a typo'd row would render a broken sprite)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.pixel-art :as pa])
  (:import
   (java.awt.image BufferedImage)
   (java.io File)
   (javax.imageio ImageIO)))

(defn- alpha [argb] (bit-and (unsigned-bit-shift-right argb 24) 0xff))
(defn- red   [argb] (bit-and (unsigned-bit-shift-right argb 16) 0xff))

(deftest rows->image-sizes-by-grid-and-scale
  (let [img (pa/rows->image ["ab" "cd"]
                            {\a [255 0 0] \b [0 255 0] \c [0 0 255] \d [255 255 0]} 4)]
    (is (instance? BufferedImage img))
    (is (= 8 (.getWidth img))  "2 cols * scale 4")
    (is (= 8 (.getHeight img)) "2 rows * scale 4")))

(deftest palette-maps-chars-and-blanks-are-transparent
  (let [img (pa/rows->image ["a "] {\a [255 0 0]} 1)]
    (is (= 255 (alpha (.getRGB img 0 0))) "a mapped char is opaque")
    (is (= 255 (red   (.getRGB img 0 0))) "with its red channel")
    (is (zero? (alpha (.getRGB img 1 0))) "a space is transparent")))

(deftest authored-sprites-are-well-formed-rectangles
  (testing "every authored sprite is a rectangular grid (no mis-typed row)"
    (doseq [[name {:keys [rows]}] pa/sprites]
      (let [w (count (first rows))]
        (is (every? #(= w (count %)) rows) (str name " has ragged rows")))))
  (testing "the door is a 16x16 grid at scale 2 (a 32x32 tile)"
    (let [{:keys [rows scale]} (:door pa/sprites)]
      (is (= 16 (count rows)))
      (is (every? #(= 16 (count %)) rows))
      (is (= 2 scale)))))

(deftest write-sprite!-produces-a-valid-png
  (let [tmp  (str (System/getProperty "java.io.tmpdir") "/sim-pa-" (System/nanoTime) ".png")
        path (pa/write-sprite! ["ab" "cd"]
                               {\a [1 2 3] \b [4 5 6] \c [7 8 9] \d [10 11 12]} 3 tmp)
        f    (File. ^String path)]
    (try
      (is (.exists f))
      (is (some? (ImageIO/read f)) "round-trips as a valid PNG")
      (is (= 6 (.getWidth (ImageIO/read f))) "2 cols * scale 3")
      (finally (.delete f)))))
