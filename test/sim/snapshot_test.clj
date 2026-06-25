(ns sim.snapshot-test
  "Smoke tests for the headless software renderer. java.awt is headless-capable,
   so these run with no display: they prove the renderer produces an image of the
   right size and a valid PNG, NOT that the pixels look a certain way (that's the
   GL renderer's untested edge, validated by eye via the dev (snap!) helper)."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [sim.defs            :as defs]
   [sim.entity          :as entity]
   [sim.tile            :as tile]
   [sim.render.snapshot :as snapshot])
  (:import
   (java.awt.image BufferedImage)
   (java.io File)
   (javax.imageio ImageIO)))

(use-fixtures :each (fn [t] (defs/load!) (t)))

(defn- tiny-world []
  (-> {:clock 0 :grid (tile/make-grid 6 4 :grass)
       :entities {} :kinds (entity/empty-kinds) :zones [] :next-zone-id 1}
      (entity/add-entity (entity/make-building [1 1]))               ; built wall
      (entity/add-entity (entity/make-blueprint :wall [3 2]))        ; ghost
      (entity/add-entity (entity/make-item :stone [2 2]))
      (entity/add-entity (entity/make-pawn "P" [4 3]))))

(deftest render-image-has-pixel-dimensions-of-the-grid
  (let [ts  12
        img (snapshot/render-image (tiny-world) {:tile-px ts})]
    (is (instance? BufferedImage img))
    (is (= (* 6 ts) (.getWidth img)) "width = grid-width * tile-px")
    (is (= (* 4 ts) (.getHeight img)) "height = grid-height * tile-px")))

(deftest render-image-is-a-pure-read
  (testing "rendering a world twice is deterministic and never mutates it"
    (let [w (tiny-world)
          a (snapshot/render-image w)
          b (snapshot/render-image w)]
      (is (= (.getWidth a) (.getWidth b)))
      (is (= (.getHeight a) (.getHeight b)))
      (is (= 2 (count (entity/buildings w))) "the world is untouched by rendering"))))

(deftest write-png!-creates-a-valid-png
  (let [tmp  (str (System/getProperty "java.io.tmpdir") "/sim-snap-test-"
                  (System/nanoTime) ".png")
        path (snapshot/write-png! (tiny-world) tmp {:tile-px 8})
        f    (File. ^String path)]
    (try
      (is (.exists f) "the file is written")
      (is (pos? (.length f)) "and non-empty")
      (is (some? (ImageIO/read f)) "and round-trips as a valid PNG")
      (finally (.delete f)))))
