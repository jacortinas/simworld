(ns sim.schedule-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.schedule :as schedule]))

(deftest bands-shape
  (is (= 1 (:normal schedule/bands)))
  (is (= 125 (:rare schedule/bands)))
  (is (= 1000 (:long schedule/bands))))

(deftest bucket-math
  (testing "home-bucket is id mod interval"
    (is (= 7 (schedule/home-bucket 7 125)))
    (is (= 5 (schedule/home-bucket 130 125))))
  (testing "due-bucket is clock mod interval"
    (is (= 0 (schedule/due-bucket 0 125)))
    (is (= 4 (schedule/due-bucket 129 125))))
  (testing "due? is true exactly when home-bucket == due-bucket"
    (is (true?  (schedule/due? 7 125 7)))     ; clock 7, id 7 -> both bucket 7
    (is (true?  (schedule/due? 132 125 7)))   ; clock 132 -> bucket 7
    (is (false? (schedule/due? 8 125 7)))))   ; clock 8 -> bucket 8, id bucket 7
