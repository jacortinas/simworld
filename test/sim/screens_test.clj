(ns sim.screens-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.screens :as screens]
   [sim.screens.main-menu :as main-menu]))

(deftest draw-screen-multimethod-exists
  (testing "draw-screen is a multimethod dispatching on the screen keyword"
    (is (instance? clojure.lang.MultiFn screens/draw-screen))))

(deftest draw-screen-default-throws
  (testing "calling draw-screen with an unregistered keyword throws"
    (is (thrown? IllegalArgumentException
                 (screens/draw-screen :nonexistent-screen {})))))

(deftest main-menu-button-rects-pure
  (testing "button-rects produces sensible rects for an 800x600 viewport"
    (let [{:keys [new-colony quit]} (main-menu/button-rects 800 600)]
      (is (= (count new-colony) 4))
      (is (= (count quit) 4))
      ;; New Colony sits above Quit (higher y in UI-cam space)
      (is (> (second new-colony) (second quit))))))
