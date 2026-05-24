(ns sim.render.sprites
  "Sprite-sheet loading + region lookup for the 32rogues tileset.

   Each sheet (`32rogues/*.png`) is a grid of 32px cells. We:
     1. PRELOAD each sheet as a GPU `Texture` once (on the GL thread, from
        gdx `create`) — uploading to the GPU mid-frame would stutter.
     2. Slice cells into `TextureRegion`s on demand and CACHE them — a region
        is just (texture + source rect), cheap, but we still cache to avoid
        allocating one per tile per frame.

   Cell coords are 0-based [col row]. The sibling `.txt` files map the
   tileset's `<row>.<letter>` labels to contents (row 1-indexed, a=col 0);
   the lookup tables below were transcribed from them — see 32rogues/tiles.txt
   and 32rogues/rogues.txt.

   Asset path note: `Gdx.files.internal` resolves relative to the working
   directory on desktop, and we always launch from `sim/` (where deps.edn
   lives), so `\"32rogues/...\"` resolves. Move the sheets under resources/ and
   switch to `.classpath` if that ever stops holding."
  (:import
   (com.badlogic.gdx Gdx)
   (com.badlogic.gdx.graphics Texture Texture$TextureFilter)
   (com.badlogic.gdx.graphics.g2d TextureRegion)))

(set! *warn-on-reflection* true)

(def ^:const sprite-size 32)

(def ^:private sheet-files
  {:tiles  "32rogues/tiles.png"
   :rogues "32rogues/rogues.png"
   :items  "32rogues/items.png"})

;; GL resources. defonce so the atoms survive ns reload; load!/dispose! manage
;; their contents against the window's GL-context lifetime.
(defonce ^:private sheets       (atom {}))   ; sheet-key -> Texture
(defonce ^:private region-cache (atom {}))   ; [sheet col row] -> TextureRegion

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
                sheet-files)))

(defn dispose!
  "Free the GPU textures. Call from gdx `dispose`."
  []
  (doseq [[_ ^Texture t] @sheets] (.dispose t))
  (reset! sheets {})
  (reset! region-cache {}))

(defn region
  "Cached TextureRegion for cell (col,row) of `sheet-key`. Source pixels are
   measured from the sheet's TOP-LEFT: (col*32, row*32)."
  ^TextureRegion [sheet-key col row]
  (let [k [sheet-key col row]]
    (or (@region-cache k)
        (let [^Texture t (@sheets sheet-key)
              r (TextureRegion. t
                                (int (* (long col) sprite-size))
                                (int (* (long row) sprite-size))
                                (int sprite-size) (int sprite-size))]
          (swap! region-cache assoc k r)
          r))))

;; --- lookup tables: terrain keyword -> [col row] (see 32rogues/tiles.txt) ---
(def terrain->cell
  {:grass  [1 7]    ; 8.b  grass 1
   :dirt   [1 8]    ; 9.b  dirt 1
   :gravel [1 9]    ; 10.b stone floor 1
   :stone  [0 1]    ; 2.a  rough stone wall (top)
   :water  [0 12]   ; 13.a blank blue floor (no true water tile in this set)
   :wall   [0 2]})  ; 3.a  stone brick wall (top)

(def ^:private pawn-cell [1 6])   ; rogues 7.b farmer (scythe)

(defn terrain-region
  "Sprite region for a terrain keyword; falls back to grass if unmapped."
  ^TextureRegion [terrain-key]
  (let [[c r] (terrain->cell terrain-key (terrain->cell :grass))]
    (region :tiles c r)))

(defn pawn-region ^TextureRegion []
  (region :rogues (pawn-cell 0) (pawn-cell 1)))

;; --- item material -> [sheet col row] (cells span two sheets) ---
;; Material items read best from the tiles sheet's object rows (logs, rock);
;; food comes from the items sheet. See 32rogues/tiles.txt + items.txt.
(def material->cell
  {:wood  [:tiles 6 17]    ; tiles 18.g log pile
   :stone [:tiles 0 18]    ; tiles 19.a large rock 1
   :food  [:items 2 25]})  ; items 26.c apple

(defn item-region
  "Sprite region for an item material; falls back to the stone cell."
  ^TextureRegion [material]
  (let [[sheet c r] (material->cell material (material->cell :stone))]
    (region sheet c r)))
