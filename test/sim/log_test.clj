(ns sim.log-test
  "The debug log is a bounded ring backed by a PersistentQueue: append is O(1)
   and, past the cap, drops the OLDEST entry (head of the queue). These tests pin
   the tick stamp and the FIFO eviction direction — the latter is precisely what
   quietly breaks if the ring is ever re-backed by a tail-popping vector."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.log :as log]))

(deftest append-stamps-tick-and-preserves-order
  (let [w (-> {:clock 7}
              (log/append {:type :a})
              (assoc :clock 8)
              (log/append {:type :b}))
        es (log/entries w)]
    (is (= [:a :b] (mapv :type es)) "oldest first")
    (is (= [7 8] (mapv :tick es)) "each entry stamped with the clock at append time")))

(deftest eviction-drops-oldest-keeps-newest
  (testing "past max-entries the ring keeps the LAST max-entries, dropping the oldest"
    (let [n  (+ log/max-entries 25)
          w  (reduce (fn [w i] (assoc (log/append w {:type :e :n i}) :clock (inc i)))
                     {:clock 0}
                     (range n))
          es (log/entries w)]
      (is (= log/max-entries (count es)) "bounded to the cap")
      (is (= 25 (:n (first es))) "the 25 oldest (n 0..24) were dropped")
      (is (= (dec n) (:n (last es))) "the newest is retained"))))

(deftest coerces-a-legacy-vector-log
  (testing "a hand-built world whose :log is a plain vector still evicts correctly"
    (let [w  (reduce (fn [w i] (log/append w {:n i}))
                     {:clock 0 :log [{:n :pre1} {:n :pre2}]}
                     (range (+ log/max-entries 5)))
          es (log/entries w)]
      (is (= log/max-entries (count es)))
      (is (not-any? #{:pre1 :pre2} (map :n es)) "the seeded vector entries aged out, not the new ones"))))

(deftest clear-empties-the-log
  (let [w (log/append {:clock 1} {:type :x})]
    (is (seq (log/entries w)))
    (is (empty? (log/entries (log/clear w))))))
