(ns sim.anim-test
  "Tests for animated terrain cell selection, sim.render.anim/terrain-cell. The
   pure frame math now lives in sim.render.graphic (see graphic-test); the GL that
   consumes terrain-cell is untested headless."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.anim :as anim]
   [sim.render.graphic :as graphic]
   [sim.render.sprites :as sprites]))

(deftest terrain-cell-animates-water-only
  (testing "water walks animated-tiles row 10 by time; col tracks the frame"
    (let [{:keys [sheet row fps frames]} (anim/animated-terrain :water)]
      (is (= [sheet (graphic/frame 1000 fps frames) row]
             (anim/terrain-cell :water 1000)))
      (is (not= (anim/terrain-cell :water 0)
                (anim/terrain-cell :water 200)))))
  (testing "non-animated terrain returns its static sprites cell, time-invariant"
    (is (= (sprites/terrain->cell :grass) (anim/terrain-cell :grass 0)))
    (is (= (anim/terrain-cell :grass 0) (anim/terrain-cell :grass 999999))))
  (testing "unmapped terrain degrades to grass"
    (is (= (sprites/terrain->cell :grass)
           (anim/terrain-cell :no-such-terrain 0)))))
