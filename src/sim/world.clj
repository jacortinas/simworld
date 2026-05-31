(ns sim.world
  "The world atom plus initial-state constructor.

   Per the architecture notes: ONE atom, defonce'd so it survives REPL reloads.
   Sub-aspects (grid, entities, events, clock) are keys inside the same map so
   the renderer always sees a consistent snapshot via `@world`.

   When/if contention shows up, partition by *aspect* — never by entity."
  (:require
   [sim.entity   :as entity]
   [sim.log      :as log]
   [sim.schedule :as schedule]
   [sim.tile     :as tile]
   [sim.worldgen :as worldgen]))

(set! *warn-on-reflection* true)

(defn initial-world
  "Build a fresh world map. Pure — does not touch the atom.

   The shape:
     {:clock     0          ; tick counter
      :grid      {...}      ; tiles + width/height (see sim.tile)
      :entities  {id ent}   ; entities by id
      :schedule  {...}      ; derived tick-band bucket index (see sim.schedule)
      :zones     []         ; stockpile zones (see sim.zone)
      :events    []         ; pending events (consumed by sim each tick)
      :rng-seed  12345}"
  [{:keys [width height seed] :or {width 40 height 20 seed 12345}}]
  (let [grid (tile/make-grid width height :grass)]
    {:clock        0
     :grid         grid
     :entities     {}
     :schedule     (schedule/empty-index)
     :zones        []          ; stockpile zones (see sim.zone); plain saved state
     :next-zone-id 1           ; monotonic zone-id counter
     :events       []
     :log          log/empty-log
     :rng-seed     seed}))

;; ---------------------------------------------------------------------------
;; The atom. `defonce` is critical — it survives ns reloads, so we can
;; recompile sim.simulation while the game is running.
;; ---------------------------------------------------------------------------

(defonce world (atom (initial-world {})))

;; ---------------------------------------------------------------------------
;; Convenience mutators. Most code should swap! the world via sim.simulation;
;; these are for setup and REPL interactivity.
;; ---------------------------------------------------------------------------

(defn reset-world!
  "Reset the world atom. With {:generate? true}, run procedural generation
   (sim.worldgen/generate) over the fresh world; otherwise leave it
   empty-grass. opts also accepts :width :height :seed and worldgen tuning."
  ([] (reset-world! {}))
  ([opts]
   (let [w (initial-world opts)]
     (reset! world (if (:generate? opts)
                     (worldgen/generate w opts)
                     w)))))

(defn spawn-pawn!
  "Drop a new pawn into the live world at [x y]."
  ([pos] (spawn-pawn! (str "pawn-" (rand-int 1000)) pos))
  ([name pos]
   (let [p (entity/make-pawn name pos)]
     (swap! world entity/add-entity p)
     p)))

(defn snapshot
  "Return the current world value. Equivalent to @world; named for clarity in
   the REPL."
  []
  @world)
