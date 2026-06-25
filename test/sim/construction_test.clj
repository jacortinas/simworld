(ns sim.construction-test
  "End-to-end vertical slice of the construction loop, headless: drop a wall
   blueprint, let an autonomous pawn deliver its material bill and build it, and
   assert the finished wall (1) exists at the site, (2) consumed every stone, and
   (3) now BLOCKS the pathgrid, proving the promotion's remove+add busted the
   size-1 memo (an in-place update would have left it walkable). This single test
   guards the whole place -> deliver -> construct -> built arc and the memo bust."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs     :as defs]
   [sim.world    :as world]
   [sim.entity   :as entity]
   [sim.ai       :as ai]
   [sim.pathgrid :as pathgrid]
   [sim.regions  :as regions]))

(use-fixtures :each (fn [t] (defs/load!) (t)))

(defn- drive-colony
  "Run the real AI cadence (idle -> redeliberate -> assign, then advance-job) for
   every pawn each tick, with NO rare-band throttle so the loop completes
   compactly. Stops at `done?` or `max-ticks`."
  [world pawn-ids max-ticks done?]
  (loop [w world n 0]
    (if (or (>= n max-ticks) (done? w))
      w
      (recur (reduce (fn [w pid]
                       (let [p (entity/entity w pid)
                             w (if (:job p) w (ai/redeliberate w p))
                             p (entity/entity w pid)]
                         (if (:job p) (ai/advance-job w p) w)))
                     w pawn-ids)
             (inc n)))))

(deftest place-deliver-build-vertical-slice
  (let [w0   (world/initial-world {:width 14 :height 8})
        pawn (entity/make-pawn "Builder" [0 0])
        bp   (entity/make-blueprint :wall [7 4])           ; :cost {:stone 5}
        pid  (:id pawn)
        bid  (:id bp)
        ;; 5 loose stone scattered for the pawn to ferry
        w    (reduce (fn [w pos] (entity/add-entity w (entity/make-item :stone pos)))
                     (-> w0 (entity/add-entity pawn) (entity/add-entity bp))
                     [[1 1] [2 5] [3 2] [5 6] [4 3]])
        built? (fn [w] (seq (filter entity/built? (entity/buildings w))))
        wf   (drive-colony w [pid] 5000 built?)
        wall (first (filter entity/built? (entity/buildings wf)))]
    (testing "the blueprint became a built wall at its site"
      (is (some? wall))
      (is (= [7 4] (:pos wall)))
      (is (= :built (:state wall)))
      (is (empty? (entity/blueprints wf)) "no blueprint remains"))
    (testing "every unit of the material bill was consumed"
      (is (zero? (count (entity/items wf))) "all 5 stone delivered and gone"))
    (testing "the finished wall blocks pathing (the memo bust)"
      (is (not (pathgrid/passable? (pathgrid/for-world wf) 7 4))
          "the built wall cell is impassable")
      (is (not (regions/reachable? wf [7 4] [0 0]))
          "the wall cell is no longer a reachable destination"))))
