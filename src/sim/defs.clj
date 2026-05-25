(ns sim.defs
  "The Def database: immutable game CONTENT, loaded from EDN at namespace-load,
   kept strictly separate from mutable game STATE (the world atom).

   Per docs/rimworld-engine-internals.md §3 this split is what makes our
   save/load a strict win over RimWorld's: defs NEVER enter the world map and
   are NEVER saved. State references a def by keyword (`:material :wood`, a
   `:grass` grid cell); the def is resolved here at *use* time.

   The registry is a `defonce` atom (like sim.schedule's system registry and
   the world atom): its identity survives `:reload`, but a top-level `(load!)`
   repopulates it from EDN on every load — so editing a def file and reloading
   picks the change up live. Defs being immutable-after-load is why reading them
   from this global inside the pure `tick` is morally a constant read, not
   mutable global state (see CLAUDE.md)."
  (:require
   [clojure.edn        :as edn]
   [clojure.java.io    :as io]
   [clojure.spec.alpha :as s]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Specs — one per def category. Checked at load; a bad entry fails fast with
;; an explain-str message naming the offending key.
;; ---------------------------------------------------------------------------

(s/def ::char      char?)
(s/def ::move-cost (s/and number? pos?))
(s/def ::passable? boolean?)
(s/def ::weight    (s/and number? pos?))
(s/def ::decay     (s/and number? #(<= 0.0 (double %) 1.0)))

(s/def ::terrain-entry  (s/keys :req-un [::move-cost ::passable?] :opt-un [::char]))
(s/def ::material-entry (s/keys :opt-un [::weight ::char]))
(s/def ::need-entry     (s/keys :req-un [::decay]))

(def ^:private entry-spec
  "Category -> the spec each of its entries must satisfy."
  {:terrain  ::terrain-entry
   :material ::material-entry
   :need     ::need-entry})

;; ---------------------------------------------------------------------------
;; Default content sources. Category -> ordered resource paths; later files win
;; via merge. The seq is the seam for future mod packs (append their dirs).
;; ---------------------------------------------------------------------------

(def default-sources
  {:terrain  ["defs/terrain.edn"]
   :material ["defs/materials.edn"]
   :need     ["defs/needs.edn"]})

(def ^:const ^:private default-decay
  "Fallback decay rate for a need lacking a def (keeps decay graceful, never 0)."
  0.0125)

;; ---------------------------------------------------------------------------
;; The registry. defonce so the atom identity survives reloads; the top-level
;; (load!) at the bottom repopulates it from EDN on every ns load.
;; ---------------------------------------------------------------------------

(defonce ^:private db (atom {}))

;; ---------------------------------------------------------------------------
;; Loading + validation
;; ---------------------------------------------------------------------------

(defn- read-resource
  "Read a single EDN map from a classpath resource. Throws if missing."
  [resource-path]
  (if-let [r (io/resource resource-path)]
    (edn/read-string (slurp r))
    (throw (ex-info (str "Def resource not found on classpath: " resource-path)
                    {:resource resource-path}))))

(defn- validate-category!
  "Validate every entry in `m` against its category spec. Returns `m` on
   success; throws ex-info (with the spec's explain-str) on the first bad entry."
  [category m]
  (let [spec (entry-spec category)]
    (doseq [[k entry] m]
      (when-not (s/valid? spec entry)
        (throw (ex-info (str "Invalid " category " def " k ": " (s/explain-str spec entry))
                        {:category category :key k :entry entry}))))
    m))

(defn load-sources!
  "Reset the registry from `sources`: a map of category -> seq of already-read
   entry-maps. Within a category the maps are merged left-to-right (later wins —
   the mod seam). Each merged entry is spec-validated; an invalid entry throws
   before the registry is touched. Returns the new registry."
  [sources]
  (reset! db
          (reduce-kv (fn [acc category maps]
                       (assoc acc category
                              (validate-category! category (apply merge maps))))
                     {} sources)))

(defn load!
  "Reset the registry from EDN resources. No-arg loads the bundled defs; the
   1-arg form takes a category -> resource-paths map (e.g. to add mod files).
   Returns the new registry."
  ([] (load! default-sources))
  ([path-map]
   (load-sources! (reduce-kv (fn [m category paths]
                               (assoc m category (map read-resource paths)))
                             {} path-map))))

;; ---------------------------------------------------------------------------
;; Lookups — use-time reads. Graceful fallback so a dangling reference degrades
;; rather than crashes (terrain mirrors the old sim.tile/terrain-info default).
;; ---------------------------------------------------------------------------

(defn ids
  "The set of def ids (keys) registered under `category`; empty set if unknown."
  [category]
  (set (keys (get @db category))))

(defn terrain
  "Terrain def for `k`; unknown keys fall back to the :grass entry."
  [k]
  (let [t (:terrain @db)]
    (get t k (get t :grass))))

(defn material
  "Material def for `k`, or nil."
  [k]
  (get-in @db [:material k]))

(defn need
  "Need def for `k`, or nil."
  [k]
  (get-in @db [:need k]))

(defn need-decay
  "Decay-per-rare-tick for need `k`; falls back to the default rate."
  ^double [k]
  (double (:decay (need k) default-decay)))

;; ---------------------------------------------------------------------------
;; Populate at load. Define-before-use: everything above must exist first
;; (clj -M compiles top-to-bottom in one pass — see CLAUDE.md cold-start note).
;; ---------------------------------------------------------------------------

(load!)
