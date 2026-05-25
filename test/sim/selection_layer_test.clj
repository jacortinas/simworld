(ns sim.selection-layer-test
  "Pure geometry for the world-space selection box — sim.render.layers.selection.
   The draw fn issues libGDX calls (untestable headless); selection-box-rects
   is the testable part, same split as the debug layer's path->segments."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.layers.selection :as selection]))

(deftest box-is-four-edge-rects-with-y-flip
  (testing "four thin rects framing the tile's world-pixel rect, (height-1-y) flipped"
    (let [t    selection/box-thickness
          ;; tile [2 1], ts=32, grid-height=5 -> px = 2*32 = 64,
          ;; py = (5-1-1)*32 = 96; tile rect is (64,96) 32x32.
          rects (selection/selection-box-rects [2 1] 32 5)]
      ;; order: bottom, top, left, right
      (is (= [[64.0          96.0          32.0 t]
              [64.0          (- 128.0 t)   32.0 t]
              [64.0          96.0          t    32.0]
              [(- 96.0 t)    96.0          t    32.0]]
             rects)))))

(deftest box-thickness-is-positive
  (testing "box-thickness is a usable positive line width"
    (is (pos? selection/box-thickness))))
