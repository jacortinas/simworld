(ns sim.worldgen-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world :as world]
   [sim.tile :as tile]
   [sim.entity :as entity]
   [sim.worldgen :as wg]))

(def ^:private test-opts {:seed 7 :width 48 :height 48})

(defn- gen [opts]
  (wg/generate (world/initial-world {}) opts))

(deftest base-pass-fills-valid-terrain
  (testing "every tile is a known passable-ground/water keyword (no stone in Plan 1)"
    (let [w     (gen (assoc test-opts :passes [wg/base-pass]))
          tiles (set (:tiles (:grid w)))]
      (is (= 48 (:width (:grid w))))
      (is (= 48 (:height (:grid w))))
      (is (every? #{:water :gravel :dirt :grass} tiles)
          (str "unexpected terrain: " tiles)))))

(deftest base-pass-produces-variety
  (testing "a reasonably sized map yields several distinct terrain types
            (guards against a classifier that collapses to one band)"
    (let [w     (gen (assoc test-opts :passes [wg/base-pass]))
          tiles (set (:tiles (:grid w)))]
      (is (>= (count tiles) 3)
          (str "expected >=3 terrain types, got " tiles)))))

(deftest terrain-is-deterministic
  (testing "same seed -> identical grid"
    (let [a (gen (assoc test-opts :passes [wg/base-pass]))
          b (gen (assoc test-opts :passes [wg/base-pass]))]
      (is (= (:grid a) (:grid b))))))

(deftest scatter-places-trees-only-on-grass
  (testing "every tree sits on a :grass tile"
    (let [w    (gen test-opts)
          grid (:grid w)]
      (is (pos? (count (entity/trees w))) "at least one tree placed")
      (doseq [t (entity/trees w)]
        (let [[x y] (:pos t)]
          (is (= :grass (tile/tile-at grid x y))
              (str "tree off-grass at " [x y])))))))

(deftest scatter-places-valid-items
  (testing "items are ground items with known materials"
    (let [w     (gen test-opts)
          items (entity/items w)]
      (is (pos? (count items)) "at least one item placed")
      (doseq [i items]
        (is (= :item (:kind i)))
        (is (some? (:pos i)))
        (is (#{:wood :food :stone} (:material i)))))))

(deftest tree-spacing-respected
  (testing "no two trees are within the tree-spacing (Chebyshev) distance"
    (let [w     (gen test-opts)
          spots (map :pos (entity/trees w))]
      (doseq [[ax ay] spots [bx by] spots
              :when (not= [ax ay] [bx by])]
        (is (>= (max (Math/abs (- ax bx)) (Math/abs (- ay by))) 2)
            (str "trees too close: " [ax ay] [bx by]))))))

(deftest full-generation-is-deterministic
  (testing "same seed -> identical grid AND identical entity layout (modulo :id)"
    (let [strip (fn [w] (->> (entity/all-entities w)
                             (map #(dissoc % :id))
                             set))
          a (gen test-opts)
          b (gen test-opts)]
      (is (= (:grid a) (:grid b)))
      (is (= (strip a) (strip b))))))

(deftest reset-world-generate-opt
  (testing "reset-world! {:generate? true} produces a varied, populated world"
    ;; This test mutates the shared world/world atom. Capture and restore the
    ;; exact prior value so it can't bleed into other tests regardless of run
    ;; order.
    (let [before @world/world]
      (try
        (world/reset-world! {:generate? true :seed 7 :width 48 :height 48})
        (let [w @world/world]
          (is (> (count (set (:tiles (:grid w)))) 1) "more than one terrain type")
          (is (pos? (count (entity/trees w))))
          (is (pos? (count (entity/items w)))))
        (finally
          (reset! world/world before))))))

(deftest on-phase-callback-fires-in-order
  (testing ":on-phase callback invoked once per pipeline phase, in pipeline order"
    (let [seen (atom [])
          _    (gen (assoc test-opts :on-phase #(swap! seen conj %)))]
      (is (= [:terrain :detail] @seen)
          (str "expected [:terrain :detail], got " @seen)))))

(deftest on-phase-callback-is-optional
  (testing "generate without :on-phase still works (existing call sites unaffected)"
    (let [w (gen test-opts)]
      (is (some? (:grid w))))))
