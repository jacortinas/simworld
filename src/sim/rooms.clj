(ns sim.rooms
  "Rooms: enclosed groups of regions, RimWorld's Room layer atop the region graph.

   A room is a maximal set of NORMAL (non-portal) regions connected by direct
   region-to-region edges, the flood STOPPING at portal (door) regions. Because a
   door is its own portal region (see sim.regions), the two sides of a doorway
   fall into DIFFERENT rooms even though they stay reachable THROUGH the door.
   That is exactly what 'a door encloses a room' means: a sealed area with a
   walk-through door is its own room, not fused with the space outside it.

   Enclosure: a room is OUTDOORS if any of its cells lies on the map edge, else
   ENCLOSED ('a building is identified'). This is the hook future per-room systems
   read; per docs/rimworld-engine-internals.md rooms are a PREREQUISITE for
   per-room temperature, not an optimization of it (temperature is per-room, never
   per-tile), so the room object reserves room for that state later.

   PURE projection of a region index (itself a pure projection of the PathGrid),
   memoized by region-index IDENTITY in a size-1 defonce atom: any building change
   yields a new region index, so a new identity recomputes and rooms CANNOT go
   stale. Depends only on sim.regions + sim.pathgrid; NOT in the world, NOT saved.

   Layering: PathGrid (costs) -> Regions (reachability) -> Rooms (enclosure). Each
   layer reads the one below by identity and memoizes; none writes the world."
  (:require
   [sim.pathgrid :as pathgrid]
   [sim.regions  :as regions]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Room labeling: connected components of the region graph with PORTAL nodes
;; removed. A flood over normal regions only (never stepping into a portal), so
;; the door regions are the cut points that separate rooms.
;; ---------------------------------------------------------------------------

(def ^:private not-found ::nf)

(defn- label-rooms
  "Flood the region graph over NORMAL regions only (never entering a portal
   region), assigning each connected group a dense room id. Room ids are
   scan-order canonical over the SORTED normal region ids, so the labeling is
   deterministic and history-independent (same graph -> same ids), matching the
   region/component id discipline. Returns {:region->room {rid -> room-id}
   :count n}."
  [graph portal-regions]
  (let [portal?  (fn [r] (contains? portal-regions r))
        labeled? (fn [acc r] (not (identical? not-found (get acc r not-found))))
        normal   (vec (sort (remove portal? (keys graph))))
        m        (count normal)]
    (loop [i 0, acc (transient {}), next-id 0]
      (if (< i m)
        (let [seed (nth normal i)]
          (if (labeled? acc seed)
            (recur (inc i) acc next-id)
            ;; DFS from seed over non-portal neighbors; flood order is irrelevant
            ;; (every reachable normal region gets the seed's scan-order room id).
            (let [acc' (loop [stack [seed], acc (assoc! acc seed next-id)]
                         (if (seq stack)
                           (let [r      (peek stack)
                                 stack' (pop stack)
                                 nbrs   (->> (:neighbors (graph r))
                                             (remove portal?)
                                             (remove #(labeled? acc %)))]
                             (recur (into stack' nbrs)
                                    (reduce (fn [a nb] (assoc! a nb next-id)) acc nbrs)))
                           acc))]
              (recur (inc i) acc' (inc next-id)))))
        {:region->room (persistent! acc) :count next-id}))))

;; ---------------------------------------------------------------------------
;; Per-room stats: cell count + whether the room touches the map edge. One
;; O(cells) scan over cell->region; portal (door) cells map to no room and are
;; skipped, so a door does not count toward either adjacent room's area.
;; ---------------------------------------------------------------------------

(defn- room-stats
  "Scan cell->region once, accumulating per-room cell counts and an edge-touch
   flag (any cell on the map border). Returns [^longs counts ^booleans edges],
   both indexed by room id."
  [region-index region->room ^long n-rooms]
  (let [width  (long (:width region-index))
        height (long (:height region-index))
        ^ints c->r (:cell->region region-index)
        n      (* width height)
        counts (long-array n-rooms)
        edges  (boolean-array n-rooms)]
    (dotimes [i n]
      (let [r (aget c->r i)]
        (when (>= r 0)
          (when-let [room (get region->room (long r))]   ; nil for portal/no-room cells
            (let [room (int room)
                  x    (rem i width)
                  y    (quot i width)]
              (aset counts room (inc (aget counts room)))
              (when (or (zero? x) (== x (dec width)) (zero? y) (== y (dec height)))
                (aset edges room true)))))))
    [counts edges]))

(defn- build-rooms-map
  "Assemble {room-id -> room object} from the labeling and stats. A room object:
   {:id :regions :cell-count :touches-edge? :enclosed?}. :enclosed? is the
   negation of :touches-edge? (a room sealed off from the map edge is indoors)."
  [region->room ^longs counts ^booleans edges ^long n-rooms]
  (let [room->regions (reduce-kv (fn [m rid room] (update m (int room) (fnil conj #{}) rid))
                                 {} region->room)]
    (persistent!
     (reduce (fn [m room-id]
               (assoc! m room-id
                       {:id            room-id
                        :regions       (get room->regions room-id #{})
                        :cell-count    (aget counts room-id)
                        :touches-edge? (aget edges room-id)
                        :enclosed?     (not (aget edges room-id))}))
             (transient {})
             (range n-rooms)))))

;; Size-1 memo keyed by region-index identity. A new region index (any building
;; change) is a new key -> recompute, so rooms cannot go stale, mirroring how
;; sim.regions memoizes on PathGrid identity one layer down.
(defonce ^:private cache (atom nil))     ; {:region-index ri :rooms rooms-index}

(defn of-index
  "Rooms index for a region index, memoized by IDENTITY. Shape:
     {:region-index ri
      :region->room {rid -> room-id}     ; normal regions only
      :rooms        {room-id -> room}
      :count        n}"
  [region-index]
  (let [c @cache]
    (if (and c (identical? region-index (:region-index c)))
      (:rooms c)
      (let [{:keys [region->room count]} (label-rooms (:regions region-index)
                                                       (:portal-regions region-index))
            [counts edges] (room-stats region-index region->room count)
            rooms-index {:region-index region-index
                         :region->room region->room
                         :rooms        (build-rooms-map region->room counts edges count)
                         :count        count}]
        (reset! cache {:region-index region-index :rooms rooms-index})
        rooms-index))))

(defn for-world
  "Rooms index for `world`: composes PathGrid -> regions -> rooms, each memoized."
  [world]
  (of-index (regions/of-pathgrid (pathgrid/for-world world))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn room-at
  "Room id at (x, y), or -1 if the cell is impassable, out of bounds, or a door
   (a portal cell belongs to no room)."
  ^long [rooms-index x y]
  (let [r (regions/region-at (:region-index rooms-index) x y)]
    (if (>= r 0)
      (long (get (:region->room rooms-index) (long r) -1))
      -1)))

(defn room
  "Room object for `room-id`, or nil."
  [rooms-index room-id]
  (get (:rooms rooms-index) room-id))

(defn count-rooms
  "Number of rooms (normal-region groups) in the index."
  ^long [rooms-index]
  (:count rooms-index))

(defn enclosed?
  "True iff (x, y) lies in an ENCLOSED room (one that does not touch the map
   edge). False for outdoor rooms, doors, walls, and out of bounds."
  [rooms-index x y]
  (let [rid (room-at rooms-index x y)]
    (and (>= rid 0)
         (boolean (:enclosed? (room rooms-index rid))))))
