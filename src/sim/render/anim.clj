(ns sim.render.anim
  "Animated terrain cell selection. Animation is a render lie on real-time: the
   frame is a pure function of wall-clock millis (sim.render.graphic/frame), so it
   never perturbs determinism and water ripples even while paused. terrain-cell is
   consumed by the (untested) terrain layer until the graphic pipeline subsumes it
   (Task 6/7)."
  (:require
   [sim.render.sprites :as sprites]
   [sim.render.graphic :as graphic]))

(set! *warn-on-reflection* true)

(def animated-terrain
  {:water {:sheet :animated :row 10 :frames 11 :fps 6}})

(defn terrain-cell
  "[sheet col row] to draw for `terrain-key` at wall-clock `now-ms`. Animated
   terrains walk their row by time; everything else returns its static cell from
   sprites/terrain->cell (grass fallback if unmapped, degrade not crash)."
  [terrain-key now-ms]
  (if-let [{:keys [sheet row frames fps]} (animated-terrain terrain-key)]
    [sheet (graphic/frame now-ms fps frames) row]
    (sprites/terrain->cell terrain-key (sprites/terrain->cell :grass))))
