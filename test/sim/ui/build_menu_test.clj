(ns sim.ui.build-menu-test
  "Pure-core tests for the bottom-left build menu: the button geometry and the
   hit-test. The GL draw + the ui-state dispatch in click! are the untested edge
   (same split as time-controls)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.ui.build-menu :as bm]))

(deftest category-row-has-one-button-per-category
  (let [row (bm/category-row-rects)]
    (is (= 3 (count row)))
    (is (= [:structure :zone :orders] (map :id row)))
    (is (every? #(= :category (:kind %)) row))
    (testing "buttons march left to right, all on the bottom row (same y)"
      (let [xs (map (comp first :rect) row)
            ys (map (comp second :rect) row)]
        (is (apply < xs) "x strictly increases")
        (is (apply = ys) "shared bottom y")))))

(deftest item-column-shows-the-open-category-only
  (is (= [] (bm/item-column-rects nil)) "collapsed -> no items")
  (let [items (bm/item-column-rects :structure)]
    (is (= [:wall :door] (map :id items)))
    (is (= [:build :build-door] (map :mode items)) "each item carries the mode it arms")
    (testing "items stack upward above the category row"
      (let [ys (map (comp second :rect) items)]
        (is (apply < ys) "y strictly increases upward")
        (is (every? #(> % 22) ys) "all above the row height")))))

(deftest hit-finds-categories-and-open-items
  (let [vh 600
        click-center (fn [{:keys [rect]}]
                       (let [[x y w h] rect] [(+ x (/ w 2.0)) (- vh (+ y (/ h 2.0)))]))]
    (testing "a click on a category button hits that category"
      (let [[sx sy] (click-center (first (bm/category-row-rects)))]
        (is (= :structure (:id (bm/hit nil vh sx sy))))))
    (testing "with :structure open, a click on Wall hits the item with its mode"
      (let [[sx sy] (click-center (first (bm/item-column-rects :structure)))
            spec    (bm/hit :structure vh sx sy)]
        (is (= :item (:kind spec)))
        (is (= :wall (:id spec)))
        (is (= :build (:mode spec)))))
    (testing "an item is NOT hittable unless its category is open"
      (let [[sx sy] (click-center (first (bm/item-column-rects :structure)))]
        (is (nil? (bm/hit nil vh sx sy)) "column not shown -> no hit there")))
    (testing "a click in empty space misses"
      (is (nil? (bm/hit :structure vh 5000 5000))))))
