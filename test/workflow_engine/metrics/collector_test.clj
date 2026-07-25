(ns workflow-engine.metrics.collector-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.metrics.collector :as metrics]))

(defn cleanup-fixture [f]
  (metrics/clear-metrics!)
  (try
    (f)
    (finally
      (metrics/clear-metrics!))))

(use-fixtures :each cleanup-fixture)

(deftest counter-test
  (testing "increments and reads counter"
    (metrics/inc-counter! :requests)
    (metrics/inc-counter! :requests)
    (is (= 2 (metrics/get-counter :requests))))
  (testing "decrements counter"
    (metrics/inc-counter! :active)
    (metrics/inc-counter! :active)
    (metrics/dec-counter! :active)
    (is (= 1 (metrics/get-counter :active))))
  (testing "returns 0 for unknown counter"
    (is (= 0 (metrics/get-counter :unknown)))))

(deftest gauge-test
  (testing "sets and reads gauge"
    (metrics/set-gauge! :connections 42)
    (is (= 42 (metrics/get-gauge :connections)))))

(deftest histogram-test
  (testing "records values and computes stats"
    (metrics/record-histogram! :latency 100)
    (metrics/record-histogram! :latency 200)
    (metrics/record-histogram! :latency 300)
    (let [stats (metrics/get-histogram :latency)]
      (is (= 3 (:count stats)))
      (is (= 600 (:sum stats)))
      (is (= 200 (:mean stats)))
      (is (= 100 (:min stats)))
      (is (= 300 (:max stats))))))

(deftest workflow-metrics-test
  (testing "workflow metric helpers"
    (metrics/record-workflow-started!)
    (metrics/record-workflow-started!)
    (metrics/record-workflow-completed!)
    (metrics/record-workflow-failed!)
    (is (= 2 (metrics/get-counter :workflows-started)))
    (is (= 1 (metrics/get-counter :workflows-completed)))
    (is (= 1 (metrics/get-counter :workflows-failed)))))

(deftest snapshot-test
  (testing "returns full metrics snapshot"
    (metrics/inc-counter! :req)
    (metrics/set-gauge! :g 1)
    (metrics/record-histogram! :h 50)
    (let [snap (metrics/snapshot)]
      (is (map? snap))
      (is (contains? snap :counters))
      (is (contains? snap :gauges))
      (is (contains? snap :histograms)))))
