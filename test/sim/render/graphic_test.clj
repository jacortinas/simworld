(ns sim.render.graphic-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.graphic :as graphic]))

(deftest facing-for-derives-direction-from-move
  (testing "cardinals map to up/down/left/right"
    (is (= :right (graphic/facing-for {:from [5 5] :to [6 5]})))
    (is (= :left  (graphic/facing-for {:from [5 5] :to [4 5]})))
    (is (= :up    (graphic/facing-for {:from [5 5] :to [5 4]})))
    (is (= :down  (graphic/facing-for {:from [5 5] :to [5 6]}))))
  (testing "diagonals collapse onto left/right by horizontal sign"
    (is (= :right (graphic/facing-for {:from [5 5] :to [6 4]})))  ; up-right
    (is (= :right (graphic/facing-for {:from [5 5] :to [6 6]})))  ; down-right
    (is (= :left  (graphic/facing-for {:from [5 5] :to [4 4]})))  ; up-left
    (is (= :left  (graphic/facing-for {:from [5 5] :to [4 6]})))) ; down-left
  (testing "idle (no move) and a zero-length segment face down"
    (is (= :down (graphic/facing-for nil)))
    (is (= :down (graphic/facing-for {:from [5 5] :to [5 5]})))))

(deftest source-for-resolves-source-and-flip
  (let [single {:image "graphics/tree.png"}
        multi  {:image "graphics/colonist.png" :directional true}
        multi+ {:image "graphics/c.png" :directional true
                :facings {:left  {:image "graphics/c_left.png"}
                          :right {:image "graphics/c_right.png"}}}]
    (testing "a non-directional graphic ignores facing, never flips"
      (is (= [{:image "graphics/tree.png"} false] (graphic/source-for single :right))))
    (testing "directional without overrides: base for up/down/left, base+flip for right"
      (is (= [{:image "graphics/colonist.png"} false] (graphic/source-for multi :up)))
      (is (= [{:image "graphics/colonist.png"} false] (graphic/source-for multi :left)))
      (is (= [{:image "graphics/colonist.png"} true]  (graphic/source-for multi :right))))
    (testing "an explicit :right override wins as-is (asymmetric art), no flip"
      (is (= [{:image "graphics/c_right.png"} false] (graphic/source-for multi+ :right))))
    (testing "with a :left override and no :right, right flips the left override"
      (let [left-only {:image "graphics/c.png" :directional true
                       :facings {:left {:image "graphics/c_left.png"}}}]
        (is (= [{:image "graphics/c_left.png"} true] (graphic/source-for left-only :right)))))))

(deftest draw-rect-sizes-and-offsets-in-tile-units
  (testing "default draw-size [1 1], no offset -> a tile-sized quad at the anchor"
    (is (= [64.0 96.0 32.0 32.0] (graphic/draw-rect {} [64 96] 32))))
  (testing "draw-size [1 2] grows two tiles tall, anchored at the bottom-left"
    (is (= [64.0 96.0 32.0 64.0] (graphic/draw-rect {:draw-size [1 2]} [64 96] 32))))
  (testing "draw-offset shifts the quad in tile units"
    (is (= [80.0 104.0 32.0 32.0]
           (graphic/draw-rect {:draw-offset [0.5 0.25]} [64 96] 32)))))

(deftest frame-steps-and-loops-with-wall-clock
  (testing "frame floors (now-ms * fps / 1000) then wraps at `frames`"
    (let [fps 6 n 11]
      (is (= 0  (graphic/frame 0    fps n)))
      (is (= 0  (graphic/frame 166  fps n)))
      (is (= 1  (graphic/frame 167  fps n)))
      (is (= 6  (graphic/frame 1000 fps n)))
      (is (= 10 (graphic/frame 1833 fps n)))
      (is (= 0  (graphic/frame 1834 fps n))))))

(deftest frame-is-always-in-range
  (testing "result is a valid 0-based column for any wall-clock value"
    (doseq [now [0 1 999 123456 987654321]
            [fps n] [[6 11] [1 4] [12 6]]]
      (let [f (graphic/frame now fps n)]
        (is (and (<= 0 f) (< f n)))))))
