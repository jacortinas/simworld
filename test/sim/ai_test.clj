(ns sim.ai-test
  "redeliberate is the integration point: an idle pawn walks the think-tree and
   the resulting job is assigned through sim.job/assign (auto, so reservations
   apply). These tests drive that end to end."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world      :as world]
   [sim.entity     :as entity]
   [sim.job        :as job]
   [sim.simulation :as simulation]
   [sim.ai         :as ai]))

(deftest redeliberate-assigns-eat-to-hungry-idle-pawn
  (let [w0 (world/initial-world {:width 12 :height 12})
        p  (assoc (entity/make-pawn "p" [0 0]) :needs {:food 0.1})
        f  (entity/make-item :food [3 0])
        w  (-> w0 (entity/add-entity p) (entity/add-entity f))
        w' (ai/redeliberate w (entity/entity w (:id p)))
        j  (:job (entity/entity w' (:id p)))]
    (is (= :eat (:type j))    "a hungry idle pawn gets an eat job")
    (is (= (:id f) (:item-id j)))))

(deftest redeliberate-leaves-a-busy-pawn-alone
  (let [w0 (world/initial-world {:width 12 :height 12})
        p  (entity/make-pawn "p" [0 0])
        w  (-> w0 (entity/add-entity p)
               (entity/update-entity (:id p) assoc :job (job/go-to [5 5])))
        w' (ai/redeliberate w (entity/entity w (:id p)))]
    (is (= :go-to (:type (:job (entity/entity w' (:id p)))))
        "a pawn that already has a job is not re-deliberated")))

(deftest two-hungry-pawns-one-food-only-one-eats
  (testing "sequential deliberation + the reservation gate: only one pawn claims
            the single food item; the other falls through to wander"
    (let [w0 (world/initial-world {:width 12 :height 12})
          a  (assoc (entity/make-pawn "a" [0 0]) :needs {:food 0.1})
          b  (assoc (entity/make-pawn "b" [1 0]) :needs {:food 0.1})
          f  (entity/make-item :food [3 0])
          w  (-> w0 (entity/add-entity a) (entity/add-entity b) (entity/add-entity f))
          w1 (ai/redeliberate w  (entity/entity w  (:id a)))   ; a deliberates first
          w2 (ai/redeliberate w1 (entity/entity w1 (:id b)))   ; then b, seeing a's claim
          ja (:job (entity/entity w2 (:id a)))
          jb (:job (entity/entity w2 (:id b)))
          eats (filter #(= :eat (:type %)) [ja jb])]
      (is (= 1 (count eats))                 "exactly one pawn eats the food")
      (is (= (:id f) (:item-id (first eats))))
      (is (some #(= :go-to (:type %)) [ja jb]) "the other wanders"))))

;; ---------------------------------------------------------------------------
;; advance-job runs the active job EVERY tick — no speed gate. Move speed lives
;; in sim.job/segment-cost (ticks per cell), not in a deliberation-cadence gate,
;; so an in-flight glide accumulates one tick of progress per tick.
;; ---------------------------------------------------------------------------

(deftest moving-pawn-glides-every-tick
  (testing "an active go-to advances its glide each tick; elapsed climbs ~1/tick
            rather than waiting for a 15-tick window"
    (let [w0  (-> (world/initial-world {:width 12 :height 12})
                  (entity/add-entity (entity/make-pawn "walker" [0 0])))
          pid (:id (first (entity/pawns w0)))
          w1  (entity/update-entity w0 pid assoc :job (job/go-to [5 0]))
          wN  (nth (iterate simulation/tick w1) 6)
          mv  (get-in (entity/entity wN pid) [:job :move])]
      (is (some? mv) "a segment is in flight after a few ticks")
      (is (> (long (:elapsed mv)) 1)
          "the glide accumulated multiple ticks of progress (gate removed)"))))
