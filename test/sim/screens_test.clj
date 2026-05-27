(ns sim.screens-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.screens :as screens]
   [sim.screens.main-menu :as main-menu]
   [sim.screens.pause-menu :as pause-menu]))

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

(deftest pause-menu-button-rects-stacked
  (testing "three buttons stack vertically with Resume on top, Quit Game on bottom"
    (let [{:keys [resume quit-to-menu quit-game]}
          (pause-menu/button-rects 800 600)]
      (is (> (second resume)       (second quit-to-menu)))
      (is (> (second quit-to-menu) (second quit-game))))))

(deftest hovered-detects-rect-containment
  (testing "hovered? returns true when (x, y) is inside any rect, false otherwise"
    (is (= true  (screens/hovered? [[10 10 50 50]] 30 30)))
    (is (= true  (screens/hovered? [[10 10 50 50]] 10 10)))     ; on edge counts as inside (per inside?)
    (is (= false (screens/hovered? [[10 10 50 50]] 100 100)))
    (is (= true  (screens/hovered? [[10 10 50 50] [100 100 50 50]] 110 110)))
    (is (= false (screens/hovered? [] 30 30)))))
