(ns sim.defs-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs     :as defs]))

;; Reload the bundled defs before AND after each test: before so the shared
;; global registry is full regardless of order; after so a test that swapped in
;; alternate sources (load-sources!) can't leave it partial for a sibling ns
;; whose entities construct via sim.entity/make-thing (which fails fast on a
;; missing def).
(use-fixtures :each (fn [t] (defs/load!) (t) (defs/load!)))

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
  (testing "loading bundled EDN returns a registry with the four categories"
    (let [db (defs/load!)]
      (is (map? db))
      (is (contains? db :terrain))
      (is (contains? db :material))
      (is (contains? db :need))
      (is (contains? db :thing))
      (is (contains? db :graphic)))))

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

(deftest every-terrain-has-a-valid-color
  (testing "each terrain def carries a [r g b] base color of 3 doubles in [0,1]"
    (doseq [k (defs/ids :terrain)]
      (let [c (:color (defs/terrain k))]
        (is (vector? c) (str k " :color should be a vector"))
        (is (= 3 (count c)) (str k " :color should have 3 components"))
        (is (every? #(<= 0.0 (double %) 1.0) c) (str k " :color components in [0,1]"))))))

(deftest terrain-color-returns-base-and-fallback
  (testing "terrain-color returns the def's color"
    (is (= (:color (defs/terrain :grass)) (defs/terrain-color :grass))))
  (testing "an unknown terrain falls back to grass's color (terrain falls back to grass)"
    (is (= (defs/terrain-color :grass) (defs/terrain-color :no-such-terrain)))))

(deftest load-rejects-out-of-range-color
  (testing "a terrain :color component above 1.0 fails validation"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)color"
         (defs/load-sources! {:terrain [{:grass {:move-cost 1.0 :passable? true
                                                 :color [2.0 0.0 0.0]}}]})))))

(deftest thing-lookup-returns-construction-template
  (testing "a known thing-def resolves to its template"
    (let [c (defs/thing :colonist)]
      (is (= :pawn (:kind c)))
      (is (= :never (:ticker-type c)))
      (is (= 15 (:move-ticks c)))
      (is (= {:food 1.0 :rest 1.0 :recreation 1.0} (:needs c)))))
  (testing "an item thing-def references its material orthogonally"
    (is (= :item (:kind (defs/thing :wood))))
    (is (= :wood (:material (defs/thing :wood)))))
  (testing "an unknown thing-def is nil (callers fail-fast at construction)"
    (is (nil? (defs/thing :no-such-thing)))))

(deftest thing-defs-load-and-validate
  (testing "the bundled registry includes the :thing category"
    (is (contains? (defs/load!) :thing)))
  (testing "ids enumerates every shipped thing type"
    (is (= #{:colonist :tree :wood :food :stone} (defs/ids :thing)))))

(deftest load-rejects-malformed-thing-entry
  (testing "an unknown :ticker-type throws (the band set is closed)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)ticker"
         (defs/load-sources! {:thing [{:colonist {:kind :pawn :ticker-type :sometimes}}]}))))
  (testing "a missing required :kind throws"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)kind"
         (defs/load-sources! {:thing [{:bad {:ticker-type :never}}]})))))

(deftest graphic-lookup-returns-entry
  (testing "an image-source graphic resolves to its entry"
    (is (= "graphics/colonist.png" (:image (defs/graphic :colonist))))
    (is (true? (:directional (defs/graphic :colonist)))))
  (testing "the animated water graphic carries a cell source and anim data"
    (is (= [:animated 0 10] (:cell (defs/graphic :water))))
    (is (= {:frames 11 :fps 6} (:anim (defs/graphic :water)))))
  (testing "an unknown graphic is nil (the render path degrades, never throws)"
    (is (nil? (defs/graphic :no-such-graphic)))))

(deftest graphics-load-and-validate
  (testing "the bundled registry includes the :graphic category"
    (is (contains? (defs/load!) :graphic)))
  (testing "every shipped graphic id is present"
    (is (= #{:grass :dirt :gravel :stone :wall :water
             :colonist :tree :wood :rock :apple}
           (defs/ids :graphic)))))

(deftest load-rejects-malformed-graphic-entry
  (testing "a graphic with both :cell and :image fails (exactly one source)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)graphic"
         (defs/load-sources! {:graphic [{:bad {:cell [:tiles 0 0] :image "x.png"}}]}))))
  (testing "a graphic with neither source fails"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)graphic"
         (defs/load-sources! {:graphic [{:bad {:draw-size [1 1]}}]}))))
  (testing "a non-positive :draw-size fails"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"(?i)graphic"
         (defs/load-sources! {:graphic [{:bad {:image "x.png" :draw-size [0 1]}}]})))))
