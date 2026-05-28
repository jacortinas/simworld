(ns sim.selection-layer-test
  "Pure geometry for the world-space selection box — sim.render.layers.selection.
   The draw fn issues libGDX calls (untestable headless); selection-box-rects
   is the testable part, same split as the debug layer's path->segments."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.layers.selection :as selection]))

(deftest box-is-four-edge-rects-around-a-world-pixel
  (testing "four thin rects framing the tile whose bottom-left world-pixel is
            [px py]; the Y-flip now lives upstream in sim.render.interp/draw-pos"
    (let [t    selection/box-thickness
          ;; bottom-left pixel [64 96] (e.g. tile [2 1] at ts=32, height=5);
          ;; tile rect is (64,96) 32x32.
          rects (selection/selection-box-rects [64.0 96.0] 32)]
      ;; order: bottom, top, left, right
      (is (= [[64.0          96.0          32.0 t]
              [64.0          (- 128.0 t)   32.0 t]
              [64.0          96.0          t    32.0]
              [(- 96.0 t)    96.0          t    32.0]]
             rects)))))

(deftest box-thickness-is-positive
  (testing "box-thickness is a usable positive line width"
    (is (pos? selection/box-thickness))))
