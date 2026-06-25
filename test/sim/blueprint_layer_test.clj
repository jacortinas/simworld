(ns sim.blueprint-layer-test
  "Pure-core tests for the blueprints render layer. The tint blit + bar fill are
   the untested GL edge (validated by eye via the dev snapshotter); the bill/work
   reads and the bar geometry are pure and tested here."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs                     :as defs]
   [sim.entity                   :as entity]
   [sim.render.layers.blueprints :as bp]))

(use-fixtures :each (fn [t] (defs/load!) (t)))

(deftest ready?-tracks-cost-vs-delivered
  (testing "a wall (:cost {:stone 5}) is ready only once all 5 stone are delivered"
    (let [base (entity/make-blueprint :wall [0 0])]
      (is (not (bp/ready? base)) "0 of 5 delivered")
      (is (not (bp/ready? (assoc base :delivered {:stone 4}))) "4 of 5")
      (is (bp/ready? (assoc base :delivered {:stone 5})) "5 of 5")
      (is (bp/ready? (assoc base :delivered {:stone 9})) "over-delivered is still ready"))))

(deftest work-fraction-is-work-done-over-target
  (let [base (entity/make-blueprint :wall [0 0])]      ; :work-to-build 120
    (is (= 0.0 (bp/work-fraction base)) "nothing built yet")
    (is (= 0.5 (bp/work-fraction (assoc base :work-done 60))))
    (is (= 1.0 (bp/work-fraction (assoc base :work-done 120))))
    (is (= 1.0 (bp/work-fraction (assoc base :work-done 999))) "clamped to 1.0")))

(deftest progress-bar-rects-scales-fill-by-fraction
  (let [bar-h     (max 2 (quot 32 6))
        [bg fill] (bp/progress-bar-rects 10 20 64 32 0.25)]
    (is (= [10 20 64 bar-h] bg) "background spans the full footprint width")
    (is (= 16 (nth fill 2)) "fill width = 64 * 0.25")
    (is (= [10 20] (subvec fill 0 2)) "fill shares the bar origin"))
  (testing "fraction is clamped to [0,1]"
    (is (= 0 (nth (second (bp/progress-bar-rects 0 0 50 32 -1)) 2)) "negative -> empty")
    (is (= 50 (nth (second (bp/progress-bar-rects 0 0 50 32 2)) 2)) "over 1 -> full")))
