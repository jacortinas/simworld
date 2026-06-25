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

;; ---------------------------------------------------------------------------
;; Review-fix coverage: the over-delivery cap under concurrency, and the
;; reachability guards that stop doomed deliver/construct jobs busy-looping.
;; ---------------------------------------------------------------------------

(deftest over-delivery-cap-holds-across-two-idle-pawns
  (testing "two idle pawns + a site needing one more unit -> exactly one delivery is minted"
    (let [w0 (world/initial-world {:width 12 :height 12})
          a  (entity/make-pawn "A" [0 0])
          b  (entity/make-pawn "B" [1 0])
          bp (assoc (entity/make-blueprint :wall [6 0]) :delivered {:stone 4})  ; needs 1 more
          w  (-> w0
                 (entity/add-entity a) (entity/add-entity b) (entity/add-entity bp)
                 (entity/add-entity (entity/make-item :stone [2 0]))
                 (entity/add-entity (entity/make-item :stone [3 0])))
          ;; deliberate+assign both in sequence, as the redeliberate system would
          w1 (reduce (fn [w pid]
                       (if-let [j (think/deliberate w (entity/entity w pid))]
                         (job/assign w pid j)
                         w))
                     w [(:id a) (:id b)])
          delivers (->> [(:id a) (:id b)]
                        (keep #(:job (entity/entity w1 %)))
                        (filter #(= :deliver (:type %))))]
      (is (= 1 (count delivers))
          "the cap (in-flight count) stops the second pawn over-fetching for a 1-short site"))))

(deftest construct-giver-skips-a-boxed-in-ready-blueprint
  (testing "a ready site with no REACHABLE adjacent cell does not become a doomed construct"
    (let [w0     (world/initial-world {:width 7 :height 7})
          boxed  (assoc (entity/make-blueprint :wall [3 3]) :delivered {:stone 5})
          ring   (for [dx [-1 0 1] dy [-1 0 1] :when (not (and (zero? dx) (zero? dy)))]
                   [(+ 3 dx) (+ 3 dy)])
          w1     (reduce (fn [w c] (entity/add-entity w (entity/make-building c)))
                         (entity/add-entity w0 boxed) ring)
          p      (entity/make-pawn "P" [0 0])
          w      (entity/add-entity w1 p)
          j      (think/deliberate w (entity/entity w (:id p)))]
      (is (not= :construct (:type j)) "no reachable stand cell -> no construct (no busy-loop)"))))

(deftest deliver-giver-skips-unreachable-material
  (testing "material walled off from the pawn is not chosen (no pickup-then-fail loop)"
    (let [w0 (world/initial-world {:width 7 :height 7})
          bp (entity/make-blueprint :wall [6 6])
          ;; a full vertical wall at x=3 splits the map; stone + site on the far side
          w1 (reduce (fn [w y] (entity/add-entity w (entity/make-building [3 y])))
                     (-> w0 (entity/add-entity bp)
                            (entity/add-entity (entity/make-item :stone [5 5])))
                     (range 7))
          p  (entity/make-pawn "P" [0 0])                 ; near side
          w  (entity/add-entity w1 p)
          j  (think/deliberate w (entity/entity w (:id p)))]
      (is (not= :deliver (:type j)) "the only stone is unreachable, so no delivery is attempted"))))

;; ---------------------------------------------------------------------------
;; Work-priority matrix: per-pawn :work-priorities drive the WORK middle of
;; deliberation (RimWorld's Work tab). Reflexes (eat) still precede work; wander
;; still follows it.
;; ---------------------------------------------------------------------------

(defn- build-and-haul-world
  "A 12x12 world with a READY wall site, a stockpile, and a loose haulable item,
   so BOTH build work and haul work are available. Returns [world add-pawn-fn]."
  []
  (let [w (-> (world/initial-world {:width 12 :height 12})
              (entity/add-entity (assoc (entity/make-blueprint :wall [5 5]) :delivered {:stone 5}))
              (zone/add-stockpile [9 9] [10 10])
              (entity/add-entity (entity/make-item :wood [2 2])))]
    [w (fn [w pawn] (entity/add-entity w pawn))]))

(deftest pawn-priorities-merges-over-defaults
  (is (= think/default-priorities (think/pawn-priorities {})) "no overrides -> the defaults")
  (is (= (assoc think/default-priorities :build 0)
         (think/pawn-priorities {:work-priorities {:build 0}}))
      "an override wins; unspecified work types keep the default"))

(deftest default-pawn-builds-before-hauling
  (testing "an un-customized pawn reproduces the old fixed Build-then-Haul order"
    (let [[w add] (build-and-haul-world)
          p  (entity/make-pawn "P" [0 0])                 ; no :work-priorities
          w  (add w p)
          j  (think/deliberate w (entity/entity w (:id p)))]
      (is (= :construct (:type j)) "default order finishes the ready build first"))))

(deftest disabling-a-work-type-skips-its-jobs
  (testing "a pawn with Build OFF ignores the ready site and hauls instead"
    (let [[w add] (build-and-haul-world)
          p  (assoc (entity/make-pawn "P" [0 0]) :work-priorities {:build 0 :haul 3})
          w  (add w p)
          j  (think/deliberate w (entity/entity w (:id p)))]
      (is (= :haul (:type j)) "Build disabled -> the build site is not this pawn's job"))))

(deftest raising-a-priority-reorders-work
  (testing "Haul at priority 1 beats Build at 3, so the pawn hauls before it builds"
    (let [[w add] (build-and-haul-world)
          p  (assoc (entity/make-pawn "P" [0 0]) :work-priorities {:build 3 :haul 1})
          w  (add w p)
          j  (think/deliberate w (entity/entity w (:id p)))]
      (is (= :haul (:type j)) "a lower priority NUMBER is more urgent"))))

(deftest all-work-disabled-falls-to-wander
  (testing "a pawn with every work type off still wanders (idle), never stalls"
    (let [[w add] (build-and-haul-world)
          p  (assoc (entity/make-pawn "P" [0 0]) :work-priorities {:build 0 :haul 0})
          w  (add w p)
          j  (think/deliberate w (entity/entity w (:id p)))]
      (is (= :go-to (:type j)) "no enabled work -> wander"))))
