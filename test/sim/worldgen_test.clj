(ns sim.worldgen-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world :as world]
   [sim.tile :as tile]
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
