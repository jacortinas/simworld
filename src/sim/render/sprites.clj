(ns sim.render.sprites
  "Sprite-sheet loading + region lookup for the 32rogues tileset.

   Each sheet (`32rogues/*.png`) is a grid of 32px cells. We:
     1. PRELOAD each sheet as a GPU `Texture` once (on the GL thread, from
        gdx `create`), because uploading to the GPU mid-frame would stutter.
     2. Slice cells into `TextureRegion`s on demand and CACHE them: a region
        is just (texture + source rect), cheap, but we still cache to avoid
        allocating one per tile per frame.

   Cell coords are 0-based [col row]. The sibling `.txt` files map the
   tileset's `<row>.<letter>` labels to contents (row 1-indexed, a=col 0).
   The cell refs now live in resources/defs/graphics.edn (the lookup tables
   that used to sit here were transcribed from 32rogues/tiles.txt and
   32rogues/rogues.txt).

   Asset path note: `Gdx.files.internal` resolves relative to the working
   directory on desktop, and we always launch from `sim/` (where deps.edn
   lives), so `\"32rogues/...\"` resolves. Move the sheets under resources/ and
   switch to `.classpath` if that ever stops holding."
  (:require
   [sim.defs :as defs]
   [sim.render.graphic :as graphic])
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Texture Texture$TextureFilter)
   (com.badlogic.gdx.graphics.g2d TextureRegion)))

(set! *warn-on-reflection* true)

(def ^:const sprite-size 32)

(def ^:private sheet-files
  {:tiles    "32rogues/tiles.png"
   :rogues   "32rogues/rogues.png"
   :items    "32rogues/items.png"
   :animated "32rogues/animated-tiles.png"})

;; GL resources. defonce so the atoms survive ns reload; load!/dispose! manage
;; their contents against the window's GL-context lifetime.
(defonce ^:private sheets       (atom {}))   ; sheet-key -> Texture
(defonce ^:private region-cache (atom {}))   ; [sheet col row] -> TextureRegion
(defonce ^:private images     (atom {}))   ; image path -> Texture
(defonce ^:private flip-cache (atom {}))   ; base TextureRegion -> flipped copy

(defn- graphic-image-paths
  "Every :image path referenced by the loaded graphic defs (top-level + facings)."
  []
  (->> (defs/ids :graphic)
       (map defs/graphic)
       (mapcat (fn [g] (keep :image (cons g (vals (:facings g))))))
       distinct))

(defn- load-images!
  "Load every graphic :image as a Nearest-filtered Texture, cached by path.
   GL thread only (called from load!)."
  []
  (reset! flip-cache {})
  (reset! images
          (into {}
                (map (fn [path]
                       (let [t (Texture. (.internal (Gdx/files) ^String path))]
                         (.setFilter t Texture$TextureFilter/Nearest
                                     Texture$TextureFilter/Nearest)
                         [path t])))
                (graphic-image-paths))))

(defn load!
  "Load every sheet as a Texture. MUST run on the GL thread (gdx `create`).
   Nearest filtering keeps the pixel art crisp instead of blurry under zoom."
  []
  (reset! region-cache {})
  (reset! sheets
          (into {}
                (map (fn [[k path]]
                       (let [t (Texture. (.internal (Gdx/files) ^String path))]
                         (.setFilter t Texture$TextureFilter/Nearest
                                     Texture$TextureFilter/Nearest)
                         [k t])))
                sheet-files))
  (load-images!))

(defn dispose!
  "Free the GPU textures. Call from gdx `dispose`."
  []
  (doseq [[_ ^Texture t] @sheets] (.dispose t))
  (reset! sheets {})
  (reset! region-cache {})
  (doseq [[_ ^Texture t] @images] (.dispose t))
  (reset! images {})
  (reset! flip-cache {}))

(defn region
  "Cached TextureRegion for cell (col,row) of `sheet-key`, or nil if the sheet is
   not loaded (degrade, matching graphic-region's contract). Source pixels are
   measured from the sheet's TOP-LEFT: (col*32, row*32)."
  ^TextureRegion [sheet-key col row]
  (let [k [sheet-key col row]]
    (or (@region-cache k)
        (when-let [^Texture t (@sheets sheet-key)]
          (let [r (TextureRegion. t
                                  (int (* (long col) sprite-size))
                                  (int (* (long row) sprite-size))
                                  (int sprite-size) (int sprite-size))]
            (swap! region-cache assoc k r)
            r)))))

(defn- image-region
  "Cached full-texture TextureRegion for a loaded image path, or nil if the path
   was not loaded (degrade, matching graphic-region's contract)."
  ^TextureRegion [path]
  (let [k [:image path]]
    (or (@region-cache k)
        (when-let [^Texture t (@images path)]
          (let [r (TextureRegion. t)]
            (swap! region-cache assoc k r)
            r)))))

(defn- flipped
  "A distinct horizontally-flipped copy of `r`, cached by `r`'s identity so the
   shared source region is never mutated."
  ^TextureRegion [^TextureRegion r]
  (or (@flip-cache r)
      (let [f (doto (TextureRegion. r) (.flip true false))]
        (swap! flip-cache assoc r f)
        f)))

(defn graphic-region
  "TextureRegion to draw for `graphic-def` at `facing` and wall-clock `now-ms`.
   Resolves the source + flip via sim.render.graphic/source-for, reads pixels from
   a sheet cell or a loaded image, steps an animated cell's column by time
   (sim.render.graphic/frame), and mirrors for a derived :right facing. Returns nil
   if the source can't resolve (the layer degrades). The param is `graphic-def` to
   avoid shadowing the `graphic` ns alias."
  ^TextureRegion [graphic-def facing now-ms]
  (let [[source flip?] (graphic/source-for graphic-def facing)
        base (cond
               (:cell source)
               (let [[sheet col row] (:cell source)
                     col (if-let [{:keys [frames fps]} (:anim graphic-def)]
                           (graphic/frame now-ms fps frames)
                           col)]
                 (region sheet col row))
               (:image source)
               (image-region (:image source)))]
    (when base
      (if flip? (flipped base) base))))

