(ns workflow-engine.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.core :as core]))

(deftest main-exists-test
  (testing "-main function exists and is callable"
    (is (fn? core/-main))))
