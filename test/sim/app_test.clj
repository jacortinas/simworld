(ns sim.app-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.app :as app]))

;; All tests in this namespace assert against the pure fn `next-app-state`
;; with `app/initial-app` (or values derived from it) — they NEVER read or
;; write the live `app/app` atom. Keep it that way: tests should be
;; thread-safe and order-independent.

(deftest initial-state
  (testing "fresh app value lands at main menu with idle worldgen and no pause snapshot"
    (is (= :main-menu (:screen app/initial-app)))
    (is (= :idle      (get-in app/initial-app [:worldgen :status])))
    (is (= false      (get-in app/initial-app [:pause-menu :was-paused?])))))

(deftest enter-worldgen-from-menu
  (testing "main-menu + :enter-worldgen → :worldgen with :status :running, phase :terrain"
    (let [s (app/next-app-state app/initial-app :enter-worldgen)]
      (is (= :worldgen (:screen s)))
      (is (= :running  (get-in s [:worldgen :status])))
      (is (= :terrain  (get-in s [:worldgen :phase]))))))

(deftest enter-worldgen-is-idempotent-while-running
  (testing "re-entry guard: :enter-worldgen on an already-running worldgen leaves state unchanged"
    (let [running (app/next-app-state app/initial-app :enter-worldgen)
          again   (app/next-app-state running :enter-worldgen)]
      (is (= running again)))))

(deftest phase-updates-do-not-change-screen
  (testing ":phase event only updates :worldgen :phase, never :screen"
    (let [s1 (app/next-app-state app/initial-app :enter-worldgen)
          s2 (app/next-app-state s1 :phase :detail)]
      (is (= :worldgen (:screen s2)))
      (is (= :detail   (get-in s2 [:worldgen :phase]))))))

(deftest worldgen-done-stores-result
  (testing ":worldgen-done writes :status :done and :result"
    (let [w  {:grid {:width 1 :height 1 :tiles [:grass]}}
          s  (-> app/initial-app
                 (app/next-app-state :enter-worldgen)
                 (app/next-app-state :worldgen-done w))]
      (is (= :done (get-in s [:worldgen :status])))
      (is (= w     (get-in s [:worldgen :result]))))))

(deftest worldgen-failed-stores-error
  (testing ":worldgen-failed writes :status :failed and :error"
    (let [t  (ex-info "boom" {})
          s  (-> app/initial-app
                 (app/next-app-state :enter-worldgen)
                 (app/next-app-state :worldgen-failed t))]
      (is (= :failed (get-in s [:worldgen :status])))
      (is (= t       (get-in s [:worldgen :error]))))))

(deftest enter-play-resets-worldgen-submap
  (testing ":enter-play moves to :play and clears the :worldgen sub-map back to idle"
    (let [w (:result (get-in (-> app/initial-app
                                 (app/next-app-state :enter-worldgen)
                                 (app/next-app-state :worldgen-done {:grid {}}))
                             [:worldgen]))
          s (-> app/initial-app
                (app/next-app-state :enter-worldgen)
                (app/next-app-state :worldgen-done w)
                (app/next-app-state :enter-play))]
      (is (= :play (:screen s)))
      (is (= :idle (get-in s [:worldgen :status]))))))

(deftest pause-menu-captures-was-paused-state
  (testing ":enter-pause-menu captures the was-paused? flag verbatim"
    (let [s-running (app/next-app-state app/initial-app :enter-pause-menu false)
          s-paused  (app/next-app-state app/initial-app :enter-pause-menu true)]
      (is (= :pause-menu (:screen s-running)))
      (is (= false (get-in s-running [:pause-menu :was-paused?])))
      (is (= :pause-menu (:screen s-paused)))
      (is (= true  (get-in s-paused  [:pause-menu :was-paused?]))))))

(deftest resume-clears-pause-submap
  (testing ":resume-from-pause-menu returns to :play and clears :pause-menu :was-paused?"
    (let [s (-> app/initial-app
                (app/next-app-state :enter-pause-menu true)
                (app/next-app-state :resume-from-pause-menu))]
      (is (= :play (:screen s)))
      (is (= false (get-in s [:pause-menu :was-paused?]))))))

(deftest quit-to-menu-fully-resets
  (testing ":quit-to-menu returns app to its initial value regardless of prior screen"
    (doseq [start [:worldgen :play :pause-menu]]
      (let [s  (-> app/initial-app
                   (assoc :screen start)
                   (app/next-app-state :quit-to-menu))]
        (is (= app/initial-app s)
            (str "quit-to-menu from " start " should equal initial-app"))))))

(deftest phase-on-idle-is-safe
  (testing ":phase event on the idle state updates :worldgen :phase without changing screen"
    (let [s (app/next-app-state app/initial-app :phase :terrain)]
      (is (= :main-menu (:screen s)))
      (is (= :terrain   (get-in s [:worldgen :phase]))))))

(deftest unknown-event-throws-ex-info
  (testing "next-app-state on an unknown event throws ex-info with :event and :args data"
    (try
      (app/next-app-state app/initial-app :nonexistent-event :extra-arg)
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Unknown app event" (.getMessage e)))
        (is (= :nonexistent-event (:event (ex-data e))))
        (is (= [:extra-arg]       (:args (ex-data e))))))))
