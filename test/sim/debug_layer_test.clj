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

(deftest l-shaped-path-becomes-axis-aligned-rects
  (testing "each consecutive tile pair → one thin rect connecting tile
            centers, with the (height-1-y) Y-flip applied"
    (let [t    debug/line-thickness
          ht   (/ t 2.0)
          segs (debug/path->segments [[1 2] [2 2] [2 1]] 32 5)]
      ;; centers (ts=32, height=5): [1 2]->(48,80) [2 2]->(80,80) [2 1]->(80,112)
      ;;   pair 1 horizontal @ y=80, spanning x 48..80
      ;;   pair 2 vertical   @ x=80, spanning y 80..112
      (is (= [[48.0        (- 80.0 ht) 32.0 t]
              [(- 80.0 ht) 80.0        t    32.0]]
             segs)))))
