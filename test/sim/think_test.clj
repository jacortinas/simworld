(ns sim.think-test
  "The think-tree is walked purely: (deliberate world pawn) -> job-or-nil. These
   tests drive the real default tree over controlled worlds, so they exercise the
   walker mechanics (priority order, conditional gating) AND the leaf givers
   (eat picks nearest reservable food; wander yields a go-to) through behavior."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world    :as world]
   [sim.entity   :as entity]
   [sim.job      :as job]
   [sim.think    :as think]
   [sim.zone     :as zone]))

(defn- world+pawn
  "12x12 world with a pawn having `needs` at `pos`. Returns [world pawn-id]."
  [needs pos]
  (let [w (world/initial-world {:width 12 :height 12})
        p (assoc (entity/make-pawn "p" pos) :needs needs)]
    [(entity/add-entity w p) (:id p)]))

(deftest hungry-pawn-eats-nearby-food
  (testing "priority + conditional + eat giver: a hungry pawn near food gets an
            :eat job for it"
    (let [[w0 pid] (world+pawn {:food 0.1} [0 0])
          food     (entity/make-item :food [3 0])
          w        (entity/add-entity w0 food)
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :eat (:type j)))
      (is (= (:id food) (:item-id j))))))

(deftest fed-pawn-falls-through-to-wander
  (testing "not hungry -> conditional fails -> priority falls to the wander leaf"
    (let [[w0 pid] (world+pawn {:food 1.0} [5 5])
          w        (entity/add-entity w0 (entity/make-item :food [3 0]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :go-to (:type j)) "fed pawn wanders even with food present"))))

(deftest hungry-but-no-food-wanders
  (testing "hungry with no reachable food -> eat giver nil -> wander"
    (let [[w pid] (world+pawn {:food 0.1} [5 5])
          j       (think/deliberate w (entity/entity w pid))]
      (is (= :go-to (:type j))))))

(deftest eat-giver-picks-nearest-food
  (let [[w0 pid] (world+pawn {:food 0.1} [0 0])
        far      (entity/make-item :food [9 0])
        near     (entity/make-item :food [2 0])
        w        (-> w0 (entity/add-entity far) (entity/add-entity near))
        j        (think/deliberate w (entity/entity w pid))]
    (is (= (:id near) (:item-id j)) "targets the nearer food by Manhattan distance")))

(deftest eat-giver-skips-reserved-food
  (testing "food already claimed by another pawn is not offered -> wander"
    (let [[w0 a]  (world+pawn {:food 0.1} [0 0])
          food    (entity/make-item :food [2 0])
          other   (entity/make-pawn "other" [2 0])
          w       (-> w0
                      (entity/add-entity food)
                      (entity/add-entity other)
                      (entity/update-entity (:id other) assoc :job (job/eat (:id food))))
          j       (think/deliberate w (entity/entity w a))]
      (is (= :go-to (:type j)) "the only food is reserved, so a wanders"))))

(deftest wander-yields-passable-goto
  (let [[w pid] (world+pawn {:food 1.0} [5 5])
        j       (think/deliberate w (entity/entity w pid))]
    (is (= :go-to (:type j)))
    (is (some? (:target j)) "wander targets a cell")))

(deftest wander-is-deterministic
  (testing "deliberate is a pure function of (world, pawn): same inputs -> same
            wander target, seeded from the world's :rng-seed (not global rand)"
    (let [[w pid] (world+pawn {:food 1.0} [5 5])
          p       (entity/entity w pid)
          targets (repeatedly 20 #(:target (think/deliberate w p)))]
      (is (apply = targets) "20 deliberations yield one stable target"))))

(deftest wander-target-depends-on-seed-and-tick
  (testing "the chosen cell is a function of (:rng-seed, :clock, pawn id), so it
            varies across seeds and across ticks (a re-wandering pawn moves on)"
    (let [[w0 pid] (world+pawn {:food 1.0} [5 5])
          tgt      (fn [w] (:target (think/deliberate w (entity/entity w pid))))
          by-seed  (map #(tgt (assoc w0 :rng-seed %)) (range 10))
          by-tick  (map #(tgt (assoc w0 :clock %))    (range 10))]
      (is (> (count (distinct by-seed)) 1) "target depends on the seed")
      (is (> (count (distinct by-tick)) 1) "target depends on the tick"))))

(deftest haul-giver-yields-haul-job
  (testing "fed pawn + loose item + stockpile -> :haul job to a stockpile cell"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          item     (entity/make-item :wood [5 5])
          w        (-> w0
                       (entity/add-entity item)
                       (zone/add-stockpile [8 8] [9 9]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :haul (:type j)))
      (is (= (:id item) (:item-id j)))
      (is (contains? (zone/stockpile-cells w) (:destination j))
          "destination is a stockpile cell"))))

(deftest haul-giver-picks-nearest-item
  (testing "nearest loose item to the pawn is chosen (Manhattan)"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          near     (entity/make-item :wood [2 2])
          far      (entity/make-item :wood [9 9])
          w        (-> w0
                       (entity/add-entity far)
                       (entity/add-entity near)
                       (zone/add-stockpile [5 5] [6 6]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= (:id near) (:item-id j)) "hauls the nearer item first"))))

(deftest haul-giver-picks-nearest-dest-cell
  (testing "destination is the stockpile cell nearest the item"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          item     (entity/make-item :wood [5 5])
          w        (-> w0
                       (entity/add-entity item)
                       (zone/add-stockpile [0 0] [2 2]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= [2 2] (:destination j)) "the [2 2] corner is nearest [5 5] in the rect"))))

(deftest haul-giver-needs-a-stockpile
  (testing "loose item but no stockpile -> nil giver -> wander"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          w        (entity/add-entity w0 (entity/make-item :wood [5 5]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :go-to (:type j)) "no stockpile means nothing to haul to"))))

(deftest haul-giver-skips-already-stored
  (testing "an item already on a stockpile cell is not re-hauled -> wander"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          item     (entity/make-item :wood [5 5])
          w        (-> w0
                       (entity/add-entity item)
                       (zone/add-stockpile [5 5] [6 6]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :go-to (:type j)) "item sits in the stockpile, so it is not loose"))))

(deftest haul-giver-skips-reserved-item
  (testing "an item another pawn is already hauling is not offered -> wander"
    (let [[w0 a]  (world+pawn {:food 1.0} [0 0])
          item    (entity/make-item :wood [5 5])
          other   (entity/make-pawn "other" [5 5])
          w       (-> w0
                      (entity/add-entity item)
                      (entity/add-entity other)
                      (zone/add-stockpile [8 8] [9 9])
                      (entity/update-entity (:id other) assoc
                                            :job (job/haul (:id item) [8 8])))
          j       (think/deliberate w (entity/entity w a))]
      (is (= :go-to (:type j)) "the only loose item is reserved, so a wanders"))))

(deftest hunger-out-prioritizes-haul
  (testing "a hungry pawn eats even when haulable work exists (eat > haul)"
    (let [[w0 pid] (world+pawn {:food 0.1} [0 0])
          food     (entity/make-item :food [2 2])
          wood     (entity/make-item :wood [3 3])
          w        (-> w0
                       (entity/add-entity food)
                       (entity/add-entity wood)
                       (zone/add-stockpile [8 8] [9 9]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :eat (:type j)) "survival beats work")
      (is (= (:id food) (:item-id j))))))

(deftest haul-out-prioritizes-wander
  (testing "a fed pawn with haulable work hauls instead of wandering (haul > wander)"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          item     (entity/make-item :wood [5 5])
          w        (-> w0
                       (entity/add-entity item)
                       (zone/add-stockpile [8 8] [9 9]))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :haul (:type j)) "work beats idling"))))

;; ---------------------------------------------------------------------------
;; give-deliver: haul material to a blueprint (construction increment 3).
;; ---------------------------------------------------------------------------

(deftest deliver-giver-yields-deliver-job
  (testing "a stone item + a wall blueprint that wants stone -> a :deliver job"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          bp       (entity/make-blueprint :wall [5 0])      ; :cost {:stone 5}
          stone    (entity/make-item :stone [2 0])
          w        (-> w0 (entity/add-entity bp) (entity/add-entity stone))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :deliver (:type j)))
      (is (= (:id stone) (:item-id j))      "carries the matching material")
      (is (= (:id bp)    (:blueprint-id j)) "targets the blueprint"))))

(deftest deliver-giver-needs-a-matching-material
  (testing "a wall wants stone; a lone wood item never becomes a delivery"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          w        (-> w0
                       (entity/add-entity (entity/make-blueprint :wall [5 0]))
                       (entity/add-entity (entity/make-item :wood [2 0])))   ; wrong material
          j        (think/deliberate w (entity/entity w pid))]
      (is (not= :deliver (:type j)) "wood does not satisfy a stone bill"))))

(deftest deliver-giver-skips-fully-delivered
  (testing "a blueprint whose bill is met yields no further delivery"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          bp       (assoc (entity/make-blueprint :wall [5 0]) :delivered {:stone 5})
          w        (-> w0 (entity/add-entity bp) (entity/add-entity (entity/make-item :stone [2 0])))
          j        (think/deliberate w (entity/entity w pid))]
      (is (not= :deliver (:type j)) "fully-stocked site needs no more material"))))

(deftest deliver-giver-respects-the-over-delivery-cap
  (testing "in-flight deliveries count against a site's remaining need"
    (let [build-world
          (fn [delivered]
            ;; blueprint at `delivered`/5 stone, a free stone for A, and pawn B
            ;; already carrying a stone mid-deliver to the same blueprint.
            (let [[w0 a] (world+pawn {:food 1.0} [0 0])
                  bp     (assoc (entity/make-blueprint :wall [6 0]) :delivered {:stone delivered})
                  free   (entity/make-item :stone [2 0])
                  bstone (entity/make-item :stone [3 0])
                  b      (entity/make-pawn "B" [3 0])
                  w      (-> w0
                            (entity/add-entity bp) (entity/add-entity free)
                            (entity/add-entity bstone) (entity/add-entity b)
                            (entity/update-entity (:id b) assoc :carrying (:id bstone)
                                                  :job (assoc (job/deliver (:id bstone) (:id bp))
                                                              :state :in-progress :phase :go-to-dest))
                            (entity/update-entity (:id bstone) assoc :carried-by (:id b) :pos nil))]
              [w a]))]
      (let [[w a] (build-world 4)]                ; needs 1 more, 1 in flight -> 0 uncommitted
        (is (not= :deliver (:type (think/deliberate w (entity/entity w a))))
            "no new delivery when in-flight already covers the remaining need"))
      (let [[w a] (build-world 3)]                ; needs 2 more, 1 in flight -> 1 uncommitted
        (is (= :deliver (:type (think/deliberate w (entity/entity w a))))
            "a delivery is minted when the site is still short after in-flight")))))

(deftest deliver-out-prioritizes-haul
  (testing "with both a build site needing material and a stockpile, BUILDING wins"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          w        (-> w0
                       (zone/add-stockpile [10 10] [11 11])
                       (entity/add-entity (entity/make-blueprint :wall [5 0]))
                       (entity/add-entity (entity/make-item :stone [2 0])))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :deliver (:type j))
          "deliver (build) outranks haul (stockpile): material feeds the site, not the pile"))))

;; ---------------------------------------------------------------------------
;; give-construct: build a READY blueprint (construction increment 4).
;; ---------------------------------------------------------------------------

(deftest construct-giver-yields-for-a-ready-blueprint
  (testing "a fully-delivered blueprint yields a construct job at an adjacent cell"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          bp       (assoc (entity/make-blueprint :wall [5 5]) :delivered {:stone 5})
          w        (entity/add-entity w0 bp)
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :construct (:type j)))
      (is (= (:id bp) (:blueprint-id j)))
      (is (some? (:stand j))                      "picks an adjacent standing cell")
      (is (= 1 (apply max (map #(Math/abs (- (long %1) (long %2)))
                               (:stand j) [5 5])))  "the stand cell is 8-adjacent to the site"))))

(deftest construct-giver-skips-an-unready-blueprint
  (testing "a blueprint still missing material is left for the deliver leaf"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          bp       (assoc (entity/make-blueprint :wall [5 5]) :delivered {:stone 2})  ; 2/5
          w        (entity/add-entity w0 bp)
          j        (think/deliberate w (entity/entity w pid))]
      (is (not= :construct (:type j)) "not ready -> no construct job"))))

(deftest construct-giver-skips-a-reserved-blueprint
  (testing "a site another pawn is already building is not re-claimed"
    (let [[w0 a] (world+pawn {:food 1.0} [0 0])
          bp     (assoc (entity/make-blueprint :wall [5 5]) :delivered {:stone 5})
          b      (entity/make-pawn "B" [6 5])
          w      (-> w0
                     (entity/add-entity bp)
                     (entity/add-entity b)
                     (entity/update-entity (:id b) assoc
                                           :job (assoc (job/construct (:id bp) [6 5])
                                                       :state :in-progress :phase :build)))
          j      (think/deliberate w (entity/entity w a))]
      (is (not= :construct (:type j)) "the site is reserved by B's construct job"))))

(deftest construct-out-prioritizes-deliver-and-haul
  (testing "a ready site is finished before more material is fetched or items hauled"
    (let [[w0 pid] (world+pawn {:food 1.0} [0 0])
          ready    (assoc (entity/make-blueprint :wall [5 5]) :delivered {:stone 5})
          needy    (entity/make-blueprint :wall [8 8])         ; still wants material
          w        (-> w0
                       (entity/add-entity ready)
                       (entity/add-entity needy)
                       (zone/add-stockpile [10 10] [11 11])
                       (entity/add-entity (entity/make-item :stone [2 0])))
          j        (think/deliberate w (entity/entity w pid))]
      (is (= :construct (:type j)) "finish-ready beats fetch-more-material beats stockpile"))))
