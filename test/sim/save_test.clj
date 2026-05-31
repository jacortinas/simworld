(ns sim.save-test
  "sim.save freezes the world to nippy and thaws it back, restoring the two
   invariants a frozen save can't carry: the DERIVED :schedule index and the
   PROCESS-GLOBAL entity id counter. These tests round-trip a world through a
   temp save dir and assert both data fidelity and those restored invariants.

   Headless: no GL, no clock thread — just the pure world map through nippy."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.entity   :as entity]
   [sim.save     :as save]
   [sim.world    :as world]
   [sim.zone     :as zone]))

(defn- temp-dir
  "A unique scratch dir per save so concurrent/repeated runs never collide."
  []
  (str (System/getProperty "java.io.tmpdir") "/sim-save-test-" (System/nanoTime)))

(deftest round-trips-world-data
  (testing "every saved (non-derived) key thaws back unchanged"
    (let [w (-> (world/initial-world {:width 10 :height 10})
                (entity/add-entity (entity/make-pawn "Ada" [1 1]))
                (entity/add-entity (entity/make-item :wood [2 2]))
                (zone/add-stockpile [0 0] [2 0]))]
      (binding [save/*save-dir* (temp-dir)]
        (save/save! w "rt")
        (let [loaded (save/load! "rt")]
          (is (= (dissoc w :schedule) (dissoc loaded :schedule))
              "all non-derived state is byte-identical across the round-trip")
          (is (some? (:schedule loaded))
              "the derived schedule index is rebuilt on load, not read from disk"))))))

(deftest load-reseeds-id-counter
  (testing "a save loaded into a fresh process (counter at 0) can't reuse a loaded id"
    (let [w      (-> (world/initial-world {:width 10 :height 10})
                     (entity/add-entity (entity/make-pawn "Ada" [1 1]))
                     (entity/add-entity (entity/make-pawn "Bel" [2 2])))
          max-id (entity/max-entity-id w)]
      (binding [save/*save-dir* (temp-dir)]
        (save/save! w "ids")
        ;; Simulate a fresh JVM: drop the process-global counter below the saved
        ;; ids. (Reach the private atom through its var — test-only.)
        (reset! @#'entity/id-counter 0)
        (save/load! "ids")
        (is (>= @@#'entity/id-counter max-id)
            "load advanced the counter past the loaded max id")
        (is (> (:id (entity/make-pawn "Cyd" [3 3])) max-id)
            "the next spawned entity gets a fresh id — no collision with a loaded one")))))
