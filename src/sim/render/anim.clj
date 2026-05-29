(ns sim.render.anim
  "Real-time sprite animation for terrain (objects later).

   Animation is a RENDER LIE on real-time, never sim-time: water keeps rippling
   while the game is PAUSED, and the frame shown is a pure function of wall-clock
   millis. The sim never sees a frame number, so this can't perturb determinism —
   same-seed runs stay bit-identical (the float/time lives only in render, exactly
   like sim.render.interp/draw-pos for gliding pawns). This is the doc's real-time
   vs sim-time rule: anything that must animate while paused lives off the render
   thread, not the sim clock.

   The animated cells are on 32rogues/animated-tiles.png — ONE animation per row,
   laid out left-to-right (col 0..frames-1). See 32rogues/animated-tiles.txt.

   Pure core (frame/terrain-cell) is headless-tested; the GL draw that consumes it
   (sim.render.layers.terrain) stays untested, same split as the other layers."
  (:require
   [sim.render.sprites :as sprites]))

(set! *warn-on-reflection* true)

;; terrain-key -> {:sheet :row :frames :fps}. :fps is frames/sec in REAL time.
;; :water is animated-tiles row 11 (0-based 10) "water waves", 11 frames (cols
;; 0..10); the static sprites/terrain->cell entry is just frame 0 of this loop.
(def animated-terrain
  {:water {:sheet :animated :row 10 :frames 11 :fps 6}})

(defn frame
  "0-based frame index of a `frames`-long loop running at `fps` at wall-clock
   `now-ms`. Pure: same (now-ms, fps, frames) → same frame. now-ms is wall-clock
   (always non-negative), so integer `quot` floors as intended."
  [now-ms fps frames]
  (mod (quot (* (long now-ms) (long fps)) 1000) (long frames)))

(defn terrain-cell
  "[sheet col row] to draw for `terrain-key` at wall-clock `now-ms`. Animated
   terrains walk their row by time; everything else returns its static cell from
   sprites/terrain->cell (grass fallback if unmapped — degrade, don't crash)."
  [terrain-key now-ms]
  (if-let [{:keys [sheet row frames fps]} (animated-terrain terrain-key)]
    [sheet (frame now-ms fps frames) row]
    (sprites/terrain->cell terrain-key (sprites/terrain->cell :grass))))
