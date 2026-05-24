(ns sim.ui-state-test
  "Tests for the view-state atom. The debug overlay is gated by a :debug?
   flag here — a view concern, never serialized — so its toggle is plain,
   pure state we can drive headlessly."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.ui-state :as ui]))

;; ui-state is a defonce global; reset it before each test so toggles don't
;; leak across tests (and so we exercise the 'key absent' starting state).
(use-fixtures :each
  (fn [run]
    (reset! ui/ui-state {:camera {:x 0.0 :y 0.0 :zoom 1.0} :selected nil})
    (run)))

(deftest debug-defaults-off
  (testing "debug? is falsey before the flag has ever been set"
    (is (not (ui/debug?)))))

(deftest toggle-debug-cycles
  (testing "toggle-debug! flips the flag and returns the NEW state"
    (is (true?  (ui/toggle-debug!)) "absent/false -> true, returns true")
    (is (true?  (ui/debug?))        "reads back true")
    (is (false? (ui/toggle-debug!)) "true -> false, returns false")
    (is (false? (ui/debug?))        "reads back false")))
