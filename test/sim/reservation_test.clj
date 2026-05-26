(ns sim.reservation-test
  "Reservations are a pure DERIVED query over the pawns' active jobs — nothing
   is stored, so these tests just build pawns with jobs and assert who claims
   what. Release is a non-event (a cleared job's claim vanishes), so there's no
   reserve/release lifecycle to test."
  (:require
   [clojure.test    :refer [deftest is testing]]
   [sim.world       :as world]
   [sim.entity      :as entity]
   [sim.job         :as job]
   [sim.reservation :as rsv]))

(defn- with-pawn
  "Add a pawn carrying `jobv` (or no job) to world `w`; return [w' pawn-id]."
  ([w] (with-pawn w nil))
  ([w jobv]
   (let [p (entity/make-pawn "p" [0 0])
         w (entity/add-entity w p)
         w (if jobv (entity/update-entity w (:id p) assoc :job jobv) w)]
     [w (:id p)])))

;; ---------------------------------------------------------------------------
;; reserved-targets — what a job claims
;; ---------------------------------------------------------------------------

(deftest reserved-targets-by-job-type
  (testing "a haul job claims its item id"
    (is (= [7] (rsv/reserved-targets (job/haul 7 [3 3])))))
  (testing "an eat job claims its food item id"
    (is (= [9] (rsv/reserved-targets (job/eat 9)))))
  (testing "a go-to job claims nothing (cell targets are out of scope)"
    (is (nil? (seq (rsv/reserved-targets (job/go-to [3 3])))))))

;; ---------------------------------------------------------------------------
;; claimant — who holds an active claim on a target
;; ---------------------------------------------------------------------------

(deftest claimant-nil-when-unclaimed
  (let [[w _] (with-pawn (world/initial-world {}))]
    (is (nil? (rsv/claimant w 7)) "no pawn hauling item 7 -> no claimant")))

(deftest claimant-is-the-hauling-pawn
  (let [[w pid] (with-pawn (world/initial-world {}) (job/haul 7 [3 3]))]
    (is (= pid (rsv/claimant w 7)))))

(deftest done-job-frees-the-claim
  (testing "a complete/failed job no longer claims its target"
    (let [[w _] (with-pawn (world/initial-world {})
                           (assoc (job/haul 7 [3 3]) :state :complete))]
      (is (nil? (rsv/claimant w 7))))))

(deftest claimant-resolves-ties-to-lowest-id
  (testing "two pawns hauling the same item -> the lowest id wins (deterministic,
            since vals order is unspecified)"
    (let [w0      (world/initial-world {})
          [w1 a]  (with-pawn w0 (job/haul 7 [3 3]))
          [w2 b]  (with-pawn w1 (job/haul 7 [3 3]))]
      (is (= (min a b) (rsv/claimant w2 7))))))

;; ---------------------------------------------------------------------------
;; can-reserve? — the predicate consumers use
;; ---------------------------------------------------------------------------

(deftest can-reserve-unclaimed-true-for-anyone
  (let [[w pid] (with-pawn (world/initial-world {}))]
    (is (true? (rsv/can-reserve? w 7 pid)) "unclaimed target is reservable")))

(deftest can-reserve-true-for-self-false-for-other
  (let [w0      (world/initial-world {})
        [w1 a]  (with-pawn w0 (job/haul 7 [3 3]))
        [w2 b]  (with-pawn w1)]                       ; idle pawn
    (is (true?  (rsv/can-reserve? w2 7 a)) "the claimant may re-reserve its own target")
    (is (false? (rsv/can-reserve? w2 7 b)) "another pawn may not reserve a claimed target")))
