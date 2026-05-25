(ns sim.simulation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.simulation :as simulation]
   [sim.world      :as world]
   [sim.entity     :as entity]
   [sim.events     :as events]
   [sim.pathfinding :as pathfinding]
   [sim.tile       :as tile]))

(deftest clock-advances
  (let [w0 (world/initial-world {})
        w1 (simulation/tick w0)]
    (is (= 1 (:clock w1)))
    (is (= 10 (:clock (nth (iterate simulation/tick w0) 10))))))

(deftest initial-world-has-empty-schedule
  (let [w (world/initial-world {})]
    (is (= 125 (count (get-in w [:schedule :rare]))))
    (is (= 1000 (count (get-in w [:schedule :long]))))))

(deftest events-drain-on-tick
  (let [w0 (-> (world/initial-world {})
               (events/enqueue (events/event :noop)))
        w1 (simulation/tick w0)]
    (is (empty? (:events w1))
        "events should be drained each tick")))

(deftest needs-decay
  (let [w0 (-> (world/initial-world {})
               (entity/add-entity (entity/make-pawn "tester" [0 0])))
        pawn-before (first (entity/pawns w0))
        ;; 250 ticks > 2x rare interval (125) -> >= 1 decay fire for any pawn id
        w-after     (nth (iterate simulation/tick w0) 250)
        pawn-after  (first (entity/pawns w-after))]
    (is (< (get-in pawn-after [:needs :food])
           (get-in pawn-before [:needs :food]))
        "food need should have decayed after 250 ticks (rare cadence)")))

(deftest pathfinding-trivial-cases
  (let [w (world/initial-world {:width 10 :height 10})]
    (testing "same start and goal"
      (is (= [[0 0]] (pathfinding/find-path w [0 0] [0 0]))))
    (testing "straight line on grass"
      (let [p (pathfinding/find-path w [0 0] [3 0])]
        (is (= [0 0] (first p)))
        (is (= [3 0] (last p)))))
    (testing "impassable goal returns nil"
      (let [w' (update w :grid tile/set-tile 5 5 :wall)]
        (is (nil? (pathfinding/find-path w' [0 0] [5 5])))))))
