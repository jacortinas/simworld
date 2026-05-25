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
  (atom {:camera   {:x 400.0 :y 200.0 :zoom 0.8}
         :selected nil
         :hover    nil
         :debug?   false}))

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
