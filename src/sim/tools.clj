(ns sim.tools
  "Interaction tools (RimWorld's Designators): each placement MODE is a self-
   contained value, so adding a verb (mine, chop, build a floor) is a new entry
   here, not edits scattered across sim.input's five event handlers.

   A tool is a map keyed by its ui-state :mode. The :drag? flag picks the gesture:
     :drag? false  a CLICK gesture: left-click fires (:on-click tool) [tx ty shift?]
     :drag? true   a RECTANGLE gesture: left-DRAG paints a preview rect, and
                   (:on-commit tool) [start current erase?] fires on release.
   Either way the tool's handlers call sim.command verbs (the world-mutating
   bridge). sim.input owns the raw libGDX events plus the key->mode bindings, and
   dispatches the left-press / drag / commit / cancel through this registry.

   :select (the default selection + order mode) is intentionally NOT a tool: it is
   the base behavior sim.input falls through to when no tool owns the current mode.
   Cursor/preview rendering still lives in the render layers (build-cursor, zones),
   which read ui-state directly; folding the cursor into the tool spec is a natural
   later extension."
  (:require
   [sim.command :as command]))

(set! *warn-on-reflection* true)

(def by-mode
  "ui-state :mode -> tool spec. Add a placement verb by adding ONE entry here
   plus a key->mode binding in sim.input/tool-keys."
  {:build
   {:drag?    false
    :on-click (fn [tx ty shift?]
                (if shift?
                  (command/deconstruct-building! tx ty)
                  (command/build-wall! tx ty)))}

   :build-door
   {:drag?    false
    :on-click (fn [tx ty shift?]
                (if shift?
                  (command/deconstruct-building! tx ty)
                  (command/build-door! tx ty)))}

   :zone-stockpile
   {:drag?     true
    :on-commit (fn [start current erase?]
                 (if erase?
                   (command/erase-stockpile! start current)
                   (command/commit-stockpile! start current)))}})

(defn tool
  "The active tool spec for `mode`, or nil when `mode` is the default :select
   (no placement tool owns it)."
  [mode]
  (get by-mode mode))
