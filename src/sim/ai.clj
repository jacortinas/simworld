(ns sim.ai
  "Pawn decision-making.

   `decide` is the per-pawn AI step. Pure: (world, pawn) -> world. The
   return type is a *world* (not a pawn) because some jobs mutate multiple
   entities — see sim.job/advance."
  (:require
   [sim.entity :as entity]
   [sim.job    :as job]
   [sim.tile   :as tile]))

(set! *warn-on-reflection* true)

(def ^:const ^:private move-period
  "Ticks between movement decisions. At 30 Hz sim, 15 = twice per second."
  15)

(defn- moves-this-tick?
  "Stagger pawns by id so they don't all twitch in lockstep."
  [world pawn]
  (zero? (mod (+ (long (:clock world)) (long (:id pawn))) move-period)))

(defn- random-step
  "Return an updated pawn moved to a random passable adjacent tile
   (including 'stay put'). Pure: (world, pawn) -> pawn."
  [world pawn]
  (let [[x y] (:pos pawn)
        choices (->> (conj (tile/neighbors-4 (:grid world) x y) [x y])
                     (filterv (fn [[nx ny]]
                                (tile/passable? (tile/tile-at (:grid world) nx ny)))))]
    (if (seq choices)
      (assoc pawn :pos (rand-nth choices))
      pawn)))

(defn decide
  "Choose what this pawn should do this tick. (world, pawn) -> world."
  [world pawn]
  (cond
    ;; Clean up a finished job — pawn is idle starting this tick.
    (and (:job pawn) (job/done? (:job pawn)))
    (entity/update-entity world (:id pawn) assoc :job nil)

    ;; Active job: compute path immediately, step on gated ticks.
    (:job pawn)
    (if (nil? (get-in pawn [:job :path]))
      (job/advance world pawn)
      (if (moves-this-tick? world pawn)
        (job/advance world pawn)
        world))

    ;; No job: idle behavior.
    (moves-this-tick? world pawn)
    (entity/update-entity world (:id pawn) #(random-step world %))

    :else world))
