(ns sim.entity-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.entity :as entity]
   [sim.schedule :as schedule]))

(deftest make-tree-shape
  (testing "a tree is a :tree kind entity at a position"
    (let [t (entity/make-tree [4 5])]
      (is (= :tree (:kind t)))
      (is (= [4 5] (:pos t)))
      (is (some? (:id t))))))

(deftest trees-query-filters-by-kind
  (testing "trees returns only :tree entities"
    (let [w (-> {:entities {}}
                (entity/add-entity (entity/make-pawn "P" [0 0]))
                (entity/add-entity (entity/make-tree [1 1]))
                (entity/add-entity (entity/make-tree [2 2])))]
      (is (= 2 (count (entity/trees w))))
      (is (every? #(= :tree (:kind %)) (entity/trees w))))))

(deftest ticker-type-defaults
  (is (= :never (:ticker-type (entity/make-pawn "P" [0 0]))))
  (is (= :long  (:ticker-type (entity/make-item :stone [0 0]))))
  (is (= :never (:ticker-type (entity/make-tree [0 0])))))

(deftest add-entity-maintains-schedule-index
  (testing "adding a :long item registers it in its long home bucket"
    (let [item (entity/make-item :wood [3 3])
          w    (-> {:entities {} :schedule (schedule/empty-index)}
                   (entity/add-entity item))]
      (is (contains? (get-in w [:schedule :long (schedule/home-bucket (:id item) 1000)])
                     (:id item)))))
  (testing "removing it clears the bucket"
    (let [item (entity/make-item :wood [3 3])
          w    (-> {:entities {} :schedule (schedule/empty-index)}
                   (entity/add-entity item))
          w'   (entity/remove-entity w (:id item))]
      (is (empty? (get-in w' [:schedule :long (schedule/home-bucket (:id item) 1000)]))))))
