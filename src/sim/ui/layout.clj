(ns sim.ui.layout
  "Screen-anchor convention: the reusable information hierarchy for HUD widgets.

   Two axes decide where a widget lives, so placement is a RULE, not a per-widget
   guess:
     VERTICAL   top    = global / sim-wide info & controls (always relevant)
                bottom = contextual info (driven by selection or the active tool)
     HORIZONTAL left   = passive readout (you READ it)
                right  = interactive controls (you CLICK it to ACT)

   The four standard slots that fall out of the axes:
     :top-right     time / system controls (pause + speed)   [global   + act]
     :top-left      global alerts & state (date, colony)      [global   + read]
     :bottom-left   selection / inspection detail             [context  + read]
     :bottom-right  tool / build menus (designators)          [context  + act]

   `anchor` is PURE (it takes the viewport dims explicitly) so it is headless-
   testable; a widget reads the live Gdx dims and passes them in. Coordinates
   are UI-cam space: origin bottom-left, Y-up, the same space SpriteBatch draws
   in under the UI camera. `pad` is the standard margin every anchored widget
   leaves from the screen edge.")

(set! *warn-on-reflection* true)

(def ^:const pad 8)

(defn anchor
  "Bottom-left [x y] (UI-cam, Y-up) for a `w` x `h` box placed at `corner` of a
   `vw` x `vh` viewport, inset by `pad` from the edges. `corner` is one of
   :top-left :top-right :bottom-left :bottom-right."
  [corner vw vh w h]
  (let [vw (double vw) vh (double vh) w (double w) h (double h) p (double pad)
        left   p
        right  (- vw p w)
        bottom p
        top    (- vh p h)]
    (case corner
      :top-left     [left  top]
      :top-right    [right top]
      :bottom-left  [left  bottom]
      :bottom-right [right bottom])))
