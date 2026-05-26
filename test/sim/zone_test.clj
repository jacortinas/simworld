(ns sim.zone-test
  "Stockpile zones are plain world state built from a drag rectangle. The model
   + rectangle geometry are pure, so these tests build worlds and assert on the
   resulting :zones without any input/rendering."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world :as world]
   [sim.tile  :as tile]
   [sim.save  :as save]
   [sim.zone  :as zone]))

(deftest cells-in-rect-inclusive-and-normalized
  (testing "inclusive rectangle, independent of drag direction"
    (is (= #{[0 0] [1 0] [0 1] [1 1]} (zone/cells-in-rect [0 0] [1 1])))
    (is (= (zone/cells-in-rect [0 0] [1 1]) (zone/cells-in-rect [1 1] [0 0]))
        "drag direction does not matter")
    (is (= #{[3 3]} (zone/cells-in-rect [3 3] [3 3])) "single cell")))

(deftest add-stockpile-creates-a-zone-of-passable-cells
  (let [w0 (world/initial-world {:width 10 :height 10})   ; all grass (passable)
        w  (zone/add-stockpile w0 [0 0] [2 0])]
    (is (= 1 (count (zone/zones w))))
    (is (= #{[0 0] [1 0] [2 0]} (:cells (first (zone/zones w)))))
    (is (= :stockpile (:kind (first (zone/zones w)))))
    (is (zone/cell-zoned? w [1 0]))
    (is (not (zone/cell-zoned? w [5 5])))))

(deftest add-stockpile-skips-impassable-cells
  (testing "impassable cells (walls) inside the rect are not zoned (note: water
            is passable in this sim, so :wall is the impassable terrain)"
    (let [w0 (-> (world/initial-world {:width 10 :height 10})
                 (update :grid tile/set-tile 1 0 :wall))
          w  (zone/add-stockpile w0 [0 0] [2 0])]
      (is (= #{[0 0] [2 0]} (:cells (first (zone/zones w)))) "the wall cell is skipped"))))

(deftest add-stockpile-skips-already-zoned-and-grows-ids
  (let [w0 (world/initial-world {:width 10 :height 10})
        w1 (zone/add-stockpile w0 [0 0] [1 0])            ; zone A: {[0 0] [1 0]}
        w2 (zone/add-stockpile w1 [1 0] [2 0])            ; rect overlaps [1 0]
        zs (zone/zones w2)]
    (is (= 2 (count zs)) "two distinct zones")
    (is (apply distinct? (map :id zs)) "distinct ids")
    (is (= #{[2 0]} (:cells (second zs))) "the already-zoned [1 0] is skipped")))

(deftest add-stockpile-empty-is-noop
  (testing "a rect with no zonable cells adds no zone"
    (let [w0 (-> (world/initial-world {:width 10 :height 10})
                 (update :grid tile/set-tile 0 0 :wall))
          w  (zone/add-stockpile w0 [0 0] [0 0])]
      (is (empty? (zone/zones w))))))

(deftest stockpile-cells-unions-all-zones
  (let [w (-> (world/initial-world {:width 10 :height 10})
              (zone/add-stockpile [0 0] [0 0])
              (zone/add-stockpile [5 5] [5 5]))]
    (is (= #{[0 0] [5 5]} (zone/stockpile-cells w)))))

(deftest remove-cells-shrinks-a-zone
  (testing "erasing part of a zone's rectangle removes just those cells"
    (let [w0 (-> (world/initial-world {:width 10 :height 10})
                 (zone/add-stockpile [0 0] [3 0]))      ; {[0 0][1 0][2 0][3 0]}
          w  (zone/remove-cells w0 [2 0] [3 0])]        ; erase [2 0],[3 0]
      (is (= #{[0 0] [1 0]} (:cells (first (zone/zones w)))))
      (is (not (zone/cell-zoned? w [2 0]))))))

(deftest remove-cells-drops-a-fully-covered-zone
  (testing "a zone whose every cell is erased is removed entirely"
    (let [w0 (-> (world/initial-world {:width 10 :height 10})
                 (zone/add-stockpile [0 0] [1 0]))
          w  (zone/remove-cells w0 [0 0] [2 2])]        ; covers the whole zone
      (is (empty? (zone/zones w))))))

(deftest remove-cells-leaves-other-cells-and-zones
  (testing "only cells inside the erase rect are removed; other zones untouched"
    (let [w0 (-> (world/initial-world {:width 10 :height 10})
                 (zone/add-stockpile [0 0] [2 0])       ; zone A
                 (zone/add-stockpile [5 5] [6 5]))      ; zone B
          w  (zone/remove-cells w0 [0 0] [0 0])]        ; erase only [0 0] from A
      (is (= #{[1 0] [2 0]} (:cells (first (zone/zones w)))) "A shrank")
      (is (= #{[5 5] [6 5]} (zone/stockpile-cells (assoc w :zones [(second (zone/zones w))])))
          "B is untouched")
      (is (= 2 (count (zone/zones w))) "both zones remain (A still non-empty)"))))

(deftest zones-survive-save-load
  (testing "zones are plain world state and round-trip through nippy"
    (let [w (-> (world/initial-world {:width 10 :height 10})
                (zone/add-stockpile [0 0] [2 0]))]
      (binding [save/*save-dir* (str (System/getProperty "java.io.tmpdir") "/sim-zone-test")]
        (save/save! w "zonetest")
        (is (= (zone/stockpile-cells w)
               (zone/stockpile-cells (save/load! "zonetest"))))))))
