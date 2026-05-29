(ns sim.anim-test
  "Tests for the PURE real-time animation core — sim.render.anim.

   frame/terrain-cell are pure (wall-clock millis in, [sheet col row] out); the
   GL draw that consumes them (sim.render.layers.terrain) is untested headless,
   same split as the debug/selection layers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.render.anim :as anim]
   [sim.render.sprites :as sprites]))

(deftest frame-steps-and-loops-with-wall-clock
  (testing "frame floors (now-ms * fps / 1000) then wraps at `frames`"
    (let [fps 6 n 11]
      (is (= 0  (anim/frame 0    fps n)) "t=0 → frame 0")
      (is (= 0  (anim/frame 166  fps n)) "just before the 1/fps boundary")
      (is (= 1  (anim/frame 167  fps n)) "crossing 1000/6 ms → frame 1")
      (is (= 6  (anim/frame 1000 fps n)) "1s at 6fps → frame 6")
      (is (= 10 (anim/frame 1833 fps n)) "last frame of the loop")
      (is (= 0  (anim/frame 1834 fps n)) "wraps back to 0 after the 11th frame"))))

(deftest frame-is-always-in-range
  (testing "result is always a valid 0-based column for any wall-clock value"
    (doseq [now [0 1 999 123456 987654321]
            [fps n] [[6 11] [1 4] [12 6]]]
      (let [f (anim/frame now fps n)]
        (is (and (<= 0 f) (< f n))
            (str "frame " f " out of 0.." (dec n) " for now=" now))))))

(deftest terrain-cell-animates-water-only
  (testing "water walks animated-tiles row 10 by time; col tracks `frame`"
    (let [{:keys [sheet row fps frames]} (anim/animated-terrain :water)]
      (is (= [sheet (anim/frame 1000 fps frames) row]
             (anim/terrain-cell :water 1000))
          "water cell = [animated <frame> 10] at the same wall-clock")
      ;; two times a frame apart should differ; same frame should match
      (is (not= (anim/terrain-cell :water 0)
                (anim/terrain-cell :water 200))
          "advancing past a frame boundary changes the cell")))
  (testing "non-animated terrain returns its static sprites cell, time-invariant"
    (is (= (sprites/terrain->cell :grass) (anim/terrain-cell :grass 0)))
    (is (= (anim/terrain-cell :grass 0) (anim/terrain-cell :grass 999999))
        "grass never animates regardless of wall-clock"))
  (testing "unmapped terrain degrades to grass, never crashes"
    (is (= (sprites/terrain->cell :grass)
           (anim/terrain-cell :no-such-terrain 0)))))
