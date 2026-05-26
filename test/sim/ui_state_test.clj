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

(deftest hover-round-trips
  (testing "set-hover! stores a [tx ty]; hover reads it; nil clears"
    (is (nil? (ui/hover)) "absent key reads nil")
    (ui/set-hover! [3 7])
    (is (= [3 7] (ui/hover)))
    (ui/set-hover! nil)
    (is (nil? (ui/hover)))))

(deftest mode-defaults-to-select
  (testing "absent :mode reads :select (reload/reset-safe)"
    (is (= :select (ui/mode)))))

(deftest set-mode-round-trips
  (ui/set-mode! :zone-stockpile)
  (is (= :zone-stockpile (ui/mode)))
  (ui/set-mode! :select)
  (is (= :select (ui/mode))))

(deftest drag-round-trips-and-clears
  (testing "set-drag! stores the in-progress rect; clear-drag! resets to nil"
    (is (nil? (ui/drag)) "absent key reads nil")
    (ui/set-drag! {:start [0 0] :current [2 3]})
    (is (= {:start [0 0] :current [2 3]} (ui/drag)))
    (ui/clear-drag!)
    (is (nil? (ui/drag)))))
