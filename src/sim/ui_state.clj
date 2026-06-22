(ns sim.ui-state
  "View state — deliberately separate from simulation state.

   The camera (where you're looking, how zoomed) is *how you view* the
   world, not part of the world itself. It lives here so:
     - save-game! serializes only `sim.world/world`, never your scroll pos
     - a headless server could run the sim with no ui-state at all
     - the renderer reads BOTH atoms; nothing in the sim reads this one

   `defonce` so it survives REPL reloads, same as the world atom.

   Camera is stored as plain data {:x :y :zoom} — the libGDX
   OrthographicCamera is a *derived* view, synced from this each frame.
   Source of truth stays in Clojure data you can inspect and poke from
   the REPL.")

(set! *warn-on-reflection* true)

(def ^:const min-zoom 0.25)
(def ^:const max-zoom 4.0)

(defonce ui-state
  ;; zoom < 1.0 zooms IN (OrthographicCamera semantics). 0.8 is a slightly
  ;; closer default than 1.0. gdx `create` recenters :x/:y on the map midpoint,
  ;; so those are just an initial placeholder.
  (atom {:camera          {:x 400.0 :y 200.0 :zoom 0.8}
         :selected        nil
         :hover           nil
         :debug?          false
         :debug-regions?  false
         :debug-pathgrid? false
         :mode            :select   ; :select | :zone-stockpile | :build | :build-door
         :drag            nil}))    ; in-progress placement rect {:start [tx ty] :current [tx ty]}

(defn camera [] (:camera @ui-state))

(defn selected
  "Currently selected entity id, or nil."
  []
  (:selected @ui-state))

(defn select!
  "Set the selection to `id` (or nil to clear)."
  [id]
  (swap! ui-state assoc :selected id))

(defn hover
  "Tile [tx ty] currently under the cursor, or nil. May be off-map: callers
   (sim.inspect/describe-tile) bounds-check. View state — never serialized."
  []
  (:hover @ui-state))

(defn set-hover!
  "Store the hovered tile [tx ty] (or nil). Called from sim.input/mouseMoved."
  [pos]
  (swap! ui-state assoc :hover pos))

(defn debug?
  "Is the debug overlay currently shown? Falsey by default — and because the
   atom survives `defonce` across reloads, a session that predates the key
   simply reads nil (off) until toggled."
  []
  (:debug? @ui-state))

(defn toggle-debug!
  "Flip the debug overlay on/off. Returns the NEW boolean so REPL/HUD callers
   can echo the state. `not` on a nil (absent key) yields true — reload-safe."
  []
  (:debug? (swap! ui-state update :debug? not)))

(defn debug-regions?
  "Is the regions debug overlay currently shown?"
  []
  (:debug-regions? @ui-state))

(defn toggle-debug-regions?
  "Flip the regions debug overlay on/off. Returns the NEW boolean."
  []
  (:debug-regions? (swap! ui-state update :debug-regions? not)))

(defn debug-pathgrid?
  "Is the pathgrid debug overlay currently shown?"
  []
  (:debug-pathgrid? @ui-state))

(defn toggle-debug-pathgrid?
  "Flip the pathgrid debug overlay on/off. Returns the NEW boolean."
  []
  (:debug-pathgrid? (swap! ui-state update :debug-pathgrid? not)))

;; ---------------------------------------------------------------------------
;; Interaction mode + in-progress placement drag. Both VIEW state — the mode is
;; how you're interacting (select vs. zoning), the drag is a live preview. Never
;; serialized. mode/drag default safely when absent (reload/reset-safe).
;; ---------------------------------------------------------------------------

(defn mode
  "Current interaction mode: :select (default), :zone-stockpile, :build, or :build-door."
  []
  (:mode @ui-state :select))

(defn set-mode!
  "Set the interaction mode; returns it."
  [m]
  (swap! ui-state assoc :mode m)
  m)

(defn drag
  "In-progress placement rectangle {:start [tx ty] :current [tx ty]}, or nil."
  []
  (:drag @ui-state))

(defn set-drag!
  "Store the in-progress placement rectangle (tile coords)."
  [d]
  (swap! ui-state assoc :drag d))

(defn clear-drag!
  "Drop the in-progress placement rectangle."
  []
  (swap! ui-state assoc :drag nil))

(defn pan!
  "Shift the camera center by [dx dy] world units."
  [dx dy]
  (swap! ui-state update :camera
         (fn [c]
           (-> c
               (update :x + (double dx))
               (update :y + (double dy))))))

(defn zoom-by!
  "Multiply zoom by `factor`, clamped to [min-zoom max-zoom]. Matches
   OrthographicCamera semantics: factor >1 zooms OUT (more world visible),
   <1 zooms IN."
  [factor]
  (swap! ui-state update-in [:camera :zoom]
         (fn [z]
           (-> (* (double z) (double factor))
               (max min-zoom)
               (min max-zoom)))))

(defn center-camera!
  "Point the camera at world coordinate [x y], leaving zoom unchanged."
  [x y]
  (swap! ui-state update :camera assoc :x (double x) :y (double y)))

(defn reset-camera!
  "Center on [x y] at zoom 1.0."
  [x y]
  (reset! ui-state {:camera {:x (double x) :y (double y) :zoom 1.0}}))
