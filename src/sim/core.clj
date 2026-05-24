(ns sim.core
  "Application entry point.

   The libGDX window runs on the MAIN thread (a hard macOS requirement, now
   uniform on all platforms). The simulation clock runs on its own thread.
   The optional dev REPL runs on a daemon background thread.

     clj -M:run        -> window only.
     clj -M:repl       -> window + a clojure.main REPL on a background thread
                          (the terminal you type into).
     clj -M:mac:run    -> as :run,  plus -XstartOnFirstThread (macOS).
     clj -M:mac:repl   -> as :repl, plus -XstartOnFirstThread (macOS).

   Closing the window ends the process (window-life = process-life)."
  (:require
   [clojure.main   :as main]
   [sim.clock      :as clock]
   [sim.render.gdx :as gdx]
   [sim.tile       :as tile]
   [sim.world      :as world])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- seed-world!
  "Reset the world to a small starter map with a few obstacles and pawns."
  []
  (world/reset-world! {:width 40 :height 20 :seed 42})
  ;; A simple wall down the middle so pathfinding has something to do.
  (swap! world/world
         (fn [w]
           (reduce (fn [w y] (update w :grid tile/set-tile 20 y :wall))
                   w
                   (range 5 15))))
  ;; A couple of pawns to look at.
  (world/spawn-pawn! "Alice" [5 5])
  (world/spawn-pawn! "Bob"   [10 8])
  (world/spawn-pawn! "Cleo"  [30 12]))

(defn- start-repl-thread!
  "Start a clojure.main REPL on a daemon background thread. The window owns
   the main thread, so the terminal prompt lives here instead. Reads the same
   stdin; switches into the `user` helper namespace before prompting."
  []
  (doto (Thread.
         ^Runnable (fn []
                     (main/repl :init (fn [] (require 'user) (in-ns 'user))))
         "sim-repl")
    (.setDaemon true)
    (.start)))

(defn -main
  [& args]
  (println "[sim] booting...")
  (seed-world!)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
            (fn []
              (println "\n[sim] shutting down...")
              (clock/stop!))))
  (clock/start!)                       ; engine thread comes up paused
  (when (some #{"repl"} args)
    (start-repl-thread!))              ; dev flavor: terminal REPL on a side thread
  ;; Run the window on THIS (main) thread. Blocks until the window closes.
  (gdx/run!)
  ;; Window closed -> fall through. Shut the agent pool so the JVM exits
  ;; promptly instead of lingering ~60s on the non-daemon future pool.
  (shutdown-agents)
  (println "[sim] bye."))
