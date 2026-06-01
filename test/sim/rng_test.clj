(ns sim.rng-test
  "Pure PRNG (SplitMix64). The contract that matters for the sim is determinism,
   not statistical quality: same seed -> same stream (reproducibility), distinct
   coordinates -> distinct streams (so per-(tick,entity) derivation is order-
   independent), and a [value state'] shape so a pure tick can thread the state
   forward. Quality of the bit-mixing is a property of the algorithm, not of our
   wiring, so we don't test it here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.rng      :as rng]))

;; ---------------------------------------------------------------------------
;; next-long — advance + emit, threading state
;; ---------------------------------------------------------------------------

(deftest next-long-returns-value-and-advanced-state
  (let [[v s'] (rng/next-long 12345)]
    (is (integer? v)        "emits a value")
    (is (integer? s')       "emits a next state")
    (is (not= 12345 s')     "state advances")))

(deftest next-long-is-deterministic
  (testing "same input state -> identical [value next-state]"
    (is (= (rng/next-long 12345) (rng/next-long 12345)))))

(deftest next-long-successive-draws-differ
  (let [[v0 s1] (rng/next-long 12345)
        [v1 _]  (rng/next-long s1)]
    (is (not= v0 v1) "consecutive values from a stream differ")))

(deftest next-long-stream-is-reproducible
  (testing "N draws from the same seed reproduce exactly"
    (let [stream (fn [seed n]
                   (loop [s seed, acc []]
                     (if (= n (count acc))
                       acc
                       (let [[v s'] (rng/next-long s)] (recur s' (conj acc v))))))]
      (is (= (stream 777 50) (stream 777 50)))
      (is (not= (stream 777 50) (stream 778 50)) "different seed -> different stream"))))

;; ---------------------------------------------------------------------------
;; next-int — value in [0,n), threaded + reproducible
;; ---------------------------------------------------------------------------

(deftest next-int-in-range
  (testing "every draw lands in [0,n)"
    (loop [s 42, k 0]
      (when (< k 1000)
        (let [[v s'] (rng/next-int s 7)]
          (is (<= 0 v) "non-negative")
          (is (< v 7)  "below n")
          (recur s' (inc k)))))))

(deftest next-int-is-deterministic
  (is (= (rng/next-int 99 13) (rng/next-int 99 13))))

;; ---------------------------------------------------------------------------
;; next-double — value in [0,1), threaded + reproducible
;; ---------------------------------------------------------------------------

(deftest next-double-in-unit-interval
  (loop [s 5, k 0]
    (when (< k 1000)
      (let [[v s'] (rng/next-double s)]
        (is (<= 0.0 v) "non-negative")
        (is (< v 1.0)  "strictly below 1.0")
        (recur s' (inc k))))))

(deftest next-double-is-deterministic
  (is (= (rng/next-double 5) (rng/next-double 5))))

;; ---------------------------------------------------------------------------
;; pick — choose one element, threading state
;; ---------------------------------------------------------------------------

(deftest pick-empty-coll-yields-nil-and-passes-state-through
  (let [[v s'] (rng/pick 123 [])]
    (is (nil? v)     "nil element for empty coll")
    (is (= 123 s')   "state passes through unchanged when nothing to pick")))

(deftest pick-returns-a-member
  (let [coll [:a :b :c :d]
        [v _] (rng/pick 123 coll)]
    (is (some #{v} coll) "picked element is in the collection")))

(deftest pick-is-deterministic
  (is (= (rng/pick 555 [:a :b :c]) (rng/pick 555 [:a :b :c]))))

;; ---------------------------------------------------------------------------
;; shuffle — permutation, reproducible per seed
;; ---------------------------------------------------------------------------

(deftest shuffle-is-a-permutation
  (let [coll  [1 2 3 4 5 6 7 8]
        [v _] (rng/shuffle 31 coll)]
    (is (= (sort v) (sort coll)) "same multiset, reordered")
    (is (= (count v) (count coll)))))

(deftest shuffle-is-deterministic
  (is (= (rng/shuffle 31 [1 2 3 4 5]) (rng/shuffle 31 [1 2 3 4 5]))))

(deftest shuffle-differs-by-seed
  (is (not= (first (rng/shuffle 1 (range 12)))
            (first (rng/shuffle 2 (range 12))))
      "different seeds permute differently"))

;; ---------------------------------------------------------------------------
;; derive-seed — mix coordinates into an independent stream seed
;; ---------------------------------------------------------------------------

(deftest derive-seed-is-deterministic
  (is (= (rng/derive-seed 100 7 42) (rng/derive-seed 100 7 42))))

(deftest derive-seed-is-coordinate-sensitive
  (testing "distinct coordinates -> distinct seeds (the per-entity independence)"
    (is (not= (rng/derive-seed 100 7 42) (rng/derive-seed 100 7 43)) "id differs")
    (is (not= (rng/derive-seed 100 7 42) (rng/derive-seed 100 8 42)) "tick differs")
    (is (not= (rng/derive-seed 100 7 42) (rng/derive-seed 100 42 7)) "order matters")))

(deftest derive-seed-is-base-seed-sensitive
  (is (not= (rng/derive-seed 100 7 42) (rng/derive-seed 101 7 42))
      "different base seed -> different derived seed"))

(deftest derived-streams-are-independent
  (testing "two entities (ids 1,2) on the same tick draw different first values"
    (let [s1 (rng/derive-seed 12345 0 1)
          s2 (rng/derive-seed 12345 0 2)]
      (is (not= (first (rng/next-long s1)) (first (rng/next-long s2)))))))
