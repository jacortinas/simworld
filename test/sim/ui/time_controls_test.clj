(ns sim.ui.time-controls-test
  "Pure-core tests for the top-right time cluster: the button layout and the
   Y-flipped hit-test. The draw + clock-dispatch sides read live Gdx / mutate
   the clock, so they are exercised in-app, not here."
  (:require
   [clojure.test         :refer [deftest is testing]]
   [sim.ui.time-controls :as tc]))

;; 800x600 viewport. cluster-w = 30 + 4 + 26 + 4 + 26 + 4 + 26 = 120, anchored
;; top-right: x0 = 800-8-120 = 672, y = 600-8-22 = 570. Buttons left->right:
;;   pause   [672 570 30 22]  right 702
;;   speed-1 [706 570 26 22]  right 732
;;   speed-2 [736 570 26 22]  right 762
;;   speed-3 [766 570 26 22]  right 792 (= 800 - pad)
(deftest button-rects-order-and-fit
  (let [rects (tc/button-rects 800 600)]
    (testing "four buttons, pause then the three speeds, in order"
      (is (= [:pause :speed-1 :speed-2 :speed-3] (mapv :id rects)))
      (is (= [nil 1.0 2.0 3.0] (mapv :speed rects))))
    (testing "anchored top-right: last button's right edge sits pad from the edge"
      (let [[x _ w _] (:rect (last rects))]
        (is (= 792.0 (+ (double x) (double w))))))
    (testing "all buttons share the top-anchored baseline"
      (is (every? #(= 570.0 (double (second (:rect %)))) rects)))))

(deftest hit-id-flips-y-and-finds-the-button
  (let [rects (tc/button-rects 800 600)]
    (testing "a click inside a button (Y-DOWN screen coords) returns its id"
      ;; pause rect x[672..702]; Y-up y=570..592 flips to screen sy 8..30
      (is (= :pause   (tc/hit-id rects 600 687 15)))
      ;; speed-2 rect x[736..762], same screen sy band
      (is (= :speed-2 (tc/hit-id rects 600 749 19)))
      (is (= :speed-3 (tc/hit-id rects 600 779 19))))
    (testing "a click off the cluster returns nil"
      (is (nil? (tc/hit-id rects 600 400 300)))   ; middle of screen
      (is (nil? (tc/hit-id rects 600 749 200)))))) ; right column, wrong row
