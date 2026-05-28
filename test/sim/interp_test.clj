(ns sim.interp-test
  "Pure render interpolation — sim.render.interp/draw-pos. This is the only place
   the glide PROGRESS becomes a float; the sim stays integer-tick. Headless: the
   layers' draw fns issue GL calls, but where-to-draw is pure geometry, same
   split as the debug/selection layers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.interp :as interp]))

;; Fixtures: tile-size 32, grid-height 5. Bottom-left anchor with the
;; (height-1-y) Y-flip, identical to terrain/pawns/selection.

(deftest settled-entity-snaps-to-its-cell
  (testing "with no :move in flight, draw-pos is the cell's flipped world pixel"
    (is (= [64.0 96.0] (interp/draw-pos {:pos [2 1]} 32 5))
        "[2 1] -> px 2*32=64, py (5-1-1)*32=96")))

(deftest glide-start-draws-at-the-cell-being-left
  (testing "at elapsed 0 the sprite is still at :from, though :pos is the dest"
    (let [pawn {:pos [1 0]
                :job {:move {:from [0 0] :to [1 0] :elapsed 0 :cost 10}}}]
      (is (= [0.0 128.0] (interp/draw-pos pawn 32 5))
          "drawn at [0 0] -> px 0, py (5-0-1)*32=128"))))

(deftest glide-midpoint-is-halfway-between-cells
  (testing "halfway through a horizontal segment, drawn halfway across the tile"
    (let [pawn {:pos [1 0]
                :job {:move {:from [0 0] :to [1 0] :elapsed 5 :cost 10}}}]
      (is (= [16.0 128.0] (interp/draw-pos pawn 32 5))
          "px lerps 0->32 at t=0.5 -> 16; py stays 128"))))

(deftest glide-interpolates-the-flipped-y-axis
  (testing "a vertical segment lerps the y, with the flip applied to the float"
    (let [pawn {:pos [0 1]
                :job {:move {:from [0 0] :to [0 1] :elapsed 5 :cost 10}}}]
      (is (= [0.0 112.0] (interp/draw-pos pawn 32 5))
          "ly lerps 0->1 at t=0.5 -> 0.5; py (5-0.5-1)*32=112"))))

(deftest glide-interpolates-both-axes-on-a-diagonal
  (testing "a diagonal segment lerps x and y together"
    (let [pawn {:pos [1 1]
                :job {:move {:from [0 0] :to [1 1] :elapsed 5 :cost 10}}}]
      (is (= [16.0 112.0] (interp/draw-pos pawn 32 5))
          "(lx,ly) lerp 0->1 at t=0.5 -> (0.5,0.5); px 16, py (5-0.5-1)*32=112"))))
