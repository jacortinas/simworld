(ns sim.think-test
  "The think-tree is walked purely: (deliberate world pawn) -> job-or-nil. These
   tests drive the real default tree over controlled worlds, so they exercise the
   walker mechanics (priority order, conditional gating) AND the leaf givers
   (eat picks nearest reservable food; wander yields a go-to) through behavior."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world    :as world]
   [sim.entity   :as entity]
   [sim.job      :as job]
   [sim.think    :as think]))

(defn- world+pawn
  "12x12 world with a pawn having `needs` at `pos`. Returns [world pawn-id]."
  [needs pos]
  (let [w (world/initial-world {:width 12 :height 12})
        p (assoc (entity/make-pawn "p" pos) :needs needs)]
    [(entity/add-entity w p) (:id p)]))

(deftest hungry-pawn-eats-nearby-food
  (testing "priority + conditional + eat giver: a hungry pawn near food gets an
            :eat job for it"
    (let [[w0 pid] (world+pawn {:food 0.1} [0 0])
          food     (entity/make-item :food [3 0])
          w        (entity/add-entity w0 food)
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :eat (:type j)))
      (is (= (:id food) (:item-id j))))))

(deftest fed-pawn-falls-through-to-wander
  (testing "not hungry -> conditional fails -> priority falls to the wander leaf"
    (let [[w0 pid] (world+pawn {:food 1.0} [5 5])
          w        (entity/add-entity w0 (entity/make-item :food [3 0]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :go-to (:type j)) "fed pawn wanders even with food present"))))

(deftest hungry-but-no-food-wanders
  (testing "hungry with no reachable food -> eat giver nil -> wander"
    (let [[w pid] (world+pawn {:food 0.1} [5 5])
          j       (think/deliberate w (entity/entity w pid))]
      (is (= :go-to (:type j))))))

(deftest eat-giver-picks-nearest-food
  (let [[w0 pid] (world+pawn {:food 0.1} [0 0])
        far      (entity/make-item :food [9 0])
        near     (entity/make-item :food [2 0])
        w        (-> w0 (entity/add-entity far) (entity/add-entity near))
        j        (think/deliberate w (entity/entity w pid))]
    (is (= (:id near) (:item-id j)) "targets the nearer food by Manhattan distance")))

(deftest eat-giver-skips-reserved-food
  (testing "food already claimed by another pawn is not offered -> wander"
    (let [[w0 a]  (world+pawn {:food 0.1} [0 0])
          food    (entity/make-item :food [2 0])
          other   (entity/make-pawn "other" [2 0])
          w       (-> w0
                      (entity/add-entity food)
                      (entity/add-entity other)
                      (entity/update-entity (:id other) assoc :job (job/eat (:id food))))
          j       (think/deliberate w (entity/entity w a))]
      (is (= :go-to (:type j)) "the only food is reserved, so a wanders"))))

(deftest wander-yields-passable-goto
  (let [[w pid] (world+pawn {:food 1.0} [5 5])
        j       (think/deliberate w (entity/entity w pid))]
    (is (= :go-to (:type j)))
    (is (some? (:target j)) "wander targets a cell")))
