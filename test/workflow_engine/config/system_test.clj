(ns workflow-engine.config.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [workflow-engine.config.system :as system]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.events.publisher :as pub]
            [workflow-engine.metrics.collector :as metrics]))

(deftest db-init-and-halt-test
  (testing "init and halt db component"
    (let [datasource (ig/init-key :workflow-engine/db {:db-config (config/test-config)})]
      (is (some? datasource))
      (ig/halt-key! :workflow-engine/db datasource)
      (is true "datasource closed without error"))))

(deftest publisher-init-test
  (testing "init publisher component"
    (let [pub-val (ig/init-key :workflow-engine/publisher {})]
      (is (some? pub-val)))))

(deftest publisher-halt-test
  (testing "halt-key! clears subscribers"
    (pub/subscribe! :step-started (fn [_]))
    (is (pos? (pub/subscriber-count)))
    (ig/halt-key! :workflow-engine/publisher nil)
    (is (= 0 (pub/subscriber-count)))))

(deftest metrics-init-test
  (testing "init metrics component"
    (let [m (ig/init-key :workflow-engine/metrics {})]
      (is (some? m)))))

(deftest metrics-halt-test
  (testing "init clears existing metrics"
    (metrics/inc-counter! :test-counter)
    (ig/init-key :workflow-engine/metrics {})
    (is (= 0 (metrics/get-counter :test-counter)))))
