(ns sim.command-test
  "Cycle-select logic in sim.command/left-click!. Drives the real world +
   ui-state atoms (the two atoms command bridges) and restores them after."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.world    :as world]
   [sim.ui-state :as ui]
   [sim.tile     :as tile]
   [sim.entity   :as entity]
   [sim.command  :as command]))

;; Snapshot and restore both globals so tests don't leak into the live atoms.
(use-fixtures :each
  (fn [run]
    (let [w @world/world
          u @ui/ui-state]
      (try (run)
           (finally
             (reset! world/world w)
             (reset! ui/ui-state u))))))

(defn- setup!
  "Reset the world to a 5x5 grass grid holding `entities` (by id) and clear
   the selection."
  [entities]
  (reset! world/world
          {:clock 0
           :grid (tile/make-grid 5 5 :grass)
           :entities (into {} (map (juxt :id identity)) entities)
           :events [] :log []})
  (ui/select! nil))

(deftest cycle-advances-and-wraps
  (testing "repeated clicks on one tile advance through entities, by id, wrapping"
    (let [tree {:id 1 :kind :tree :pos [3 3]}
          pawn {:id 2 :kind :pawn :name "Dave" :pos [3 3]}]
      (setup! [tree pawn])
      (command/left-click! 3 3)
      (is (= 1 (ui/selected)) "first click -> lowest id")
      (command/left-click! 3 3)
      (is (= 2 (ui/selected)) "second click -> next id")
      (command/left-click! 3 3)
      (is (= 1 (ui/selected)) "third click wraps to the first"))))

(deftest empty-tile-clears
  (testing "clicking a tile with no selectable entities clears the selection"
    (let [pawn {:id 2 :kind :pawn :name "Dave" :pos [3 3]}]
      (setup! [pawn])
      (command/left-click! 3 3)
      (is (= 2 (ui/selected)))
      (command/left-click! 0 0)
      (is (nil? (ui/selected)) "empty tile -> nil"))))

(deftest different-tile-selects-its-first
  (testing "clicking a different populated tile selects that tile's first entity"
    (let [a {:id 5 :kind :tree :pos [1 1]}
          b {:id 9 :kind :tree :pos [2 2]}]
      (setup! [a b])
      (command/left-click! 1 1)
      (is (= 5 (ui/selected)))
      (command/left-click! 2 2)
      (is (= 9 (ui/selected)) "new tile starts at its first, not a cycle"))))

(deftest single-entity-tile-stays-selected
  (testing "repeated clicks on a one-entity tile re-select it (mod 1 = idempotent)"
    (let [pawn {:id 3 :kind :pawn :name "Solo" :pos [4 4]}]
      (setup! [pawn])
      (command/left-click! 4 4)
      (is (= 3 (ui/selected)) "first click selects it")
      (command/left-click! 4 4)
      (is (= 3 (ui/selected)) "second click re-selects the same id (wrap of a 1-elem list)"))))

(deftest right-click-orders-a-selected-pawn
  (testing "right-clicking a passable tile with a pawn selected assigns a go-to job"
    (let [pawn {:id 2 :kind :pawn :name "Dave" :pos [3 3]
                :job nil :carrying nil :path nil}]
      (setup! [pawn])
      (command/left-click! 3 3)                 ; select the pawn
      (is (= 2 (ui/selected)))
      (command/right-click! 4 4)                ; order a move to a passable tile
      (is (= :go-to (:type (:job (get-in @world/world [:entities 2]))))
          "pawn receives a go-to job"))))

(deftest right-click-ignores-a-selected-non-pawn
  (testing "right-clicking with a tree selected does NOT stamp a job on the tree"
    (let [tree {:id 1 :kind :tree :pos [3 3]}]
      (setup! [tree])
      (command/left-click! 3 3)                 ; select the tree
      (is (= 1 (ui/selected)))
      (command/right-click! 4 4)                ; attempt an order
      (is (nil? (:job (get-in @world/world [:entities 1])))
          "tree must not receive a job")
      (is (empty? (filter #(= :job/assigned (:type %)) (:log @world/world)))
          "and no false :job/assigned log entry is written"))))

(deftest can-build-wall-allows-open-passable-cells
  (let [w {:grid (tile/make-grid 3 3) :entities {} :kinds (entity/empty-kinds)}]
    (is (command/can-build-wall? w [1 1]))))

(deftest can-build-wall-rejects-impassable-and-oob
  (let [w {:grid (tile/set-tile (tile/make-grid 3 3) 1 1 :wall)
           :entities {} :kinds (entity/empty-kinds)}]
    (is (not (command/can-build-wall? w [1 1])) "impassable terrain")
    (is (not (command/can-build-wall? w [5 5])) "out of bounds")))

(deftest can-build-wall-rejects-occupied-and-built
  (let [w (-> {:grid (tile/make-grid 3 3) :entities {} :kinds (entity/empty-kinds)}
              (entity/add-entity (entity/make-pawn "p" [0 0]))
              (entity/add-entity (entity/make-building [2 2])))]
    (is (not (command/can-build-wall? w [0 0])) "pawn occupies the cell")
    (is (not (command/can-build-wall? w [2 2])) "already a wall")))

(deftest build-and-deconstruct-wall-mutate-the-world
  (world/reset-world!)
  (command/build-wall! 3 3)
  (is (= 1 (count (entity/buildings @world/world))) "wall placed")
  (command/deconstruct-wall! 3 3)
  (is (zero? (count (entity/buildings @world/world))) "wall removed"))
