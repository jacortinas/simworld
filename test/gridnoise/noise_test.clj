(ns gridnoise.noise-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [gridnoise.noise :as noise]))

(deftest field-is-deterministic
  (testing "same seed + coords -> identical value, across separate field fns"
    (let [f (noise/field {:seed 42})]
      (is (= (f 3 7) (f 3 7))))
    (is (= ((noise/field {:seed 42}) 3 7)
           ((noise/field {:seed 42}) 3 7)))))

(deftest field-stays-in-unit-range
  (testing "every sample is within [0,1]"
    (let [f (noise/field {:seed 1})]
      (doseq [x (range 0 40) y (range 0 40)]
        (let [v (f x y)]
          (is (<= 0.0 v 1.0) (str "out of range at " [x y] " = " v)))))))

(deftest field-is-continuous
  (testing "tiny coordinate change -> tiny value change (interpolated, not hashed)"
    (let [f (noise/field {:seed 5})]
      (is (< (Math/abs (- (f 10.0 10.0) (f 10.000001 10.0))) 1e-3)))))

(deftest different-seeds-differ
  (testing "the seed actually changes the field"
    (is (not= ((noise/field {:seed 1}) 5 5)
              ((noise/field {:seed 2}) 5 5)))))
