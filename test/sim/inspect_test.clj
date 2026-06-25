(ns sim.inspect-test
  "Pure inspect logic — no GL. Builds tiny worlds from sim.tile/sim.entity
   and asserts the concept-line strings and selectable filtering."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.tile    :as tile]
   [sim.inspect :as inspect]))

;; A 5x5 grass grid with a few terrain pokes; entities added per-test.
(defn- world-with
  "Build a world whose :grid is 5x5 grass with `terrain-pokes` ([x y key] ...)
   applied, and whose :entities is the given seq keyed by :id."
  [terrain-pokes entities]
  (let [grid (reduce (fn [g [x y k]] (tile/set-tile g x y k))
                     (tile/make-grid 5 5 :grass)
                     terrain-pokes)]
    {:grid grid
     :entities (into {} (map (juxt :id identity)) entities)}))

(deftest off-map-is-nil
  (testing "describe-tile returns nil outside the grid"
    (let [w (world-with [] [])]
      (is (nil? (inspect/describe-tile w [-1 0])))
      (is (nil? (inspect/describe-tile w [0 -1])))
      (is (nil? (inspect/describe-tile w [5 0])))
      (is (nil? (inspect/describe-tile w [0 5]))))))

(deftest bare-grass-tile
  (testing "a passable grass tile is one line, speed as a percentage"
    (is (= ["Grass 100%"] (inspect/describe-tile (world-with [] []) [2 2])))))

(deftest impassable-tile-omits-percent
  (testing "impassable terrain shows (impassable), no speed %"
    (let [w (world-with [[1 1 :stone]] [])]
      (is (= ["Stone (impassable)"] (inspect/describe-tile w [1 1]))))))

(deftest move-speed-percentages
  (testing "speed % = round(100 / move-cost) for passable terrain"
    (let [w (world-with [[0 0 :dirt] [1 0 :gravel] [2 0 :water]] [])]
      (is (= ["Dirt 87%"]   (inspect/describe-tile w [0 0])))
      (is (= ["Gravel 77%"] (inspect/describe-tile w [1 0])))
      (is (= ["Water 40%"]  (inspect/describe-tile w [2 0]))))))

(deftest entity-lines-sorted-by-id
  (testing "terrain line first, then one label per selectable entity, by :id"
    (let [tree {:id 7 :kind :tree :pos [3 3]}
          pawn {:id 9 :kind :pawn :name "Dave" :pos [3 3]}
          item {:id 4 :kind :item :material :wood :pos [3 3]}
          w    (world-with [] [tree pawn item])]
      (is (= ["Grass 100%" "Wood" "Tree" "Dave"]
             (inspect/describe-tile w [3 3]))))))

(deftest selectable-at-filters-and-sorts
  (testing "only selectable kinds, at the tile, sorted by id; carried items excluded"
    (let [tree    {:id 2 :kind :tree :pos [1 1]}
          pawn    {:id 1 :kind :pawn :name "A" :pos [1 1]}
          carried {:id 3 :kind :item :material :wood :pos nil :carried-by 1}
          elsewhere {:id 4 :kind :tree :pos [2 2]}
          w (world-with [] [tree pawn carried elsewhere])]
      (is (= [1 2] (map :id (inspect/selectable-at w [1 1]))))
      (is (= []    (inspect/selectable-at w [0 0])) "bare tile -> empty"))))

(deftest building-is-selectable-from-any-footprint-cell
  (testing "a multi-cell building is selectable from every cell it covers, and
            labelled by its def"
    (let [wall {:id 5 :kind :building :def :wall :pos [1 1] :size [3 1]}
          w    (world-with [] [wall])]
      (is (= [5] (map :id (inspect/selectable-at w [1 1]))) "origin cell")
      (is (= [5] (map :id (inspect/selectable-at w [3 1]))) "far footprint cell")
      (is (= []  (inspect/selectable-at w [4 1])) "just past the footprint")
      (is (= ["Grass 100%" "Wall"] (inspect/describe-tile w [2 1]))
          "shows as a labelled line on a covered tile"))))

(deftest blueprint-building-labels-with-its-state
  (testing "a blueprint reads as '<Type> (blueprint)' plus a progress sub-line; a built one is one plain line"
    (let [ghost {:id 7 :kind :building :def :wall :state :blueprint :pos [2 2]}
          built {:id 8 :kind :building :def :wall :state :built     :pos [3 2]}
          w     (world-with [] [ghost built])]
      (is (= ["Grass 100%" "Wall (blueprint)" "  stone 0/5, built 0%"]
             (inspect/describe-tile w [2 2])))
      (is (= ["Grass 100%" "Wall"] (inspect/describe-tile w [3 2]))))))

(deftest blueprint-progress-line-reflects-delivery-and-work
  (testing "the sub-line tracks delivered material and construction percent"
    (let [bp {:id 9 :kind :building :def :wall :state :blueprint :pos [1 1]
              :delivered {:stone 3} :work-done 60}                 ; 3/5 stone, 60/120 work
          w  (world-with [] [bp])]
      (is (= ["Grass 100%" "Wall (blueprint)" "  stone 3/5, built 50%"]
             (inspect/describe-tile w [1 1]))))))

(deftest long-labels-truncate-with-ellipsis
  (testing "a label past max-line-len is cut to exactly max-line-len ending in ..."
    (let [pawn {:id 1 :kind :pawn :name (apply str (repeat 50 \X)) :pos [0 0]}
          w    (world-with [] [pawn])
          line (second (inspect/describe-tile w [0 0]))]
      (is (= inspect/max-line-len (count line)))
      (is (clojure.string/ends-with? line "...")))))
