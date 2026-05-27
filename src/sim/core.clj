(ns sim.core
  "Application entry point.

   The libGDX window runs on the MAIN thread (a hard macOS requirement, now
   uniform on all platforms). The simulation clock runs on its own thread,
   started by sim.app/enter-play! (not here). The optional dev REPL runs on
   a daemon background thread.

     clj -M:run        -> window only.
     clj -M:repl       -> window + a clojure.main REPL on a background thread
                          (the terminal you type into).
     clj -M:mac:run    -> as :run,  plus -XstartOnFirstThread (macOS).
     clj -M:mac:repl   -> as :repl, plus -XstartOnFirstThread (macOS).

   Closing the window ends the process (window-life = process-life)."
  (:require
   [clojure.main   :as main]
   [sim.clock      :as clock]
   [sim.render.gdx :as gdx])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn- start-repl-thread!
  "Start a clojure.main REPL on a daemon background thread."
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
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable
            (fn []
              (println "\n[sim] shutting down...")
              (clock/stop!))))
  (when (some #{"repl"} args)
    (start-repl-thread!))
  ;; Run the window on THIS (main) thread. Blocks until the window closes.
  ;; App boots at :main-menu (per sim.app/initial-app); clock stays stopped
  ;; until the user clicks New Colony (which triggers enter-play! → clock/start!).
  (gdx/run!)
  (shutdown-agents)
  (println "[sim] bye."))
