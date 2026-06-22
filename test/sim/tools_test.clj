(ns sim.tools-test
  "The interaction-tool registry is plain data; these tests pin its shape so a
   new tool entry stays well-formed. The handler fns themselves mutate the world
   atom via sim.command, so they are exercised through command-test, not invoked
   here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [sim.tools    :as tools]))

(deftest select-is-not-a-tool
  (testing "the default :select mode has no placement tool (input falls through)"
    (is (nil? (tools/tool :select)))
    (is (nil? (tools/tool nil)))))

(deftest build-is-a-click-tool
  (let [t (tools/tool :build)]
    (is (some? t))
    (is (false? (:drag? t))   "build places on a single click, not a drag")
    (is (fn? (:on-click t))   "a click tool exposes :on-click")))

(deftest build-door-is-a-click-tool
  (let [t (tools/tool :build-door)]
    (is (some? t))
    (is (false? (:drag? t))   "door placement is a single click, like walls")
    (is (fn? (:on-click t))   "a click tool exposes :on-click")))

(deftest zone-is-a-drag-tool
  (let [t (tools/tool :zone-stockpile)]
    (is (some? t))
    (is (true? (:drag? t))    "zoning paints a rectangle via drag")
    (is (fn? (:on-commit t))  "a drag tool exposes :on-commit")))

(deftest every-tool-is-well-formed
  (testing "each registered tool is exactly one of click (:on-click) or drag (:on-commit)"
    (doseq [[mode t] tools/by-mode]
      (if (:drag? t)
        (is (fn? (:on-commit t)) (str mode " is a drag tool, needs :on-commit"))
        (is (fn? (:on-click t))  (str mode " is a click tool, needs :on-click"))))))
