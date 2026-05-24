(ns sim.entity-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.entity :as entity]))

(deftest make-tree-shape
  (testing "a tree is a :tree-kind entity at a position"
    (let [t (entity/make-tree [4 5])]
      (is (= :tree (:kind t)))
      (is (= [4 5] (:pos t)))
      (is (= :tree (:species t)))
      (is (some? (:id t))))))

(deftest trees-query-filters-by-kind
  (testing "trees returns only :tree entities"
    (let [w (-> {:entities {}}
                (entity/add-entity (entity/make-pawn "P" [0 0]))
                (entity/add-entity (entity/make-tree [1 1]))
                (entity/add-entity (entity/make-tree [2 2])))]
      (is (= 2 (count (entity/trees w))))
      (is (every? #(= :tree (:kind %)) (entity/trees w))))))
