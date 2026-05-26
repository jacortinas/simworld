(ns sim.screens-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.screens :as screens]))

(deftest draw-screen-multimethod-exists
  (testing "draw-screen is a multimethod dispatching on the screen keyword"
    (is (instance? clojure.lang.MultiFn screens/draw-screen))))

(deftest draw-screen-default-throws
  (testing "calling draw-screen with an unregistered keyword throws"
    (is (thrown? IllegalArgumentException
                 (screens/draw-screen :nonexistent-screen {})))))
