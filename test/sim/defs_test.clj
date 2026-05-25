(ns sim.defs-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs     :as defs]))

;; Reload the bundled defs before each test so the shared global registry is
;; always in a known full state regardless of test order (some tests load
;; alternate sources).
(use-fixtures :each (fn [t] (defs/load!) (t)))

(deftest terrain-lookup-returns-entry
  (testing "a known terrain resolves to its def"
    (let [g (defs/terrain :grass)]
      (is (= 1.0 (:move-cost g)))
      (is (true? (:passable? g)))))
  (testing "impassable terrain reports passable? false"
    (is (false? (:passable? (defs/terrain :wall))))))

(deftest terrain-unknown-falls-back-to-grass
  (testing "an unmapped terrain key returns the grass entry (matches old terrain-info)"
    (is (= (defs/terrain :grass) (defs/terrain :no-such-terrain)))))

(deftest material-lookup-returns-entry
  (is (= 0.7 (:weight (defs/material :wood))))
  (is (= \s (:char (defs/material :stone)))))

(deftest need-decay-returns-rate
  (testing "a known need returns its decay rate"
    (is (= 0.0125 (defs/need-decay :food))))
  (testing "an unknown need falls back to the default rate"
    (is (= 0.0125 (defs/need-decay :no-such-need)))))

(deftest shipped-defs-load-and-validate
  (testing "loading bundled EDN returns a registry with the three categories"
    (let [db (defs/load!)]
      (is (map? db))
      (is (contains? db :terrain))
      (is (contains? db :material))
      (is (contains? db :need)))))

(deftest load-rejects-malformed-entry
  (testing "a terrain entry with a non-boolean :passable? throws a useful message"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)passable"
         (defs/load-sources! {:terrain [{:grass {:move-cost 1.0 :passable? "yes"}}]}))))
  (testing "a need entry with an out-of-range :decay throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)decay"
         (defs/load-sources! {:need [{:food {:decay 9.0}}]})))))

(deftest ids-returns-all-keys-in-a-category
  (testing "ids enumerates the def keys registered under a category"
    (is (= #{:grass :dirt :gravel :stone :water :wall} (defs/ids :terrain)))
    (is (= #{:stone :wood :food} (defs/ids :material))))
  (testing "an unknown category is the empty set"
    (is (= #{} (defs/ids :no-such-category)))))

(deftest load-sources-merges-later-wins
  (testing "a later source overrides an earlier one for the same key (the mod seam)"
    (defs/load-sources! {:material [{:wood {:weight 0.7}}
                                    {:wood {:weight 9.9}}]})
    (is (= 9.9 (:weight (defs/material :wood))))))
