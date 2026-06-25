(ns sim.job-test
  "Tests for the job FSMs in sim.job — especially the 4-phase haul job, the
   most intricate pure logic in the sim. All headless: jobs are pure
   (world,pawn)->world, so we can drive them tick-by-tick and assert on the
   resulting world without any rendering."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world  :as world]
   [sim.entity :as entity]
   [sim.tile     :as tile]
   [sim.log      :as log]
   [sim.movement :as movement]
   [sim.pathgrid :as pathgrid]
   [sim.job      :as job]))

(defn- setup
  "Build a 12x12 all-grass world with a hauler pawn and one :wood item.
   Returns [world pawn-id item-id]."
  [pawn-pos item-pos]
  (let [w    (world/initial-world {:width 12 :height 12})
        pawn (entity/make-pawn "hauler" pawn-pos)
        item (entity/make-item :wood item-pos)]
    [(-> w (entity/add-entity pawn) (entity/add-entity item))
     (:id pawn)
     (:id item)]))

(defn- drive
  "Call job/advance until the pawn's job is done? (or max-steps hit). Re-fetches
   the pawn each step: advance returns a *world*, and the pawn's job/pos mutate
   between calls — this mirrors how sim.ai/decide drives a job."
  [world pid max-steps]
  (loop [w world n 0]
    (let [j (:job (entity/entity w pid))]
      (if (or (nil? j) (job/done? j) (>= n max-steps))
        w
        (recur (job/advance w (entity/entity w pid)) (inc n))))))

(defn- drive-n
  "Call job/advance exactly `n` times, re-fetching the pawn each step."
  [world pid n]
  (reduce (fn [w _] (job/advance w (entity/entity w pid))) world (range n)))

;; ---------------------------------------------------------------------------
;; Haul — full lifecycle
;; ---------------------------------------------------------------------------

(deftest haul-happy-path
  (let [[w0 pid iid] (setup [0 0] [3 0])
        dest         [6 0]
        w1           (entity/update-entity w0 pid assoc :job (job/haul iid dest))
        wf           (drive w1 pid 200)
        pawn         (entity/entity wf pid)
        item         (entity/entity wf iid)]
    (is (job/complete? (:job pawn)) "haul job reaches :complete")
    (is (nil? (:carrying pawn))     "pawn is no longer carrying")
    (is (= dest (:pos pawn))        "pawn ends at the destination")
    (is (= dest (:pos item))        "item left on the ground at destination")
    (is (nil? (:carried-by item))   "item is no longer carried")
    (is (seq (log/of-type wf :job/pickup)) "pickup was logged")
    (is (seq (log/of-type wf :job/drop))   "drop was logged")
    (is (seq (log/of-type wf :job/complete)) "completion was logged")))

;; ---------------------------------------------------------------------------
;; Haul — individual phases
;; ---------------------------------------------------------------------------

(deftest haul-pickup-phase
  (testing "picking up binds item<->pawn and clears the item's ground pos"
    (let [[w0 pid iid] (setup [3 0] [3 0])   ; pawn standing on the item
          w1   (entity/update-entity w0 pid assoc :job
                                     (assoc (job/haul iid [6 0]) :phase :pickup))
          w2   (job/advance w1 (entity/entity w1 pid))
          pawn (entity/entity w2 pid)
          item (entity/entity w2 iid)]
      (is (= iid (:carrying pawn))   "pawn now carries the item")
      (is (= pid (:carried-by item)) "item knows its carrier")
      (is (nil? (:pos item))         "carried item has no ground position")
      (is (= :go-to-dest (get-in pawn [:job :phase])) "advances to :go-to-dest"))))

(deftest haul-drop-phase
  (testing "dropping places the item at the pawn's tile and completes"
    (let [[w0 pid iid] (setup [6 0] [3 0])
          w1   (-> w0
                   (entity/update-entity pid assoc
                                         :carrying iid
                                         :job (assoc (job/haul iid [6 0]) :phase :drop))
                   (entity/update-entity iid assoc :carried-by pid :pos nil))
          w2   (job/advance w1 (entity/entity w1 pid))
          pawn (entity/entity w2 pid)
          item (entity/entity w2 iid)]
      (is (job/complete? (:job pawn))  "job completes on drop")
      (is (nil? (:carrying pawn))      "pawn stops carrying")
      (is (= [6 0] (:pos item))        "item dropped at the pawn's tile")
      (is (nil? (:carried-by item))    "item is free again"))))

(deftest haul-fails-when-item-disappears
  (testing "an item that vanishes mid-haul fails the job rather than NPEing"
    (let [[w0 pid iid] (setup [0 0] [3 0])
          w1   (entity/update-entity w0 pid assoc :job (job/haul iid [6 0]))
          w2   (entity/remove-entity w1 iid)            ; item despawns
          w3   (job/advance w2 (entity/entity w2 pid))]
      (is (job/failed? (:job (entity/entity w3 pid))))
      (is (seq (log/of-type w3 :job/failed)) "failure was logged"))))

;; ---------------------------------------------------------------------------
;; go-to — completion and failure
;; ---------------------------------------------------------------------------

(defn- setup-deliver
  "12x12 grass world with a builder pawn, a :stone item, and a :wall blueprint.
   Returns [world pawn-id item-id blueprint-id]."
  [pawn-pos item-pos bp-pos]
  (let [w    (world/initial-world {:width 12 :height 12})
        pawn (entity/make-pawn "builder" pawn-pos)
        item (entity/make-item :stone item-pos)
        bp   (entity/make-blueprint :wall bp-pos)]
    [(-> w (entity/add-entity pawn) (entity/add-entity item) (entity/add-entity bp))
     (:id pawn) (:id item) (:id bp)]))

(deftest deliver-happy-path
  (let [[w0 pid iid bid] (setup-deliver [0 0] [3 0] [6 0])
        w1   (entity/update-entity w0 pid assoc :job (job/deliver iid bid))
        wf   (drive w1 pid 200)
        pawn (entity/entity wf pid)
        bp   (entity/entity wf bid)]
    (is (job/complete? (:job pawn))     "deliver reaches :complete")
    (is (nil? (:carrying pawn))         "pawn is no longer carrying")
    (is (nil? (entity/entity wf iid))   "the material item is consumed into the site")
    (is (= 1 (get-in bp [:delivered :stone])) "the blueprint tallies one stone")
    (is (seq (log/of-type wf :job/deliver)) "delivery was logged")))

(deftest deliver-deposit-increments-not-overwrites
  (testing "the :deposit phase consumes the item and bumps an existing tally"
    (let [[w0 pid iid bid] (setup-deliver [6 0] [3 0] [6 0])     ; pawn already on the site
          w1   (-> w0
                   (entity/update-entity pid assoc :carrying iid
                                         :job (assoc (job/deliver iid bid) :phase :deposit))
                   (entity/update-entity iid assoc :carried-by pid :pos nil)
                   (entity/update-entity bid assoc :delivered {:stone 2}))   ; pre-seeded tally
          w2   (job/advance w1 (entity/entity w1 pid))
          pawn (entity/entity w2 pid)
          bp   (entity/entity w2 bid)]
      (is (job/complete? (:job pawn)))
      (is (nil? (:carrying pawn)))
      (is (nil? (entity/entity w2 iid))           "item consumed")
      (is (= 3 (get-in bp [:delivered :stone]))   "tally incremented 2 to 3, not replaced"))))

(deftest deliver-fails-when-blueprint-vanishes
  (testing "a cancelled or already-built blueprint fails the delivery, no NPE"
    (let [[w0 pid iid bid] (setup-deliver [0 0] [3 0] [6 0])
          w1   (entity/update-entity w0 pid assoc :job (job/deliver iid bid))
          w2   (entity/remove-entity w1 bid)            ; blueprint cancelled mid-haul
          w3   (job/advance w2 (entity/entity w2 pid))]
      (is (job/failed? (:job (entity/entity w3 pid)))))))

(deftest deliver-fails-when-item-vanishes
  (let [[w0 pid iid bid] (setup-deliver [0 0] [3 0] [6 0])
        w1   (entity/update-entity w0 pid assoc :job (job/deliver iid bid))
        w2   (entity/remove-entity w1 iid)
        w3   (job/advance w2 (entity/entity w2 pid))]
    (is (job/failed? (:job (entity/entity w3 pid))))))

;; ---------------------------------------------------------------------------
;; Construct -- build a READY blueprint into its finished form (increment 4).
;; The pawn stands ADJACENT (a wall promotes to a blocker) and accumulates work;
;; promotion goes through remove-entity + add-entity so the pathgrid memo busts.
;; ---------------------------------------------------------------------------

(defn- setup-construct
  "12x12 grass world with a builder pawn and a READY wall blueprint (its 5 stone
   already delivered). Returns [world pawn-id blueprint-id]."
  [pawn-pos bp-pos]
  (let [w    (world/initial-world {:width 12 :height 12})
        pawn (entity/make-pawn "builder" pawn-pos)
        bp   (assoc (entity/make-blueprint :wall bp-pos) :delivered {:stone 5})]
    [(-> w (entity/add-entity pawn) (entity/add-entity bp))
     (:id pawn) (:id bp)]))

(deftest construct-happy-path
  (let [[w0 pid bid] (setup-construct [0 0] [5 5])
        w1    (entity/update-entity w0 pid assoc :job (job/construct bid [4 5]))   ; stand adjacent
        wf    (drive w1 pid 600)
        pawn  (entity/entity wf pid)
        built (first (filter entity/built? (entity/buildings wf)))]
    (is (job/complete? (:job pawn))         "construct reaches :complete")
    (is (entity/built? (entity/entity wf bid)) "the blueprint id now holds the built wall (id reused for a pure tick)")
    (is (some? built)                       "a built wall now exists")
    (is (= [5 5] (:pos built))              "at the blueprint's position")
    (is (true? (:blocks-path? built))       "the finished wall blocks paths")
    (is (= [4 5] (:pos pawn))               "builder stood adjacent, not inside the wall")
    (is (seq (log/of-type wf :job/built))   "build was logged")))

(deftest construct-promotion-busts-the-pathgrid-memo
  (testing "promotion via remove+add changes the building-set identity, so the wall blocks"
    (let [[w0 pid bid] (setup-construct [4 5] [5 5])       ; pawn already adjacent
          w1 (entity/update-entity w0 pid assoc :job
                                   (assoc (job/construct bid [4 5]) :phase :build))]
      (is (pathgrid/passable? (pathgrid/for-world w1) 5 5) "ghost cell starts passable")
      (let [wf (drive w1 pid 200)]
        (is (not (pathgrid/passable? (pathgrid/for-world wf) 5 5))
            "after build the wall cell is blocked (the memo rebuilt)")))))

(deftest construct-fails-when-blueprint-vanishes
  (let [[w0 pid bid] (setup-construct [4 5] [5 5])
        w1   (entity/update-entity w0 pid assoc :job (job/construct bid [4 5]))
        w2   (entity/remove-entity w1 bid)
        w3   (job/advance w2 (entity/entity w2 pid))]
    (is (job/failed? (:job (entity/entity w3 pid))))))

(deftest deliver-failure-drops-carried-material-not-leaks-it
  (testing "a deliver that fails WHILE carrying returns the material to the ground"
    (let [[w0 pid iid bid] (setup-deliver [6 0] [3 0] [6 0])
          w1   (-> w0
                   (entity/update-entity pid assoc :carrying iid
                                         :job (assoc (job/deliver iid bid)
                                                     :phase :go-to-dest :state :in-progress))
                   (entity/update-entity iid assoc :carried-by pid :pos nil)
                   (entity/remove-entity bid))               ; blueprint built/cancelled out from under it
          w2   (job/advance w1 (entity/entity w1 pid))
          pawn (entity/entity w2 pid)
          item (entity/entity w2 iid)]
      (is (job/failed? (:job pawn))  "the job fails")
      (is (nil? (:carrying pawn))    "pawn stops carrying")
      (is (some? item)               "the material item still exists (NOT leaked)")
      (is (= [6 0] (:pos item))      "dropped back at the pawn's cell")
      (is (nil? (:carried-by item))  "and is free to be hauled again"))))

(deftest construct-defers-promotion-while-footprint-occupied
  (testing "a finished build holds rather than sealing a pawn standing on the cell"
    (let [[w0 pid bid] (setup-construct [4 5] [5 5])
          other (entity/make-pawn "passer" [5 5])             ; standing ON the ghost cell
          w1 (-> w0
                 (entity/add-entity other)
                 (entity/update-entity pid assoc :job
                                       (assoc (job/construct bid [4 5]) :phase :build :state :in-progress))
                 (entity/update-entity bid assoc :work-done 119))   ; one tick from done
          w2 (reduce (fn [w _] (job/advance w (entity/entity w pid))) w1 (range 5))]
      (is (entity/blueprint? (entity/entity w2 bid)) "still a blueprint; not sealed over the pawn")
      (is (pathgrid/passable? (pathgrid/for-world w2) 5 5) "cell stays passable while occupied")
      (testing "once the occupant steps off, it promotes"
        (let [w3 (entity/move-entity w2 (:id other) [5 6])
              w4 (job/advance w3 (entity/entity w3 pid))]
          (is (entity/built? (entity/entity w4 bid)) "promotes once the cell clears")
          (is (not (pathgrid/passable? (pathgrid/for-world w4) 5 5))))))))

(deftest construct-door-blueprint-promotes-with-portal-and-size
  (testing "a multi-cell door blueprint builds into a portal door, flags + :size restored"
    (let [w0   (world/initial-world {:width 12 :height 12})
          pawn (entity/make-pawn "builder" [0 5])
          bp   (-> (entity/make-blueprint :door [4 5]) (assoc :size [3 1] :delivered {:wood 3}))
          pid  (:id pawn) bid (:id bp)
          w1   (-> w0 (entity/add-entity pawn) (entity/add-entity bp)
                   (entity/update-entity pid assoc :job (job/construct bid [3 5])))
          wf   (drive w1 pid 400)
          door (entity/entity wf bid)]
      (is (entity/built? door)          "the door is built")
      (is (= :door (:def door)))
      (is (true? (:portal? door))       "portal flag restored from the def")
      (is (= 20 (:open-ticks door))     "open-ticks restored from the def")
      (is (= 0 (:open door))            "starts closed")
      (is (= [3 1] (:size door))        "multi-cell footprint preserved")
      (is (false? (:blocks-path? door)) "a door does not block paths"))))

(deftest go-to-completes
  (let [[w0 pid _] (setup [0 0] [0 0])
        w1   (entity/update-entity w0 pid assoc :job (job/go-to [4 4]))
        wf   (drive w1 pid 200)
        pawn (entity/entity wf pid)]
    (is (job/complete? (:job pawn)))
    (is (= [4 4] (:pos pawn)) "pawn arrives at the target tile")))

(deftest go-to-fails-when-target-unreachable
  (testing "pathing to an impassable tile fails the job on the first step"
    (let [[w0 pid _] (setup [0 0] [0 0])
          w1   (-> w0
                   (update :grid tile/set-tile 5 5 :wall)
                   (entity/update-entity pid assoc :job (job/go-to [5 5])))
          w2   (job/advance w1 (entity/entity w1 pid))]
      (is (job/failed? (:job (entity/entity w2 pid)))))))

;; ---------------------------------------------------------------------------
;; Sub-cell movement timing — a pawn GLIDES across a cell over `cost` ticks
;; instead of teleporting one tile per advance. :pos flips to the destination at
;; move-start (decision D2); the glide (:elapsed -> :cost) is what takes time.
;; cost = move-ticks × traversal-cost, so terrain and diagonals slow the walk.
;; ---------------------------------------------------------------------------

(deftest segment-cost-cardinal-is-move-ticks
  (let [g (tile/make-grid 12 12)]
    (is (= 15 (movement/segment-cost g 15 [0 0] [1 0])) "grass cardinal = move-ticks")))

(deftest segment-cost-diagonal-scales-by-root2
  (let [g (tile/make-grid 12 12)]
    (is (= 21 (movement/segment-cost g 15 [0 0] [1 1])) "round(15 × √2) = 21")))

(deftest segment-cost-honors-terrain
  (let [g (tile/set-tile (tile/make-grid 12 12) 1 0 :water)] ; move-cost 2.5
    (is (= 38 (movement/segment-cost g 15 [0 0] [1 0])) "round(15 × 2.5) = 38")))

(deftest segment-cost-never-zero
  (let [g (tile/make-grid 12 12)]
    (is (= 1 (movement/segment-cost g 0 [0 0] [1 0])) "clamps to a 1-tick minimum")))

(deftest walking-flips-pos-and-records-a-segment
  (testing "starting a move sets :pos to the destination cell and records the
            from/to/cost it is gliding across — still in-progress, not arrived"
    (let [[w0 pid _] (setup [0 0] [0 0])
          w1   (entity/update-entity w0 pid assoc :job (job/go-to [1 0]))
          w2   (drive-n w1 pid 2)            ; compute path, then start segment
          pawn (entity/entity w2 pid)
          mv   (get-in pawn [:job :move])]
      (is (= [1 0] (:pos pawn))    "pos flips to the destination at move-start")
      (is (= [0 0] (:from mv))     "move remembers the cell being left")
      (is (= [1 0] (:to mv))       "...and the cell being entered")
      (is (= 15 (:cost mv))        "cardinal grass costs move-ticks to cross")
      (is (not (job/complete? (:job pawn))) "still gliding, not yet arrived"))))

(deftest walking-marks-in-progress-once-and-keeps-it
  (testing "the first advance marks a go-to :in-progress; later glide ticks keep it
            there (mark-in-progress skips the redundant per-tick state write and
            must NOT revert the state)"
    (let [[w0 pid _] (setup [0 0] [0 0])
          w1   (entity/update-entity w0 pid assoc :job (job/go-to [3 0]))
          a1   (drive-n w1 pid 1)
          a5   (drive-n w1 pid 5)]
      (is (= :pending (get-in (entity/entity w1 pid) [:job :state]))
          "a freshly assigned go-to starts :pending")
      (is (= :in-progress (get-in (entity/entity a1 pid) [:job :state]))
          "the first advance marks the job :in-progress")
      (is (= :in-progress (get-in (entity/entity a5 pid) [:job :state]))
          "still :in-progress several glide ticks later (guard is a no-op, not a revert)")
      (is (not (job/complete? (:job (entity/entity a5 pid))))
          "a 3-cell walk is nowhere near done at tick 5"))))

(deftest diagonal-move-costs-more-ticks
  (testing "a diagonal segment records the √2-scaled cost"
    (let [[w0 pid _] (setup [0 0] [0 0])
          w1   (entity/update-entity w0 pid assoc :job (job/go-to [1 1]))
          w2   (drive-n w1 pid 2)
          mv   (get-in (entity/entity w2 pid) [:job :move])]
      (is (= [1 1] (:to mv)))
      (is (= 21 (:cost mv)) "diagonal grass = round(15 × √2)"))))

(deftest crossing-a-cell-takes-many-ticks-not-one
  (testing "a one-cell go-to is NOT done after a couple ticks; it needs ~cost"
    (let [[w0 pid _] (setup [0 0] [0 0])
          w1   (entity/update-entity w0 pid assoc :job (job/go-to [1 0]))
          mid  (drive-n w1 pid 8)            ; well short of cost (15)
          done (drive w1 pid 200)]
      (is (not (job/done? (:job (entity/entity mid pid))))
          "still walking partway across the cell")
      (is (job/complete? (:job (entity/entity done pid))) "completes once across")
      (is (= [1 0] (:pos (entity/entity done pid))) "ends on the target cell"))))

(deftest arrival-fires-on-the-exact-cost-tick
  (testing "a one-cell go-to (cost 15) completes on the precise tick the glide
            finishes — guards the (< elapsed cost) threshold against off-by-one.
            Tick 1 computes the path, tick 2 starts the segment (elapsed 0),
            then 15 glide ticks -> done at tick 17."
    (let [[w0 pid _] (setup [0 0] [0 0])
          w1   (entity/update-entity w0 pid assoc :job (job/go-to [1 0]))]
      (is (not (job/done? (:job (entity/entity (drive-n w1 pid 16) pid))))
          "still gliding at tick 16")
      (is (job/complete? (:job (entity/entity (drive-n w1 pid 17) pid)))
          "arrives exactly at tick 17"))))

(deftest multi-cell-path-has-no-dead-tick-between-cells
  (testing "rolling from one segment straight into the next costs no extra tick:
            two cardinal cells (cost 15 each) finish at tick 32, not 33+.
            1 path tick + 2×15 glide ticks = 32, with the boundary handled in the
            same tick the first cell completes."
    (let [[w0 pid _] (setup [0 0] [0 0])
          w1   (entity/update-entity w0 pid assoc :job (job/go-to [2 0]))]
      (is (not (job/done? (:job (entity/entity (drive-n w1 pid 31) pid))))
          "still on the second cell at tick 31")
      (is (job/complete? (:job (entity/entity (drive-n w1 pid 32) pid)))
          "both cells crossed by tick 32 — no per-cell settle tick")
      (is (= [2 0] (:pos (entity/entity (drive-n w1 pid 32) pid)))))))

;; ---------------------------------------------------------------------------
;; Step-validation: a wall built on a walking pawn's NEXT path cell must nil
;; the path (free replan via walk-toward's (nil? path) branch) rather than
;; gliding the pawn onto the wall. The PathGrid reflects the wall the instant
;; it is placed, so next-cell-passable? sees it.
;; ---------------------------------------------------------------------------

(deftest wall-on-next-cell-forces-replan
  (testing "a wall built on a walking pawn's next path cell nils the path so it
            replans rather than gliding onto the wall"
    (let [g    (tile/make-grid 3 3)
          pawn (-> (entity/make-pawn "p" [1 0])
                   (assoc :job {:type :go-to :state :in-progress :target [2 0]
                                :path [[1 0] [2 0]] :path-index 0 :move nil}))
          w    (-> {:grid g :entities {(:id pawn) pawn} :kinds (entity/empty-kinds)}
                   (entity/add-entity (entity/make-building [2 0])))
          w'   (job/advance w (entity/entity w (:id pawn)))
          p'   (entity/entity w' (:id pawn))]
      (is (not= [2 0] (:pos p')) "pawn did not step onto the wall cell")
      (is (nil? (get-in p' [:job :path])) "stale path dropped for a free replan"))))

;; ---------------------------------------------------------------------------
;; assign — the one path all assignment routes through
;; ---------------------------------------------------------------------------

(deftest assign-sets-job-and-logs
  (let [[w0 pid _] (setup [0 0] [0 0])
        w1   (job/assign w0 pid (job/go-to [3 3]) job/forced-by-player)
        pawn (entity/entity w1 pid)
        es   (log/of-type w1 :job/assigned)]
    (is (= :go-to  (get-in pawn [:job :type])))
    (is (= [3 3]   (get-in pawn [:job :target])))
    (is (= :forced (get-in pawn [:job :priority])) "override merged onto the job")
    (is (= :player (get-in pawn [:job :source])))
    (is (= 1 (count es)) "exactly one :job/assigned logged")
    (is (= pid  (:pawn   (first es))))
    (is (= [3 3] (:target (first es))) "entry derives target from the job map")))

(deftest assign-derives-haul-fields-from-job
  (let [[w0 pid iid] (setup [0 0] [3 0])
        w1 (job/assign w0 pid (job/haul iid [6 0]) job/forced-by-player)
        e  (first (log/of-type w1 :job/assigned))]
    (is (= :haul (:job e)))
    (is (= iid   (:item e))         "haul logs the item id")
    (is (= [6 0] (:destination e))  "haul logs the destination")))

;; ---------------------------------------------------------------------------
;; assign primes the go-to path eagerly — so the route exists in world state
;; the instant the order is given, drawable by the path overlay even while the
;; sim is PAUSED (no tick has run). Without this, :path stays nil until the
;; first tick after resume computes it lazily in walk-toward.
;; ---------------------------------------------------------------------------

(deftest assign-go-to-primes-path-immediately
  (testing "assigning a go-to computes the A* path at assign time — no tick run"
    (let [[w0 pid _] (setup [0 0] [3 0])
          w1   (job/assign w0 pid (job/go-to [3 3]) job/forced-by-player)
          path (get-in (entity/entity w1 pid) [:job :path])]
      (is (some? path)        "path is populated at assignment, before any advance")
      (is (= [0 0] (first path)) "path starts at the pawn's current position")
      (is (= [3 3] (peek path))  "path ends at the target"))))

(deftest assign-go-to-unreachable-leaves-path-nil
  (testing "if the target can't be reached, priming leaves :path nil (walk-toward
            will mark the job :failed on the first advance, as before)"
    (let [[w0 pid _] (setup [0 0] [0 0])
          ;; wall the target tile itself: find-path returns nil for it
          w0'  (update w0 :grid tile/set-tile 5 5 :wall)
          w1   (job/assign w0' pid (job/go-to [5 5]) job/forced-by-player)
          job  (:job (entity/entity w1 pid))]
      (is (nil? (:path job)) "no path -> :path stays nil")
      (is (= :go-to (:type job)) "job is still assigned"))))

(deftest assign-haul-does-not-prime-path
  (testing "haul jobs are NOT primed at assign — their path is recomputed per
            phase (next-phase nils it), so priming there would be wrong"
    (let [[w0 pid iid] (setup [0 0] [3 0])
          w1 (job/assign w0 pid (job/haul iid [6 0]) job/forced-by-player)]
      (is (nil? (get-in (entity/entity w1 pid) [:job :path]))
          "haul :path remains nil at assign time"))))

;; ---------------------------------------------------------------------------
;; Reservations — assign refuses an AUTO claim of a target another pawn already
;; holds (forced player orders override); the haul :pickup phase guards against
;; the same-tick double-grab so a contested item is carried at most once.
;; ---------------------------------------------------------------------------

(defn- drive-all
  "Advance every pawn in `pids` one job-step per round, until all are done? or
   max-steps. Mirrors how advance-jobs-system steps all pawns each tick."
  [world pids max-steps]
  (loop [w world n 0]
    (if (or (>= n max-steps)
            (every? (fn [pid] (let [j (:job (entity/entity w pid))]
                                (or (nil? j) (job/done? j))))
                    pids))
      w
      (recur (reduce (fn [w pid]
                       (let [p (entity/entity w pid)]
                         (if (and p (:job p) (not (job/done? (:job p))))
                           (job/advance w p)
                           w)))
                     w pids)
             (inc n)))))

(deftest assign-blocks-auto-claim-of-reserved-target
  (testing "an auto (non-forced) haul of an item another pawn already hauls is
            refused: no job set, world logs :job/blocked"
    (let [[w0 a iid] (setup [0 0] [3 0])
          b   (entity/make-pawn "b" [1 1])
          w1  (-> w0
                  (entity/add-entity b)
                  (entity/update-entity a assoc :job (job/haul iid [6 0]))) ; a claims iid
          w2  (job/assign w1 (:id b) (job/haul iid [6 0]))]                 ; auto -> blocked
      (is (nil? (:job (entity/entity w2 (:id b)))) "b gets no job")
      (is (seq (log/of-type w2 :job/blocked))      "a :job/blocked entry was logged"))))

(deftest assign-forced-overrides-reservation
  (testing "a forced player order takes a claimed target anyway (player is boss)"
    (let [[w0 a iid] (setup [0 0] [3 0])
          b   (entity/make-pawn "b" [1 1])
          w1  (-> w0
                  (entity/add-entity b)
                  (entity/update-entity a assoc :job (job/haul iid [6 0])))
          w2  (job/assign w1 (:id b) (job/haul iid [6 0]) job/forced-by-player)]
      (is (= :haul (get-in (entity/entity w2 (:id b)) [:job :type]))
          "forced haul is assigned despite the existing claim"))))

(deftest two-haulers-one-item-no-double-grab
  (testing "two pawns ordered to haul the same item: exactly one carries it to
            the destination, the other fails — never a double-grab"
    (let [w0   (world/initial-world {:width 12 :height 12})
          a    (entity/make-pawn "a" [2 0])
          b    (entity/make-pawn "b" [4 0])     ; symmetric distance -> same-tick arrival
          it   (entity/make-item :wood [3 0])
          dest [6 0]
          w1   (-> w0
                   (entity/add-entity a) (entity/add-entity b) (entity/add-entity it)
                   (entity/update-entity (:id a) assoc :job (job/haul (:id it) dest))
                   (entity/update-entity (:id b) assoc :job (job/haul (:id it) dest)))
          wf   (drive-all w1 [(:id a) (:id b)] 100)
          ja   (:job (entity/entity wf (:id a)))
          jb   (:job (entity/entity wf (:id b)))
          item (entity/entity wf (:id it))]
      (is (= 1 (count (filter job/complete? [ja jb]))) "exactly one haul completes")
      (is (= 1 (count (filter job/failed?   [ja jb]))) "exactly one haul fails")
      (is (= dest (:pos item))    "item delivered once, at the destination")
      (is (nil? (:carried-by item)) "item is free at the end"))))

;; ---------------------------------------------------------------------------
;; Eat — a hungry pawn walks to a food item and consumes it, refilling :food.
;; ---------------------------------------------------------------------------

(defn- setup-food
  "12x12 world, a hungry pawn (food need 0.1) at pawn-pos, a :food item at
   food-pos. Returns [world pawn-id food-id]."
  [pawn-pos food-pos]
  (let [w    (world/initial-world {:width 12 :height 12})
        pawn (assoc (entity/make-pawn "eater" pawn-pos) :needs {:food 0.1})
        food (entity/make-item :food food-pos)]
    [(-> w (entity/add-entity pawn) (entity/add-entity food))
     (:id pawn) (:id food)]))

(deftest eat-happy-path
  (let [[w0 pid fid] (setup-food [0 0] [3 0])
        w1   (entity/update-entity w0 pid assoc :job (job/eat fid))
        wf   (drive w1 pid 200)
        pawn (entity/entity wf pid)]
    (is (job/complete? (:job pawn))          "eat job completes")
    (is (= 1.0 (get-in pawn [:needs :food])) "food need refilled to full")
    (is (nil? (entity/entity wf fid))        "the food item is consumed (removed)")
    (is (seq (log/of-type wf :job/eat))      "eating was logged")))

(deftest eat-consume-phase
  (testing "standing on the food, :consume removes it and refills the need"
    (let [[w0 pid fid] (setup-food [3 0] [3 0])
          w1   (entity/update-entity w0 pid assoc :job
                                     (assoc (job/eat fid) :phase :consume))
          w2   (job/advance w1 (entity/entity w1 pid))
          pawn (entity/entity w2 pid)]
      (is (job/complete? (:job pawn))          "job completes on consume")
      (is (= 1.0 (get-in pawn [:needs :food])) "need refilled")
      (is (nil? (entity/entity w2 fid))        "food removed from the world"))))

(deftest eat-fails-when-food-disappears
  (testing "food removed mid-eat fails the job rather than NPEing"
    (let [[w0 pid fid] (setup-food [0 0] [3 0])
          w1   (entity/update-entity w0 pid assoc :job (job/eat fid))
          w2   (entity/remove-entity w1 fid)
          w3   (job/advance w2 (entity/entity w2 pid))]
      (is (job/failed? (:job (entity/entity w3 pid))))
      (is (seq (log/of-type w3 :job/failed)) "failure was logged"))))
