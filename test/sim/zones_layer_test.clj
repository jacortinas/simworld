(ns sim.zones-layer-test
  "Pure geometry for the stockpile-zone overlay — same headless discipline as
   selection-layer-test. The GL `draw` and the in-window appearance are not
   tested here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.layers.zones :as zones]))

(deftest cell-rect-y-flips
  (testing "a cell maps to a [px py w h] fill rect with the (height-1-y) flip"
    (is (= [0.0 288.0 32.0 32.0] (zones/cell-rect [0 0] 32 10)) "top-left cell")
    (is (= [64.0 0.0 32.0 32.0]  (zones/cell-rect [2 9] 32 10)) "bottom row -> py 0")))

(deftest cells->rects-covers-every-cell
  (is (= #{[0.0 288.0 32.0 32.0] [32.0 288.0 32.0 32.0]}
         (set (zones/cells->rects #{[0 0] [1 0]} 32 10)))))

(deftest drag-preview-cells-spans-the-rect
  (testing "the live preview is the inclusive rect of the in-progress drag"
    (is (= #{[0 0] [1 0] [0 1] [1 1]}
           (zones/drag-preview-cells {:start [0 0] :current [1 1]})))
    (is (nil? (zones/drag-preview-cells nil)) "no drag -> nil")))
