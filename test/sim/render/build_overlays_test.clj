(ns sim.render.build-overlays-test
  (:require
   [clojure.test :refer [deftest is]]
   [sim.render.layers.build-cursor     :as cursor]
   [sim.render.layers.debug-regions    :as dbg]
   [sim.render.layers.buildings        :as _buildings]
   [sim.render.layers.debug-pathgrid   :as _dpg]))

(deftest cursor-rect-is-one-tile-at-the-flip
  ;; tile [tx ty] on an h-tall grid -> bottom-left pixel rect, Y-flipped.
  (is (= [64.0 32.0 32.0 32.0]
         (cursor/cell-rect 2 1 3 32))))   ; x=2,y=1, height=3, tile-size=32

(deftest region-color-is-deterministic-and-bounded
  (let [c (dbg/region->color 5)]
    (is (= c (dbg/region->color 5)) "stable per id")
    (is (= 4 (count c)) "[r g b a]")
    (is (every? #(<= 0.0 (double %) 1.0) c))))
