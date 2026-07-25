(ns workflow-engine.api.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.api.server :as server]))

(deftest stop-server-when-nil-test
  (testing "stop-server! is safe when no server running"
    (reset! server/server nil)
    (server/stop-server!)
    (is (nil? @server/server))))
