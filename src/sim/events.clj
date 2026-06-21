(ns sim.events
  "Event queue and processing pipeline.

   Events are plain maps with a :type key and arbitrary payload. They live in
   (:events world) and are drained each tick. Processing is a transducer
   pipeline so it composes cleanly and avoids intermediate allocation.

   Most gameplay should be expressible as 'tick reads world + events, produces
   new world + new events'."
  (:require
   [sim.log :as log]))

(set! *warn-on-reflection* true)

(defn event
  "Construct a new event. type is a keyword; extras are merged in."
  ([type] {:type type})
  ([type extras] (assoc extras :type type)))

(defn enqueue
  "Append an event to the world's event queue."
  [world event]
  (update world :events (fnil conj []) event))

(defn enqueue-many
  [world events]
  (update world :events (fnil into []) events))

(defn drain
  "Return [events-vec world']. The two parts are usually consumed together as
   events flow through the tick pipeline. When the queue is empty (the common
   case while no system enqueues), `world` is returned UNCHANGED, so an idle
   event pipeline costs zero per-tick allocation rather than re-assoc'ing a fresh
   empty vector every tick forever."
  [world]
  (let [evs (:events world)]
    (if (seq evs)
      [evs (assoc world :events [])]
      [[] world])))

;; ---------------------------------------------------------------------------
;; Default handler — multimethod so new event types are open for extension
;; (drop a defmethod in any namespace).
;; ---------------------------------------------------------------------------

(defmulti handle
  "Apply an event to the world. Default: log and ignore."
  (fn [_world event] (:type event)))

(defmethod handle :default
  [world event]
  (log/append world {:type :event/unhandled :event event}))

(defn apply-events
  "Fold a sequence of events into the world by dispatching each through
   `handle`. Every applied event leaves a log entry."
  [world events]
  (reduce
   (fn [w event]
     (-> w
         (handle event)
         (log/append {:type :event :event event})))
   world events))
