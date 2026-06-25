(ns sim.ui.work-tab-test
  "Pure-core tests for the Work tab: the grid geometry and the cell hit-test. The
   GL draw + the command dispatch in click! are the untested edge."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.ui.work-tab :as wt]
   [sim.think       :as think]))

(def ^:private pawns
  [{:id 1 :name "A" :priorities {:build 3 :haul 3}}
   {:id 2 :name "B" :priorities {:build 0 :haul 1}}])

(deftest panel-has-a-header-per-work-type-and-a-cell-per-pawn-x-type
  (let [{:keys [headers names cells]} (wt/panel-layout 600 pawns)]
    (is (= (map :label think/work-types) (map :label headers)) "one header per work type")
    (is (= 2 (count names)) "one name row per pawn")
    (is (= [1 2] (map :pawn-id names)) "rows in pawn order")
    (is (= (* 2 (count think/work-types)) (count cells)) "pawns x work-types cells")
    (testing "each cell carries its pawn, work type, and current priority"
      (let [c (first (filter #(and (= 1 (:pawn-id %)) (= :build (:wt-id %))) cells))]
        (is (= 3 (:priority c))))
      (let [c (first (filter #(and (= 2 (:pawn-id %)) (= :build (:wt-id %))) cells))]
        (is (= 0 (:priority c)) "pawn B has Build off")))))

(deftest hit-cell-maps-a-click-to-a-pawn-and-work-type
  (let [vh 600
        {:keys [cells]} (wt/panel-layout vh pawns)
        c (first cells)
        [x y w h] (:rect c)
        sx (+ x (/ w 2.0))
        sy (- vh (+ y (/ h 2.0)))]
    (is (= {:pawn-id (:pawn-id c) :wt-id (:wt-id c)} (wt/hit-cell vh pawns sx sy)))
    (is (nil? (wt/hit-cell vh pawns 5000 5000)) "a click in empty space misses")))
