(ns workflow-engine.scheduler.channels-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [workflow-engine.scheduler.channels :as channels]))

(deftest create-channels-test
  (testing "creates channels map with all channel types"
    (let [ch (channels/create-channels)]
      (is (some? (:work-ch ch)))
      (is (some? (:result-ch ch)))
      (is (some? (:event-ch ch))))))

(deftest close-channels-test
  (testing "closes all channels in the map"
    (let [ch (channels/create-channels)]
      (channels/close-channels! ch)
      (is (nil? (async/<!! (:work-ch ch))) "work-ch should be closed")
      (is (nil? (async/<!! (:result-ch ch))) "result-ch should be closed")
      (is (nil? (async/<!! (:event-ch ch))) "event-ch should be closed"))))

(deftest submit-and-read-work-test
  (testing "submit work and read from channel"
    (let [ch (channels/create-channels)
          item {:type :test-work :data "hello"}]
      (channels/submit-work! ch item)
      (let [result (async/<!! (:work-ch ch))]
        (is (= item result)))
      (channels/close-channels! ch))))

(deftest submit-and-read-result-test
  (testing "submit result and read from channel"
    (let [ch (channels/create-channels)
          result {:status :ok}]
      (channels/submit-result! ch result)
      (let [r (async/<!! (:result-ch ch))]
        (is (= result r)))
      (channels/close-channels! ch))))

(deftest submit-and-read-event-test
  (testing "submit event and read from channel"
    (let [ch (channels/create-channels)
          event {:type :step-completed}]
      (channels/submit-event! ch event)
      (let [e (async/<!! (:event-ch ch))]
        (is (= event e)))
      (channels/close-channels! ch))))

(deftest legacy-submit-work-test
  (testing "1-arg submit-work! uses global work-ch"
    (let [item {:type :legacy :data "test"}]
      (channels/submit-work! item)
      (let [result (async/<!! channels/work-ch)]
        (is (= item result))))))

(deftest legacy-submit-result-test
  (testing "1-arg submit-result! uses global result-ch"
    (let [result {:status :ok}]
      (channels/submit-result! result)
      (let [r (async/<!! channels/result-ch)]
        (is (= result r))))))

(deftest legacy-submit-event-test
  (testing "1-arg submit-event! uses global event-ch"
    (let [event {:type :test}]
      (channels/submit-event! event)
      (let [e (async/<!! channels/event-ch)]
        (is (= event e))))))

(deftest close-all-test
  (testing "close-all! closes all global channels"
    (let [item {:type :close-test}]
      (channels/submit-work! item)
      (let [r (async/<!! channels/work-ch)]
        (is (= item r)))
      (channels/close-all!))))
