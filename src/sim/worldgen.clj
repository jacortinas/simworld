(ns sim.worldgen
  "Procedural map generation -- the GAME layer. Composes a pipeline of pure
   `state -> state` passes over the gridnoise core. Two phases:
     - terrain phase: passes that write ONLY the cell grid (here: base-pass)
     - detail phase:  a 'fat pass' (scatter-pass) that reads finished terrain
                      and scatters entities (trees + haulable items)

   state = {:world <world> :seed <long> :opts <map> :reachable <set-or-nil>}

   Determinism: the terrain phase seeds gridnoise via the master seed; the
   detail phase derives its own java.util.Random from seed + offset, so passes
   are order-insensitive (see the spec's Determinism section)."
  (:require
   [gridnoise.noise :as noise]
   [gridnoise.grid  :as grid]
   [sim.tile        :as tile]
   [sim.entity      :as entity]))

(set! *warn-on-reflection* true)

(def default-opts
  "Tuning knobs. Override any via the opts arg to `generate`."
  {:width 80 :height 60 :seed 12345
   :freq 0.10 :octaves 4 :persistence 0.5
   ;; terrain thresholds (Plan 1 has no :stone -- rock arrives in a later plan)
   :water-level 0.32 :moist-low 0.42 :moist-high 0.70
   ;; detail-phase densities -- scaled for the 80x60 default so the bigger map
   ;; doesn't feel barren (tree placement is still capped by spaced grass)
   :tree-count 60 :tree-spacing 2 :wood-count 20 :food-count 24 :stone-count 20})

;; --- terrain phase -------------------------------------------------------

(defn classify
  "Map an elevation + moisture sample to a terrain keyword. Plan 1 emits only
   passable ground + water, so the map is trivially connected (no connectivity
   guard needed yet); :stone is introduced later alongside the guard."
  [^double elev ^double moist {:keys [water-level moist-low moist-high]}]
  (cond
    (< elev water-level) :water
    (< moist moist-low)  :gravel
    (< moist moist-high) :dirt
    :else                :grass))

(defn build-terrain-grid
  "Pure: produce the game grid {:width :height :tiles [...]} from noise.
   Uses gridnoise to make the shape, then renames :cells -> :tiles for the
   game world (the only naming bridge between core and game)."
  [seed {:keys [width height freq octaves persistence] :as opts}]
  (let [elev  (noise/field {:seed (+ (long seed) 1) :freq freq
                            :octaves octaves :persistence persistence})
        moist (noise/field {:seed (+ (long seed) 2) :freq freq
                            :octaves octaves :persistence persistence})
        g     (grid/generate width height
                             (fn [x y] (classify (elev x y) (moist x y) opts)))]
    {:width width :height height :tiles (:cells g)}))

(defn base-pass
  "Terrain phase: write the cell grid from noise. Reads/writes only :world's
   :grid, nothing entity-related. Invokes (:on-phase opts) with :terrain
   before starting if present — the optional progress hook the screens layer
   uses for phase-by-phase reporting."
  [state]
  (when-let [cb (:on-phase (:opts state))] (cb :terrain))
  (assoc-in state [:world :grid]
            (build-terrain-grid (:seed state) (:opts state))))

;; --- detail phase (the "fat pass" -- composed of lean helpers) -----------

(defn- chebyshev ^long [[ax ay] [bx by]]
  (max (Math/abs (long (- ax bx))) (Math/abs (long (- ay by)))))

(defn- shuffle-with
  "Deterministic Fisher-Yates using a seeded java.util.Random."
  [^java.util.Random rng coll]
  (let [arr (object-array coll)]
    (loop [i (dec (alength arr))]
      (if (pos? i)
        (let [j   (.nextInt rng (inc i))
              tmp (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp)
          (recur (dec i)))
        (vec arr)))))

(defn- tiles-of
  "All [x y] coords whose terrain == kind, row-major (deterministic order)."
  [grid kind]
  (vec (for [y (range (:height grid)) x (range (:width grid))
             :when (= kind (tile/tile-at grid x y))]
         [x y])))

(defn- rejection-sample
  "Greedily pick up to n coords from candidates such that each accepted coord
   is >= min-dist (Chebyshev) from every other accepted coord. Deterministic
   given rng."
  [rng candidates n min-dist]
  (reduce (fn [acc c]
            (if (>= (count acc) n)
              acc
              (if (every? #(>= (chebyshev c %) min-dist) acc)
                (conj acc c)
                acc)))
          []
          (shuffle-with rng candidates)))

(defn scatter-pass
  "Detail phase: read the finished terrain and decorate it. Trees on grass
   (spaced), wood near trees, food on grass, stone on gravel. Derives its own
   Random so it's independent of any other pass's draws. Invokes
   (:on-phase opts) with :detail before starting if present."
  [state]
  (when-let [cb (:on-phase (:opts state))] (cb :detail))
  (let [world (:world state)
        grid  (:grid world)
        opts  (:opts state)
        rng   (java.util.Random. (+ (long (:seed state)) 1000))
        grass (tiles-of grid :grass)
        trees (rejection-sample rng grass (:tree-count opts) (:tree-spacing opts))
        ;; place trees
        world (reduce (fn [w pos] (entity/add-entity w (entity/make-tree pos)))
                      world trees)
        ;; wood on a passable neighbor of the first N trees
        world (reduce (fn [w [tx ty]]
                        (if-let [spot (->> (tile/neighbors-8 grid tx ty)
                                           (filter (fn [[nx ny]]
                                                     (tile/passable?
                                                      (tile/tile-at grid nx ny))))
                                           first)]
                          (entity/add-entity w (entity/make-item :wood spot))
                          w))
                      world (take (:wood-count opts) trees))
        ;; food on grass (reuse a fresh deterministic shuffle of grass)
        food  (take (:food-count opts) (shuffle-with rng grass))
        world (reduce (fn [w pos] (entity/add-entity w (entity/make-item :food pos)))
                      world food)
        ;; stone on gravel (a later plan retargets this to rock adjacency)
        gravel (tiles-of grid :gravel)
        stone  (take (:stone-count opts) (shuffle-with rng gravel))
        world  (reduce (fn [w pos] (entity/add-entity w (entity/make-item :stone pos)))
                       world stone)]
    (assoc state :world world)))

;; --- pipeline ------------------------------------------------------------

(defn init-state
  "Build the initial pipeline state. Seed resolution: opts :seed, else the
   world's :rng-seed, else the default."
  [world opts]
  (let [seed (or (:seed opts) (:rng-seed world) (:seed default-opts))
        opts (merge default-opts opts {:seed seed})]
    {:world world :seed (long seed) :opts opts :reachable nil}))

(def default-pipeline
  "Ordered vector of passes. Plan 1: terrain phase (base-pass) then detail
   phase (scatter-pass). The pipeline is DATA -- add/remove/reorder by editing
   this vector, or override per-call with the :passes opt."
  [base-pass scatter-pass])

(defn generate
  "Pure: (world opts) -> world'. Runs the pipeline (or opts :passes) by
   reducing each pass over the initial state."
  ([world] (generate world {}))
  ([world opts]
   (let [passes (:passes opts default-pipeline)]
     (:world (reduce (fn [s pass] (pass s)) (init-state world opts) passes)))))
