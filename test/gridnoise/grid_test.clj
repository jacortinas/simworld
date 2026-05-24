(ns gridnoise.grid-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [gridnoise.grid :as grid]))

(deftest generate-builds-cells-row-major
  (testing "generate calls (f x y) per cell, row-major"
    (let [g (grid/generate 3 2 (fn [x y] [x y]))]
      (is (= 3 (:width g)))
      (is (= 2 (:height g)))
      (is (= 6 (count (:cells g))))
      (is (= [0 0] (grid/cell-at g 0 0)))
      (is (= [2 1] (grid/cell-at g 2 1))))))

(deftest idx-and-in-bounds
  (let [g (grid/generate 3 3 (constantly :x))]
    (is (= 4 (grid/idx g 1 1)))
    (is (grid/in-bounds? g 0 0))
    (is (grid/in-bounds? g 2 2))
    (is (not (grid/in-bounds? g 3 0)))
    (is (not (grid/in-bounds? g -1 0)))
    (is (nil? (grid/cell-at g 5 5)))))

(deftest neighbors-8-clips-at-edges
  (let [g (grid/generate 3 3 (constantly :x))]
    (is (= 3 (count (grid/neighbors-8 g 0 0))) "corner has 3 neighbors")
    (is (= 5 (count (grid/neighbors-8 g 1 0))) "edge has 5 neighbors")
    (is (= 8 (count (grid/neighbors-8 g 1 1))) "center has 8 neighbors")))

(deftest map-cells-transforms-every-cell
  (let [g (grid/generate 2 2 (constantly 1))]
    (is (= [2 2 2 2] (:cells (grid/map-cells g inc))))))
