(ns sim.ui.layout-test
  "The anchor convention is pure geometry; these pin each corner so future
   widgets can trust where :top-right etc. land."
  (:require
   [clojure.test  :refer [deftest is testing]]
   [sim.ui.layout :as layout]))

;; A 800x600 viewport, a 100x20 box, pad = 8. Expected bottom-left [x y] per
;; corner (UI-cam, Y-up):
;;   left   x = 8                    right  x = 800-8-100 = 692
;;   bottom y = 8                    top    y = 600-8-20  = 572
(deftest anchor-pins-each-corner
  (let [vw 800 vh 600 w 100 h 20]
    (testing "the four corners inset by pad, in UI-cam Y-up coords"
      (is (= [8.0   572.0] (layout/anchor :top-left     vw vh w h)))
      (is (= [692.0 572.0] (layout/anchor :top-right    vw vh w h)))
      (is (= [8.0   8.0]   (layout/anchor :bottom-left  vw vh w h)))
      (is (= [692.0 8.0]   (layout/anchor :bottom-right vw vh w h))))))

(deftest anchor-tracks-viewport-size
  (testing "right/top edges follow the viewport so resize stays pinned"
    ;; widen + shorten: right edge moves with vw, top edge with vh
    (is (= [892.0 372.0] (layout/anchor :top-right 1000 400 100 20)))))
