(ns sim.debug-layer-test
  "Tests for the debug overlay's PURE geometry — sim.render.layers.debug.

   The layer's draw fn issues libGDX calls (untestable headless), so the
   testable logic is pulled out into path->segments: tiles in, world-pixel
   rects out. Same instinct that keeps sim.simulation/tick pure."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.layers.debug :as debug]))

(deftest empty-and-trivial-paths-have-no-segments
  (testing "nil, empty, and single-tile paths yield no line segments"
    (is (= [] (debug/path->segments nil    32 5)))
    (is (= [] (debug/path->segments []      32 5)))
    (is (= [] (debug/path->segments [[2 2]] 32 5)))))

(deftest remaining-path-is-the-road-ahead
  (testing "remaining-path slices the route at the pawn's current index"
    (let [p [[1 1] [2 1] [3 1]]]
      (is (= p              (debug/remaining-path p 0)) "index 0 → whole path")
      (is (= [[2 1] [3 1]]  (debug/remaining-path p 1)) "drops traversed tiles")
      (is (= [[3 1]]        (debug/remaining-path p 2)) "at the end → goal tile only")
      (is (= [] (debug/remaining-path nil 0)) "nil → empty")
      (is (= [] (debug/remaining-path []  0)) "empty → empty"))))

(deftest cardinal-steps-become-rotated-segments
  (testing "each consecutive tile pair → one segment [x y w h angle], anchored
            at the start center's left edge (origin = left-center) with the
            (height-1-y) Y-flip; cardinal angles are 0° / 90°"
    (let [t       debug/line-thickness
          ht      (/ t 2.0)
          [s1 s2] (debug/path->segments [[1 2] [2 2] [2 1]] 32 5)]
      ;; centers (ts=32, height=5): [1 2]->(48,80) [2 2]->(80,80) [2 1]->(80,112)
      ;; pair 1 horizontal: a(48,80)->b(80,80), len 32, angle 0
      (is (= [48.0 (- 80.0 ht) 32.0 t] (vec (take 4 s1))) "horizontal geometry")
      (is (< (Math/abs (- (double (nth s1 4)) 0.0)) 1e-9) "...angle 0°")
      ;; pair 2 vertical: a(80,80)->b(80,112), len 32, angle 90
      (is (= [80.0 (- 80.0 ht) 32.0 t] (vec (take 4 s2))) "vertical geometry")
      (is (< (Math/abs (- (double (nth s2 4)) 90.0)) 1e-9) "...angle 90°"))))

(deftest diagonal-step-is-one-rotated-segment-not-an-axis-stub
  (testing "a diagonal tile pair → a single segment spanning BOTH axes, rotated
            to the true angle — the bug was rendering it as a vertical stub"
    (let [t   debug/line-thickness
          ht  (/ t 2.0)
          [s] (debug/path->segments [[0 0] [1 1]] 32 5)]
      ;; centers: [0 0]->(16,144) [1 1]->(48,112); dx=32 dy=-32 (down-right on screen)
      (is (= [16.0 (- 144.0 ht)] (vec (take 2 s))) "anchored at the start center")
      (is (< (Math/abs (- (double (nth s 2)) (Math/sqrt 2048.0))) 1e-9)
          "length = 32√2 (full diagonal span), not 32")
      (is (== (nth s 3) t) "thickness unchanged")
      (is (< (Math/abs (- (double (nth s 4)) -45.0)) 1e-9)
          "angle -45° (down-right in screen space)"))))
