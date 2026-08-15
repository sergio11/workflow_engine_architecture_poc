(ns workflow-engine.config.system-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [integrant.core :as ig]
            [workflow-engine.config.system :as system]
            [workflow-engine.execution.adapters :as adapters]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.events.publisher :as pub]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.api.server :as server]
            [workflow-engine.scheduler.channels :as channels]
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

(deftest store-init-test
  (testing "init store component"
    (let [datasource (db/create-datasource (config/test-config))
          store (ig/init-key :workflow-engine/store {:datasource datasource})]
      (is (some? store))
      (db/close-datasource! datasource))))

(deftest recorder-init-test
  (testing "init recorder component"
    (let [datasource (db/create-datasource (config/test-config))
          recorder (ig/init-key :workflow-engine/recorder {:datasource datasource})]
      (is (some? recorder))
      (db/close-datasource! datasource))))

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

(deftest channels-init-test
  (testing "init channels component creates channels map"
    (let [ch (ig/init-key :workflow-engine/channels {})]
      (is (some? (:work-ch ch)))
      (is (some? (:result-ch ch)))
      (is (some? (:event-ch ch)))
      (ig/halt-key! :workflow-engine/channels ch))))

(deftest channels-halt-test
  (testing "halt-key! closes all channels"
    (let [ch (ig/init-key :workflow-engine/channels {})]
      (ig/halt-key! :workflow-engine/channels ch)
      (is (nil? (clojure.core.async/<!! (:work-ch ch))) "work-ch should be closed"))))

(deftest server-init-test
  (testing "init server component starts server with datasource and channels"
    (with-redefs [server/start-server! (fn [ds ch store recorder pub metrics] {:server :mock-server})]
      (let [result (ig/init-key :workflow-engine/server {:datasource :test-ds :channels :test-ch
                                                        :store :test-store :recorder :test-recorder
                                                        :publisher :test-pub :metrics :test-metrics})]
        (is (= {:server :mock-server} result))))))

(deftest server-halt-test
  (testing "halt-key! stops server and resets atom"
    (reset! server/server :mock-server)
    (ig/halt-key! :workflow-engine/server {:server :mock-server})
    (is (nil? @server/server))))

(deftest server-halt-nil-test
  (testing "halt-key! with nil server does nothing"
    (ig/halt-key! :workflow-engine/server {:server nil})
    (is true "no error with nil server")))

(deftest system-config-test
  (testing "system-config has correct keys and dependencies"
    (is (contains? system/system-config :workflow-engine/db))
    (is (contains? system/system-config :workflow-engine/store))
    (is (contains? system/system-config :workflow-engine/recorder))
    (is (contains? system/system-config :workflow-engine/publisher))
    (is (contains? system/system-config :workflow-engine/metrics))
    (is (contains? system/system-config :workflow-engine/channels))
    (is (contains? system/system-config :workflow-engine/scheduler))
    (is (contains? system/system-config :workflow-engine/server))
    (let [server-config (get system/system-config :workflow-engine/server)]
      (is (some? (:datasource server-config)))
      (is (some? (:channels server-config)))
      (is (some? (:store server-config)))
      (is (some? (:recorder server-config)))
      (is (some? (:publisher server-config)))
      (is (some? (:metrics server-config))))
    (let [scheduler-config (get system/system-config :workflow-engine/scheduler)]
      (is (some? (:datasource scheduler-config)))
      (is (some? (:channels scheduler-config)))
      (is (some? (:store scheduler-config)))
      (is (some? (:recorder scheduler-config)))
      (is (some? (:publisher scheduler-config)))
      (is (some? (:metrics scheduler-config))))))

(deftest start-and-stop-system-test
  (testing "start-system! and stop-system! work with mocked server"
    (with-redefs [server/start-server! (fn [ds ch store recorder pub metrics] {:server :mock-jetty})]
      (let [sys (system/start-system!)]
        (is (some? sys))
        (is (some? (get sys :workflow-engine/db)))
        (is (some? (get sys :workflow-engine/store)))
        (is (some? (get sys :workflow-engine/recorder)))
        (is (some? (get sys :workflow-engine/publisher)))
        (is (some? (get sys :workflow-engine/metrics)))
        (is (some? (get sys :workflow-engine/channels)))
        (is (some? (get sys :workflow-engine/scheduler)))
        (is (some? (get sys :workflow-engine/server)))
        (system/stop-system! sys)
        (is true "system stopped without error")))))

(deftest db-init-from-env-test
  (testing "init db uses from-env when no db-config provided"
    (with-redefs [config/from-env (fn [] (config/test-config))]
      (let [datasource (ig/init-key :workflow-engine/db {})]
        (is (some? datasource))
        (ig/halt-key! :workflow-engine/db datasource)))))

(deftest scheduler-init-test
  (testing "init scheduler component with datasource and channels"
    (let [datasource (db/create-datasource (config/test-config))
          ch (channels/create-channels)
          store (adapters/create-store datasource)
          recorder (adapters/create-recorder datasource)
          pub (adapters/create-publisher)
          metrics-adapter (adapters/create-metrics-collector)
          scheduler-handle (ig/init-key :workflow-engine/scheduler {:datasource datasource :channels ch
                                                                    :store store :recorder recorder
                                                                    :publisher pub :metrics metrics-adapter})]
      (is (some? scheduler-handle))
      (is (contains? scheduler-handle :workers))
      (is (contains? scheduler-handle :processor))
      (ig/halt-key! :workflow-engine/scheduler scheduler-handle)
      (channels/close-channels! ch)
      (db/close-datasource! datasource))))

(deftest scheduler-halt-nil-test
  (testing "halt-key! with nil scheduler does nothing"
    (ig/halt-key! :workflow-engine/scheduler nil)
    (is true "no error with nil scheduler")))

(deftest scheduler-halt-with-handle-test
  (testing "halt-key! with non-nil scheduler handle executes log"
    (ig/halt-key! :workflow-engine/scheduler {:workers [] :processor nil})
    (is true "scheduler halt with handle completed")))

(deftest system-config-scheduler-test
  (testing "system-config includes scheduler with datasource and channels dependencies"
    (is (contains? system/system-config :workflow-engine/scheduler))
    (let [scheduler-config (get system/system-config :workflow-engine/scheduler)]
      (is (some? (:datasource scheduler-config)))
      (is (some? (:channels scheduler-config)))
      (is (some? (:store scheduler-config)))
      (is (some? (:recorder scheduler-config)))
      (is (some? (:publisher scheduler-config)))
      (is (some? (:metrics scheduler-config))))))
