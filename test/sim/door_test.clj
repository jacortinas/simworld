(ns sim.door-test
  "Timed doors. Unit tests pin the open/close swing and the movement gate; the
   integration test is the payoff: a pawn crossing a corridor with a door takes
   ~the door's :open-ticks longer than the same corridor with an open gap, the
   wait happening at movement time (the door is always passable for planning)."
  (:require
   [clojure.test   :refer [deftest is testing]]
   [sim.tile       :as tile]
   [sim.entity     :as entity]
   [sim.world      :as world]
   [sim.job        :as job]
   [sim.simulation :as simulation]
   [sim.door       :as door]))

(defn- bare-world []
  {:grid (tile/make-grid 5 3) :entities {} :kinds (entity/empty-kinds)})

(defn- tick-doors-n [world n]
  (reduce (fn [w _] (door/tick-doors-system w nil)) world (range n)))

(defn- door-id [world] (:id (first (door/doors world))))

;; ---------------------------------------------------------------------------
;; State + the open/close swing.
;; ---------------------------------------------------------------------------

(deftest a-door-starts-closed
  (let [d (entity/make-door [2 1])]
    (is (= 0 (:open d)) "make-door builds it closed")
    (is (not (door/open? d)) "and not passable without waiting")
    (is (= 20 (:open-ticks d)) "carrying the def's open time")))

(deftest door-system-opens-a-wanted-door-one-tick-at-a-time
  (testing "a pawn settled one cell away with the door as its next path cell
            (i.e. waiting to enter) makes the door swing open over :open-ticks"
    (let [pawn (-> (entity/make-pawn "p" [1 1])
                   (assoc :job {:path [[1 1] [2 1] [3 1]] :path-index 0 :move nil}))
          w    (-> (bare-world)
                   (entity/add-entity (entity/make-door [2 1]))
                   (entity/add-entity pawn))
          did  (door-id w)]
      (is (= 5 (:open (entity/entity (tick-doors-n w 5) did))) "opens one per tick")
      (is (not (door/open? (entity/entity (tick-doors-n w 5) did))) "partway at tick 5")
      (let [d20 (entity/entity (tick-doors-n w 20) did)]
        (is (= 20 (:open d20)) "reaches :open-ticks")
        (is (door/open? d20) "and is fully open"))
      (is (= 20 (:open (entity/entity (tick-doors-n w 30) did)))
          "then clamps at :open-ticks, never past"))))

(deftest door-system-drifts-an-unwanted-door-closed
  (testing "with no pawn wanting it, an open door closes one tick at a time"
    (let [w   (-> (bare-world)
                  (entity/add-entity (assoc (entity/make-door [2 1]) :open 5)))
          did (door-id w)]
      (is (= 3 (:open (entity/entity (tick-doors-n w 2) did))) "5 -> 3 after 2 ticks")
      (is (= 0 (:open (entity/entity (tick-doors-n w 5) did))) "fully closed after 5")
      (is (= 0 (:open (entity/entity (tick-doors-n w 9) did))) "clamped at 0"))))

(deftest door-system-is-a-noop-without-doors
  (let [w (-> (bare-world) (entity/add-entity (entity/make-pawn "p" [1 1])))]
    (is (identical? w (door/tick-doors-system w nil)) "no doors -> world untouched")))

;; ---------------------------------------------------------------------------
;; The movement gate.
;; ---------------------------------------------------------------------------

(deftest blocking?-is-true-only-for-a-not-yet-open-door
  (let [w   (-> (bare-world) (entity/add-entity (entity/make-door [2 1])))
        did (door-id w)]
    (is (door/blocking? w [2 1]) "a closed door blocks the step")
    (is (not (door/blocking? w [0 0])) "an empty cell never blocks")
    (let [w-open (entity/update-entity w did assoc :open 20)]
      (is (not (door/blocking? w-open [2 1])) "a fully open door does not block"))))

;; ---------------------------------------------------------------------------
;; Integration: the wait shows up end-to-end as extra crossing time.
;; ---------------------------------------------------------------------------

(defn- corridor-world
  "A 5x3 world walled top and bottom (a 1-wide corridor at y=1). Cell [2 1] is
   built with `mid-fn` ([pos] -> entity), or left open floor when nil. A pawn at
   [0 1]. Returns [world pawn-id]."
  [mid-fn]
  (let [w0   (world/initial-world {:width 5 :height 3})
        grid (reduce (fn [g x] (-> g (tile/set-tile x 0 :wall) (tile/set-tile x 2 :wall)))
                     (:grid w0) (range 5))
        base (assoc w0 :grid grid)
        w1   (if mid-fn (entity/add-entity base (mid-fn [2 1])) base)
        pawn (entity/make-pawn "p" [0 1])]
    [(entity/add-entity w1 pawn) (:id pawn)]))

(defn- ticks-to-reach [world pid target cap]
  (loop [w world, n 0]
    (cond
      (= target (:pos (entity/entity w pid))) n
      (>= n cap)                              nil
      :else (recur (simulation/tick w) (inc n)))))

(deftest pawn-waits-at-a-closed-door-then-crosses
  (testing "crossing a corridor with a door takes ~:open-ticks longer than with
            an open gap; the door never permanently blocks the route"
    (let [[dw dpid] (corridor-world entity/make-door)
          [ow opid] (corridor-world nil)
          dw (job/assign dw dpid (job/go-to [4 1]) job/forced-by-player)
          ow (job/assign ow opid (job/go-to [4 1]) job/forced-by-player)
          dt (ticks-to-reach dw dpid [4 1] 300)
          ot (ticks-to-reach ow opid [4 1] 300)]
      (is (some? dt) "the pawn crosses the door and reaches the far side")
      (is (some? ot) "the control pawn crosses the open gap")
      (is (<= 18 (- dt ot) 24)
          (str "the door added its ~20-tick open time (doored " dt " vs open " ot ")")))))

(deftest a-door-on-the-path-is-still-reachable-and-passable
  (testing "the door is passable for planning; only the wait is at move time"
    (let [[dw dpid] (corridor-world entity/make-door)
          dw (job/assign dw dpid (job/go-to [4 1]) job/forced-by-player)
          arrived (ticks-to-reach dw dpid [4 1] 300)]
      (is (some? arrived) "a path exists through the door and the pawn completes it"))))
