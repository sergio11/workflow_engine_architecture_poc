(ns workflow-engine.config.system-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [workflow-engine.config.system :as system]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.events.publisher :as pub]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.api.server :as server]
            [workflow-engine.scheduler.core :as scheduler]))

(defn cleanup-fixture [f]
  (pub/clear-subscribers!)
  (metrics/clear-metrics!)
  (reset! server/server nil)
  (try
    (f)
    (finally
      (pub/clear-subscribers!)
      (metrics/clear-metrics!)
      (reset! server/server nil))))

(use-fixtures :each cleanup-fixture)

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

(deftest server-init-test
  (testing "init server component starts server with datasource"
    (with-redefs [server/start-server! (fn [ds] {:server :mock-server})]
      (let [result (ig/init-key :workflow-engine/server {:datasource :test-ds})]
        (is (= {:server :mock-server} result))))))

(deftest server-halt-test
  (testing "halt-key! stops server"
    (let [stopped? (atom false)
          mock-server (proxy [org.eclipse.jetty.server.Server] []
                        (stop [this] (reset! stopped? true)))]
      (ig/halt-key! :workflow-engine/server {:server mock-server})
      (is (true? @stopped?)))))

(deftest server-halt-nil-test
  (testing "halt-key! with nil server does nothing"
    (ig/halt-key! :workflow-engine/server {:server nil})
    (is true "no error with nil server")))

(deftest system-config-test
  (testing "system-config has correct keys and dependencies"
    (is (contains? system/system-config :workflow-engine/db))
    (is (contains? system/system-config :workflow-engine/publisher))
    (is (contains? system/system-config :workflow-engine/metrics))
    (is (contains? system/system-config :workflow-engine/server))
    (let [server-config (get system/system-config :workflow-engine/server)]
      (is (some? (:datasource server-config))))))

(deftest start-and-stop-system-test
  (testing "start-system! and stop-system! work with mocked server"
    (let [mock-jetty (proxy [org.eclipse.jetty.server.Server] [])]
      (with-redefs [server/start-server! (fn [ds] {:server mock-jetty})]
        (let [sys (system/start-system!)]
          (is (some? sys))
          (is (some? (get sys :workflow-engine/db)))
          (is (some? (get sys :workflow-engine/publisher)))
          (is (some? (get sys :workflow-engine/metrics)))
          (is (some? (get sys :workflow-engine/server)))
          (system/stop-system! sys)
          (is true "system stopped without error"))))))

(deftest db-init-from-env-test
  (testing "init db uses from-env when no db-config provided"
    (with-redefs [config/from-env (fn [] (config/test-config))]
      (let [datasource (ig/init-key :workflow-engine/db {})]
        (is (some? datasource))
        (ig/halt-key! :workflow-engine/db datasource)))))

(deftest scheduler-init-test
  (testing "init scheduler component with datasource"
    (let [datasource (db/create-datasource (config/test-config))
          scheduler-handle (ig/init-key :workflow-engine/scheduler {:datasource datasource})]
      (is (some? scheduler-handle))
      (is (contains? scheduler-handle :workers))
      (is (contains? scheduler-handle :processor))
      (ig/halt-key! :workflow-engine/scheduler scheduler-handle)
      (db/close-datasource! datasource))))

(deftest scheduler-halt-nil-test
  (testing "halt-key! with nil scheduler does nothing"
    (ig/halt-key! :workflow-engine/scheduler nil)
    (is true "no error with nil scheduler")))

(deftest system-config-scheduler-test
  (testing "system-config includes scheduler with datasource dependency"
    (is (contains? system/system-config :workflow-engine/scheduler))
    (let [scheduler-config (get system/system-config :workflow-engine/scheduler)]
      (is (some? (:datasource scheduler-config))))))
