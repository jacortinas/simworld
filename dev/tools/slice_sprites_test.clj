(ns tools.slice-sprites-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tools.slice-sprites :as slice])
  (:import (java.awt.image BufferedImage)))

(deftest extract-copies-the-cell-verbatim
  (testing "extract lifts a 32x32 cell whose pixels match the source cell"
    (let [sheet (BufferedImage. 64 64 BufferedImage/TYPE_INT_ARGB)
          argb  (unchecked-int 0xFF112233)]
      ;; paint cell (col 1, row 0): source pixels x in 32..63, y in 0..31
      (doseq [x (range 32 64) y (range 0 32)]
        (.setRGB sheet x y argb))
      (let [out (slice/extract sheet 1 0)]
        (is (= 32 (.getWidth out)))
        (is (= 32 (.getHeight out)))
        (is (= argb (.getRGB out 0 0)) "top-left pixel copied")
        (is (= argb (.getRGB out 31 31)) "bottom-right pixel copied")))))
