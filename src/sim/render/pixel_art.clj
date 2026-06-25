(ns sim.render.pixel-art
  "Author pixel-art sprites as text grids -> PNG (Java2D, no GL). A sprite is a
   vector of strings; each char maps to a palette color [r g b] / [r g b a] (0-255),
   and a space or an unmapped char is transparent. Each char becomes a `scale` x
   `scale` block, so a 16x16 grid at scale 2 is a 32x32 sprite (the tile size).

   This is the asset-authoring counterpart to sim.render.snapshot: hand-made
   sprites for things the 32rogues sheet does not cover, loaded through the graphic
   defs (resources/defs/graphics.edn :image path). The SOURCE OF TRUTH is the grid
   in `sprites` here (tracked); the PNG is a generated, committed artifact the game
   loads. Edit a sprite + (regenerate!) to rewrite its PNG; the def needs no code
   change (point its :image at the path)."
  (:import
   (java.awt Color)
   (java.awt.image BufferedImage)
   (java.io File)
   (javax.imageio ImageIO)))

(set! *warn-on-reflection* true)

(defn rows->image
  "Render text `rows` to a BufferedImage. Width = the longest row; each char is a
   `scale` x `scale` block colored via `palette` (char -> [r g b] or [r g b a],
   0-255). A space or a char absent from the palette is transparent."
  ^BufferedImage [rows palette scale]
  (let [cols  (long (reduce max 0 (map count rows)))
        nrows (long (count rows))
        s     (long scale)
        img   (BufferedImage. (* cols s) (* nrows s) BufferedImage/TYPE_INT_ARGB)
        g     (.createGraphics img)]
    (dotimes [y nrows]
      (let [^String row (nth rows y)]
        (dotimes [x (count row)]
          (when-let [[r gg b a] (palette (.charAt row x))]
            (.setColor g (Color. (int r) (int gg) (int b) (int (or a 255))))
            (.fillRect g (* x s) (* y s) s s)))))
    (.dispose g)
    img))

(defn write-sprite!
  "Render `rows`/`palette` at `scale` and write a PNG to `path` (creating parent
   dirs). Returns the absolute path."
  [rows palette scale path]
  (let [f (File. ^String path)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (ImageIO/write (rows->image rows palette scale) "png" f)
    (.getAbsolutePath f)))

;; ---------------------------------------------------------------------------
;; Authored sprites. Each is a 16x16 grid at scale 2 (a 32x32 PNG, one tile).
;; ---------------------------------------------------------------------------

(def ^:private door-palette
  {\F [58 36 18]      ; dark frame / outline
   \W [150 98 52]     ; door wood
   \w [116 72 36]     ; recessed / shadowed wood
   \h [184 130 80]    ; lit wood edge
   \o [226 200 96]})  ; brass handle

;; A face-on wooden door: full frame, a lit left edge, two recessed panels, and a
;; brass handle on the right between them.
(def ^:private door-rows
  ["FFFFFFFFFFFFFFFF"
   "FhWWWWWWWWWWWWwF"
   "FhWwwwwwwwwwwwwF"
   "FhWwhhhhhhhhwwwF"
   "FhWwhWWWWWWhwwwF"
   "FhWwhWWWWWWhwwwF"
   "FhWwhhhhhhhhwwwF"
   "FhWWWWWWWWWWowwF"
   "FhWWWWWWWWWWowwF"
   "FhWwhhhhhhhhwwwF"
   "FhWwhWWWWWWhwwwF"
   "FhWwhWWWWWWhwwwF"
   "FhWwhWWWWWWhwwwF"
   "FhWwhhhhhhhhwwwF"
   "FhWwwwwwwwwwwwwF"
   "FFFFFFFFFFFFFFFF"])

(def sprites
  "Registry of hand-authored sprites: name -> {:rows :palette :scale :path}. Run
   (regenerate!) to (re)write every PNG. Add a sprite here, then point its graphic
   def's :image at the path."
  {:door {:rows door-rows :palette door-palette :scale 2 :path "graphics/door.png"}})

(defn regenerate!
  "Write every registered sprite's PNG. Returns the paths written. Run from the
   project root (paths are relative, matching where the game loads assets)."
  []
  (mapv (fn [[_ {:keys [rows palette scale path]}]]
          (write-sprite! rows palette scale path))
        sprites))
