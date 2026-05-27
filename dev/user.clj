(ns user
  "REPL convenience namespace. Loaded automatically when the :dev or :repl
   alias is active (because Clojure looks for `user.clj` on the classpath).

   Goal: minimum keystrokes to inspect and steer a running game from the
   REPL. Re-evaluate any `sim.*` namespace and the next tick uses the new
   code — that's the whole point of building in Clojure."
  (:require
   [clojure.inspector :as inspector]
   [clojure.pprint    :refer [pprint]]
   [clojure.repl      :refer [doc source dir]]
   [sim.world     :as world]
   [sim.app       :as app]
   [sim.clock :as clock]
   [sim.simulation :as simulation]
   [sim.pathfinding :as pathfinding]
   [sim.render    :as render]
   [sim.render.gdx :as gdx]
   [sim.ui-state  :as ui]
   [sim.tile      :as tile]
   [sim.entity    :as entity]
   [sim.events    :as events]
   [sim.job       :as job]
   [sim.log       :as slog]
   [sim.save      :as save]
   [sim.worldgen  :as worldgen]
   [gridnoise.noise :as noise]
   [gridnoise.grid  :as ngrid]
   [gridnoise.image :as nimage]))

(defn start! [] (clock/start!))   ; spawns the engine thread (boots PAUSED)
(defn stop!  [] (clock/stop!))

;; Pause control — the loop boots paused, so `(resume!)` is what actually
;; gets ticks flowing after `(start!)`. The in-window pause button and the
;; space key call the same toggle.
(defn pause!  [] (clock/pause!))
(defn resume! [] (clock/resume!))
(defn toggle-pause! [] (clock/toggle-pause!))

(defn status
  "Print engine + world state at a glance: loop liveness, pause state, tick,
   and entity counts. Handy when you're heads-down in the REPL and can't see
   the window's HUD. (Defined before go! so the cold-start compile resolves
   it — source order matters on the first load.)"
  []
  (let [w @world/world]
    (println (format "loop:%s  paused:%s  tick:%d  pawns:%d  items:%d  log:%d"
                     (if (clock/running?*) "running" "stopped")
                     (boolean (clock/paused?*))
                     (long (:clock w))
                     (count (entity/pawns w))
                     (count (filter :pos (entity/items w)))
                     (count (:log w)))))
  nil)

(defn go!
  "Start the SIM running: bring the clock up (idempotent) and resume ticking,
   then print status. The window is already open (it owns the main thread and
   launches with the process), so this no longer touches the window."
  []
  (clock/start!)
  (clock/resume!)
  (status)
  :go)

(defn halt!
  "Stop the SIM clock (pawns/jobs/needs freeze). The window stays open and
   keeps rendering. Use (quit!) to actually close the window / exit."
  []
  (clock/stop!)
  :halted)

(defn restart!
  "Respin the loop with freshly-loaded code, leaving the window OPEN. Use
   after editing run-loop! / drain-ticks / the tick rate — a live loop won't
   pick those up on its own, because the running thread is executing the old
   compiled loop body and only re-resolves run-loop! when start! spawns it
   anew. (Layers, commands, and the HUD hot-reload without this.)"
  []
  (clock/stop!)
  (clock/start!)
  (clock/resume!)
  (status)
  :restarted)

(defn quit!
  "Close the libGDX window, which ends the process (window-life = process-life
   under the unified main-thread model)."
  [] (gdx/stop!))

(defn camera
  "Current camera view-state {:x :y :zoom}."
  [] (ui/camera))

(defn look-at!
  "Center the camera on tile [tx ty] (tile coords, not pixels).
   Reads the live grid height to do the Y-flip. Uses gdx/tile-size as the
   single source of truth for pixels-per-tile."
  [tx ty]
  (let [ts gdx/tile-size
        h  (long (:height (:grid @world/world)))]
    (ui/center-camera! (* tx ts 1.0) (* (- h ty) ts 1.0))))

(defn zoom!
  "Set zoom directly. <1 zooms in, >1 zooms out."
  [z]
  (swap! ui/ui-state assoc-in [:camera :zoom] (double z)))

(defn debug!
  "Toggle the debug overlay (pathfinding routes; more later). Same flag the
   backtick key flips in-window. Returns the new on/off state."
  []
  (let [on? (ui/toggle-debug!)]
    (println (str "debug overlay " (if on? "ON" "OFF")))
    on?))

(defn snapshot
  "Return the live world value. Use this from the REPL — pprint it, drill
   in with get-in, hand it to clojure.inspector/inspect-tree, whatever."
  [] @world/world)

(defn reset-world!
  ([] (world/reset-world! {}))
  ([opts] (world/reset-world! opts)))

(defn generate-world!
  "Reset to a freshly GENERATED world (terrain + trees + haulable items) and
   print status. Pass {:seed n :width w :height h} to vary it.
     (generate-world! {:seed 42})  ;; then (spawn-pawn! ...) (go!)"
  ([] (generate-world! {}))
  ([opts]
   (world/reset-world! (assoc opts :generate? true))
   (status)
   :generated))

(defn spawn-pawn!
  ([pos] (world/spawn-pawn! pos))
  ([name pos] (world/spawn-pawn! name pos)))

(defn tick!
  "Manually step the simulation N ticks. Useful when the loop is stopped
   and you want to advance deterministically."
  ([] (tick! 1))
  ([n]
   (dotimes [_ n] (swap! world/world simulation/tick))
   (:clock @world/world)))

(defn save-game!
  ([] (save/save! @world/world))
  ([slot] (save/save! @world/world slot)))

(defn load-game!
  ([] (reset! world/world (save/load!)))
  ([slot] (reset! world/world (save/load! slot))))

(defn inspect
  "Open a Swing tree inspector on the world. Aaron Santos's killer move."
  []
  (inspector/inspect-tree @world/world))

(defn show-path
  "Print the current world with `path` overlaid. Marks the start as 'S',
   the goal as 'G', and intermediate tiles as '*'. Pawns still draw on top."
  [path]
  (let [path-vec  (vec path)
        overlay   (into {}
                        (map-indexed
                         (fn [i pos]
                           [pos (cond
                                  (zero? i)                       \S
                                  (= i (dec (count path-vec)))    \G
                                  :else                           \*)]))
                        path-vec)]
    (render/draw-with-overlay! @world/world overlay)))

(defn path-between
  "Convenience: pathfind from `start` to `goal` in the live world and
   visualize it. Returns the path."
  [start goal]
  (let [p (pathfinding/find-path @world/world start goal)]
    (if p
      (do (show-path p) p)
      (do (println "No path from" start "to" goal) nil))))

(defn assign-go-to!
  "Give pawn-id a go-to job targeting [x y]. Replaces any existing job."
  [pawn-id target]
  (swap! world/world job/assign pawn-id (job/go-to target) job/forced-by-player)
  :assigned)

(defn pawn-jobs
  "Map of pawn-id -> current job. Handy for inspection."
  []
  (into {}
        (keep (fn [p] (when (:job p) [(:id p) (:job p)])))
        (entity/pawns @world/world)))

(defn spawn-item!
  "Drop a new item of `material` on the ground at [x y]. Returns the item."
  [material pos]
  (let [item (entity/make-item material pos)]
    (swap! world/world entity/add-entity item)
    item))

(defn items-on-map
  "All items currently on the ground (not carried)."
  []
  (filter :pos (entity/items @world/world)))

(defn assign-haul!
  "Give `pawn-id` a haul job: pick up `item-id` and bring it to `destination`."
  [pawn-id item-id destination]
  (swap! world/world job/assign pawn-id (job/haul item-id destination) job/forced-by-player)
  :assigned)

;; ---------------------------------------------------------------------------
;; Debug log access.
;; The log lives at (:log @world/world) and is appended pure-ly by sim code.
;; ---------------------------------------------------------------------------

(defn log
  "All log entries in the live world (oldest first)."
  []
  (slog/entries @world/world))

(defn recent-log
  "Last `n` log entries (default 20). Use `(pprint (recent-log))` for nice
   formatting, or just `(recent-log)` for a raw vector."
  ([] (recent-log 20))
  ([n] (slog/recent @world/world n)))

(defn log-of
  "Log entries with `:type` matching `t` (keyword or set of keywords).
   Examples: (log-of :job/pickup), (log-of #{:job/complete :job/failed})."
  [t]
  (slog/of-type @world/world t))

(defn log-for-pawn
  "All log entries involving `pawn-id`."
  [pawn-id]
  (slog/for-pawn @world/world pawn-id))

(defn log-since
  "Entries since `tick` (inclusive). Handy after `(let [t (:clock (snapshot))])`
   ... do thing ... `(log-since t)`."
  [tick]
  (slog/since @world/world tick))

(defn clear-log!
  "Empty the log. Useful before reproducing a bug."
  []
  (swap! world/world slog/clear)
  :cleared)

;; ---------------------------------------------------------------------------
;; Screen transitions — match the in-window buttons so REPL flow mirrors UX.
;; ---------------------------------------------------------------------------

(defn menu!
  "Return to the main menu (clock stopped, world cleared). Equivalent to the
   pause-menu's 'Quit to Menu' button, but callable from any screen."
  []
  (app/quit-to-menu!)
  :main-menu)

(defn new-colony!
  "Kick off async worldgen → :play, just like clicking New Colony in the
   in-window menu. Useful when you want a fresh map in the REPL without
   reaching for the mouse."
  []
  (app/enter-worldgen!)
  :worldgenning)

(comment
  ;; Typical session in the REPL with screens:
  (new-colony!)        ;; worldgen → :play, three random starter pawns
  (resume!)            ;; clock comes up paused; this starts the ticks
  (status)             ;; loop/paused/tick/counts at a glance
  (tick! 100)          ;; manual step (works whether the loop runs or not)
  (pause!) (resume!)   ;; or click the in-window pause button / press Space
  (menu!)              ;; back to the main menu

  ;; Lower-level escape hatches still work for direct-poke development:
  (reset-world!)
  (spawn-pawn! "Dave" [3 3])
  (go!)                ;; bypasses the menu — clock straight up, no worldgen
  (save-game! "before-experiment")
  ;; …make changes, then: (require 'user :reload-all)…
  (load-game! "before-experiment")
  (stop!))
