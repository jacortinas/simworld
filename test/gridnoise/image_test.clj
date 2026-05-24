(ns gridnoise.image-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [gridnoise.noise :as noise]
   [gridnoise.grid :as grid]
   [gridnoise.image :as image])
  (:import (java.io File)))

(deftest writes-a-nonempty-png
  (testing "a noise field renders to a PNG file on disk"
    (let [f   (noise/field {:seed 3})
          g   (grid/generate 16 16 (fn [x y] (f x y)))
          tmp (File/createTempFile "gridnoise" ".png")]
      (.deleteOnExit tmp)
      (image/spit-png! (.getPath tmp) g)
      (is (.exists tmp))
      (is (pos? (.length tmp))))))
