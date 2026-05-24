(ns sim.core
  "Application entry point.

   `clj -M:run` lands here. We seed the world with a starter map, spawn a
   couple of pawns, install a shutdown hook so Ctrl-C exits cleanly, start
   the simulation loop (which boots PAUSED), and open the libGDX window. The
   main thread then blocks until the loop stops."
  (:require
   [sim.clock  :as clock]
   [sim.render.gdx :as gdx]
   [sim.tile       :as tile]
   [sim.world      :as world])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- seed-world!
  "Reset the world to a small starter map with a few obstacles and pawns."
  []
  (world/reset-world! {:width 40 :height 20 :seed 42})
  ;; A simple wall down the middle so pathfinding has something to do later.
  (swap! world/world
         (fn [w]
           (reduce (fn [w y] (update w :grid tile/set-tile 20 y :wall))
                   w
                   (range 5 15))))
  ;; A couple of pawns to look at.
  (world/spawn-pawn! "Alice" [5 5])
  (world/spawn-pawn! "Bob"   [10 8])
  (world/spawn-pawn! "Cleo"  [30 12]))

(defn -main
  [& _args]
  (println "[sim] booting...")
  (seed-world!)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
            (fn []
              (println "\n[sim] shutting down...")
              (clock/stop!))))
  (clock/start!)   ; engine thread comes up paused
  (gdx/start!)         ; open the window; press play (or space) to run
  ;; Park the main thread while the daemon loop runs. The shutdown hook
  ;; flips running? off and the future exits.
  (try
    (while (clock/running?*)
      (Thread/sleep 200))
    (catch InterruptedException _))
  ;; The sim loop runs on a `future`, whose thread pool is non-daemon and
  ;; keeps the JVM alive ~60s after the loop ends. Shut it down so closing
  ;; the window terminates the process promptly instead of hanging.
  (shutdown-agents)
  (println "[sim] bye."))
