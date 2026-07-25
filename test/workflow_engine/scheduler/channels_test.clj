(ns workflow-engine.scheduler.channels-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [workflow-engine.scheduler.channels :as channels]))

(deftest submit-and-read-work-test
  (testing "submit work and read from channel"
    (let [item {:type :test-work :data "hello"}]
      (channels/submit-work! item)
      (let [result (async/<!! channels/work-ch)]
        (is (= item result))))))

(deftest submit-and-read-result-test
  (testing "submit result and read from channel"
    (let [result {:status :ok}]
      (channels/submit-result! result)
      (let [r (async/<!! channels/result-ch)]
        (is (= result r))))))

(deftest submit-and-read-event-test
  (testing "submit event and read from channel"
    (let [event {:type :step-completed}]
      (channels/submit-event! event)
      (let [e (async/<!! channels/event-ch)]
        (is (= event e))))))
