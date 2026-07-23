(ns workflow-engine.worker.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.worker.handler :as handler]
            [workflow-engine.worker.retry :as retry]))

(deftest execute-with-timeout-test
  (testing "executes handler within timeout"
    (let [result (handler/execute-with-timeout (fn [_] {:ok true}) {} nil)]
      (is (= {:ok true} result))))
  (testing "returns timeout error when exceeded"
    (let [result (handler/execute-with-timeout (fn [_] (Thread/sleep 5000) {:ok true}) {} 100)]
      (is (:error result))
      (is (re-find #"timed out" (:error result))))))
