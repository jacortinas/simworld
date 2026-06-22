(ns sim.rooms-test
  "Rooms: enclosed groups of regions, the flood stopping at door (portal) regions.
   The load-bearing property is the door/gap/wall TRIO: a wall opening filled with
   a DOOR encloses an indoor room; the SAME opening left as an open gap merges
   into the outdoor room (a gap does not enclose); filled with a WALL it is a
   sealed indoor room. That contrast is exactly 'a door encloses a room'."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.tile     :as tile]
   [sim.entity   :as entity]
   [sim.regions  :as regions]
   [sim.rooms    :as rooms]))

(defn- reset-caches! []
  (reset! @#'regions/cache nil)
  (reset! @#'rooms/cache nil))

;; The 16 perimeter cells of a 5x5 box sitting inside a 7x7 grid (so there is
;; real space OUTSIDE the box: the box never touches the grid edge).
(def ^:private box-ring
  (vec (for [x (range 1 6) y (range 1 6)
             :when (or (= x 1) (= x 5) (= y 1) (= y 5))]
         [x y])))

(defn- boxed-world
  "7x7 grass world walled into a 5x5 box. The ring cell `gap` is built with
   `gap-fn` ([pos] -> entity) instead of a wall, or left OPEN floor when gap-fn
   is nil. Interior = (2,2)..(4,4) = 9 cells; exterior = 24 passable cells."
  [gap gap-fn]
  (let [base  {:grid (tile/make-grid 7 7) :entities {} :kinds (entity/empty-kinds)}
        walls (remove #(= % gap) box-ring)
        w1    (reduce (fn [w p] (entity/add-entity w (entity/make-building p))) base walls)]
    (if gap-fn
      (entity/add-entity w1 (gap-fn gap))
      w1)))

;; ---------------------------------------------------------------------------
;; Open map: one outdoor room. Chunking must NOT over-segment it: a 24x24 open
;; grid is 4 region NODES but a single room (normal-normal edges reconnect the
;; chunk pieces; only portals cut rooms).
;; ---------------------------------------------------------------------------

(deftest open-map-is-one-outdoor-room
  (reset-caches!)
  (let [ri (rooms/of-index (regions/of-grid (tile/make-grid 10 10)))]
    (is (= 1 (rooms/count-rooms ri)) "open map -> exactly one room")
    (is (= 100 (:cell-count (rooms/room ri (rooms/room-at ri 5 5)))) "every cell counted")
    (is (:touches-edge? (rooms/room ri 0)) "the open room reaches the map edge")
    (is (not (rooms/enclosed? ri 5 5)) "so it is outdoors, not enclosed")))

(deftest open-multichunk-map-is-still-one-room
  (reset-caches!)
  (let [ri (rooms/of-index (regions/of-grid (tile/make-grid 24 24)))]
    (is (= 4 (regions/count-regions (:region-index ri))) "4 region nodes (2x2 chunks)")
    (is (= 1 (rooms/count-rooms ri)) "but a single room: chunk seams do not cut rooms")))

;; ---------------------------------------------------------------------------
;; THE TRIO: door encloses, gap does not, wall seals.
;; ---------------------------------------------------------------------------

(deftest a-door-in-the-box-encloses-an-indoor-room
  (reset-caches!)
  (let [world  (boxed-world [3 1] entity/make-door)
        ri     (rooms/for-world world)
        inside (rooms/room-at ri 3 3)
        out    (rooms/room-at ri 0 0)]
    (testing "the box interior is an enclosed room, the outside is outdoors"
      (is (= 2 (rooms/count-rooms ri)) "interior + exterior = two rooms")
      (is (not= inside out) "interior and exterior are different rooms")
      (is (rooms/enclosed? ri 3 3) "interior does not touch the edge -> enclosed")
      (is (not (rooms/enclosed? ri 0 0)) "exterior touches the edge -> outdoors"))
    (testing "the door cell itself belongs to no room (it is a portal)"
      (is (= -1 (rooms/room-at ri 3 1)) "door is not in a room"))
    (testing "room objects carry cell counts and the enclosure flag"
      (is (= 9  (:cell-count (rooms/room ri inside))) "interior is 3x3 = 9 cells")
      (is (= 24 (:cell-count (rooms/room ri out)))    "exterior is 24 passable cells")
      (is (true?  (:enclosed? (rooms/room ri inside))))
      (is (false? (:enclosed? (rooms/room ri out))))
      (is (true?  (:touches-edge? (rooms/room ri out)))))
    (testing "but the two rooms remain REACHABLE through the door"
      (is (regions/reachable? world [3 3] [0 0])
          "you can still walk from inside to outside through the door"))))

(deftest an-open-gap-does-not-enclose
  (testing "the same opening left as open floor merges interior into the outdoors"
    (reset-caches!)
    (let [world (boxed-world [3 1] nil)            ; [3 1] is open grass, not a door
          ri    (rooms/for-world world)]
      (is (= 1 (rooms/count-rooms ri))
          "interior and exterior are one room through the open gap")
      (is (not (rooms/enclosed? ri 3 3))
          "interior is part of the outdoor room: not enclosed")
      (is (regions/reachable? world [3 3] [0 0]) "and still reachable, of course"))))

(deftest a-wall-seals-the-box-into-an-unreachable-indoor-room
  (testing "filling the opening with a wall keeps the interior enclosed but cuts it off"
    (reset-caches!)
    (let [world (boxed-world [3 1] entity/make-building)   ; fully walled, no door
          ri    (rooms/for-world world)]
      (is (= 2 (rooms/count-rooms ri)) "interior + exterior, still two rooms")
      (is (rooms/enclosed? ri 3 3) "interior is a sealed enclosed room")
      (is (not (regions/reachable? world [3 3] [0 0]))
          "but with no door it is unreachable from outside"))))

;; ---------------------------------------------------------------------------
;; Memoization: the same region index returns the IDENTICAL rooms index (a hit),
;; and a door toggle that yields a new region index recomputes.
;; ---------------------------------------------------------------------------

(deftest rooms-index-is-memoized-by-region-index-identity
  (reset-caches!)
  (let [ridx (regions/of-grid (tile/make-grid 8 8))]
    (is (identical? (rooms/of-index ridx) (rooms/of-index ridx))
        "same region index -> cached rooms index (no recompute)")))

(deftest enclosure-toggles-as-a-door-becomes-a-wall
  (testing "swapping the door for a wall does not change enclosure, removing it does"
    (reset-caches!)
    (let [doored (rooms/for-world (boxed-world [3 1] entity/make-door))
          walled (rooms/for-world (boxed-world [3 1] entity/make-building))
          opened (rooms/for-world (boxed-world [3 1] nil))]
      (is (rooms/enclosed? doored 3 3) "door: enclosed")
      (is (rooms/enclosed? walled 3 3) "wall: still enclosed")
      (is (not (rooms/enclosed? opened 3 3)) "open gap: not enclosed"))))
