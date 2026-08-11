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

(deftest histogram-percentiles-test
  (testing "computes percentiles for larger dataset"
    (doseq [v (range 1 101)]
      (metrics/record-histogram! :lat v))
    (let [stats (metrics/get-histogram :lat)]
      (is (= 100 (:count stats)))
      (is (= 5050 (:sum stats)))
      (is (== 50.5 (:mean stats)))
      (is (= 1 (:min stats)))
      (is (= 100 (:max stats)))
      (is (some? (:p50 stats)))
      (is (some? (:p95 stats)))
      (is (some? (:p99 stats))))))

(deftest record-step-retry-test
  (testing "increments step-retries counter"
    (metrics/record-step-retry!)
    (metrics/record-step-retry!)
    (is (= 2 (metrics/get-counter :step-retries)))))

(deftest update-active-executions-test
  (testing "sets active-executions gauge"
    (metrics/update-active-executions! 5)
    (is (= 5 (metrics/get-gauge :active-executions)))
    (metrics/update-active-executions! 0)
    (is (= 0 (metrics/get-gauge :active-executions)))))

(deftest get-histogram-nil-test
  (testing "returns nil for non-existent histogram"
    (is (nil? (metrics/get-histogram :nonexistent)))))

(deftest record-histogram-truncation-test
  (testing "truncates to last 1000 values when exceeded"
    (doseq [v (range 1 1101)]
      (metrics/record-histogram! :trunc v))
    (let [stats (metrics/get-histogram :trunc)]
      (is (= 1000 (:count stats)))
      (is (= 100 (:min stats)))
      (is (= 1100 (:max stats))))))

(deftest histogram-single-value-test
  (testing "single value histogram has matching stats"
    (metrics/record-histogram! :single 42)
    (let [stats (metrics/get-histogram :single)]
      (is (= 1 (:count stats)))
      (is (= 42 (:sum stats)))
      (is (= 42 (:mean stats)))
      (is (= 42 (:min stats)))
      (is (= 42 (:max stats)))
      (is (= 42 (:p50 stats)))
      (is (= 42 (:p95 stats)))
      (is (= 42 (:p99 stats))))))

(deftest record-step-execution-test
  (testing "increments steps-executed and records histogram"
    (metrics/record-step-execution! 100)
    (metrics/record-step-execution! 200)
    (is (= 2 (metrics/get-counter :steps-executed)))
    (let [stats (metrics/get-histogram :step-duration)]
      (is (= 2 (:count stats)))
      (is (= 300 (:sum stats))))))
