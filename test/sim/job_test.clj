(ns sim.job-test
  "Tests for the job FSMs in sim.job — especially the 4-phase haul job, the
   most intricate pure logic in the sim. All headless: jobs are pure
   (world,pawn)->world, so we can drive them tick-by-tick and assert on the
   resulting world without any rendering."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.world  :as world]
   [sim.entity :as entity]
   [sim.tile   :as tile]
   [sim.log    :as log]
   [sim.job    :as job]))

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
