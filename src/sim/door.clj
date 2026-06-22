(ns sim.door
  "Timed doors: a door opens before a pawn can step through, RimWorld's
   plan-estimate vs execute-wait split (see docs/rimworld-engine-internals.md).

   The door's open amount lives ENTIRELY in mutable world state (`:open`, an
   integer 0 = closed .. `:open-ticks` = fully open), NEVER in the PathGrid. For
   PLANNING a door is always a passable portal cell (sim.pathgrid/sim.regions), so
   regions/rooms/reachability never thrash as a door opens and closes. The
   open-state is purely an EXECUTION gate: sim.movement won't step a pawn into a
   door cell until it is fully open, and until then the pawn stalls one cell away.

   Two halves, one-way dependency:
     - WRITE (here): `tick-doors-system`, a normal-band system, is the sole writer
       of `:open`. Each tick it opens every WANTED door by one and drifts every
       unwanted door closed by one. A door is wanted when a pawn occupies its cell
       (passing through) or is settled one step away with the door as its next
       path cell (waiting to enter). Deterministic and tick-counted: same-seed
       runs stay identical, and idle doors allocate nothing.
     - READ (sim.movement): `blocking?` gates a step. Movement only reads doors;
       this ns never imports movement, so the graph stays acyclic.

   Depends on sim.entity (queries) + sim.pathgrid (the O(1) portal? pre-check)."
  (:require
   [sim.entity   :as entity]
   [sim.pathgrid :as pathgrid]))

(set! *warn-on-reflection* true)

(defn doors
  "All door entities (buildings flagged :portal?), ascending by id."
  [world]
  (filter :portal? (entity/buildings world)))

(defn open?
  "Is this door fully open (a pawn may step through without waiting)?"
  [door]
  (>= (long (:open door 0)) (long (:open-ticks door 0))))

(defn open-fraction
  "How far open this door is, in [0.0, 1.0]: :open / :open-ticks (0.0 closed,
   1.0 fully open). The render reads this to draw the door retracting; the sim
   gate (blocking?/open?) only cares about fully open, but the view wants the
   in-between."
  ^double [door]
  (let [mx (long (:open-ticks door 0))]
    (if (pos? mx)
      (min 1.0 (/ (double (:open door 0)) (double mx)))
      0.0)))

(defn orientation
  "Which way a door at `cell` faces, read from its flanking walls (a cell is
   wall-like when impassable: a building blocker or impassable terrain, NOT
   another door, which is passable):
     :horizontal  walls to the E and W -> the door sits in a horizontal wall and
                  is drawn face-on (you see the door);
     :vertical    walls to the N and S -> a vertical wall, drawn edge-on as a
                  thin strip.
   Defaults to :horizontal when ambiguous (free-standing, or a corner with walls
   on adjacent rather than opposite sides). Pure read of the world's PathGrid."
  [world [x y]]
  (let [pg    (pathgrid/for-world world)
        wall? (fn [cx cy] (not (pathgrid/passable? pg cx cy)))]
    (cond
      (and (wall? (dec x) y) (wall? (inc x) y)) :horizontal
      (and (wall? x (dec y)) (wall? x (inc y))) :vertical
      :else                                     :horizontal)))

;; ---------------------------------------------------------------------------
;; WRITE half: the tick system that animates every door toward open/closed.
;; ---------------------------------------------------------------------------

(defn- next-open
  "The door's open amount next tick: +1 toward `mx` when wanted, else -1 toward 0.
   A symmetric swing, so opening and closing both take `:open-ticks` ticks."
  ^long [^long open ^long mx wanted?]
  (if wanted?
    (min mx (inc open))
    (max 0 (dec open))))

(defn- next-cell
  "The cell a SETTLED walking pawn is about to enter (its path cell after the
   current one), or nil. A pawn mid-glide (:move in flight) has no pending next
   cell here, it is already committed to its current segment."
  [pawn]
  (let [{:keys [path path-index move]} (:job pawn)]
    (when (and path (nil? move))
      (let [ni (inc (long (or path-index 0)))]
        (when (< ni (count path)) (nth path ni))))))

(defn- wanted-cells
  "Set of door cells that a pawn currently wants open: any pawn standing ON a door
   cell (passing through, so it must not close on them) plus any settled pawn whose
   NEXT path cell is a door (waiting to enter). One O(pawns) pass; `door-cells` is
   the precomputed set of door positions."
  [world door-cells]
  (reduce (fn [acc pawn]
            (let [acc (if (door-cells (:pos pawn)) (conj acc (:pos pawn)) acc)
                  nc  (next-cell pawn)]
              (if (and nc (door-cells nc)) (conj acc nc) acc)))
          #{}
          (entity/pawns world)))

(defn tick-doors-system
  "Normal-band system: advance every door toward open (if wanted this tick) or
   closed (if not) by one tick. The SOLE writer of door :open. No-op when there
   are no doors; a door whose :open is unchanged is left untouched (idle doors
   allocate nothing)."
  [world _due]
  (let [ds (doors world)]
    (if (empty? ds)
      world
      (let [door-cells (into #{} (map :pos) ds)
            wanted     (wanted-cells world door-cells)]
        (reduce (fn [w door]
                  (let [open  (long (:open door 0))
                        open' (next-open open (long (:open-ticks door 0))
                                         (contains? wanted (:pos door)))]
                    (if (== open open')
                      w
                      (entity/update-entity w (:id door) assoc :open open'))))
                world
                ds)))))

;; ---------------------------------------------------------------------------
;; READ half: the movement gate. Called by sim.movement before a step.
;; ---------------------------------------------------------------------------

(defn- door-at
  "The door entity whose footprint covers cell [x y], or nil. Footprint-aware so
   a multi-cell door is found from any of its cells."
  [world cell]
  (first (filter #(entity/building-covers? % cell) (doors world))))

(defn blocking?
  "Is a NOT-yet-open door standing in cell [x y]? A pawn must wait rather than
   step in. The O(1) pathgrid/portal? pre-check means non-door cells (the common
   case) never pay the door-entity lookup."
  [world [x y]]
  (and (pathgrid/portal? (pathgrid/for-world world) x y)
       (when-let [d (door-at world [x y])]
         (not (open? d)))))
