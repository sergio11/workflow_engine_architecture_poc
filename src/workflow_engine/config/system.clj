(ns workflow-engine.config.system
  (:require [integrant.core :as ig]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.api.server :as server]
            [workflow-engine.execution.adapters :as adapters]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.scheduler.channels :as channels]
            [workflow-engine.scheduler.core :as scheduler]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :workflow-engine/db [_ {:keys [db-config] :as _opts}]
  (let [datasource (db/create-datasource (or db-config (config/from-env)))]
    datasource))

(defmethod ig/halt-key! :workflow-engine/db [_ datasource]
  (db/close-datasource! datasource))

(defmethod ig/init-key :workflow-engine/store [_ {:keys [datasource]}]
  (adapters/create-store datasource))

(defmethod ig/init-key :workflow-engine/recorder [_ {:keys [datasource]}]
  (adapters/create-recorder datasource))

(defmethod ig/init-key :workflow-engine/publisher [_ _]
  (adapters/create-publisher))

(defmethod ig/init-key :workflow-engine/metrics [_ _]
  (adapters/create-metrics-collector))

(defmethod ig/init-key :workflow-engine/channels [_ _]
  (channels/create-channels))

(defmethod ig/halt-key! :workflow-engine/channels [_ ch]
  (channels/close-channels! ch))

(defmethod ig/init-key :workflow-engine/scheduler [{:keys [store recorder publisher metrics]} {:keys [datasource channels]}]
  (scheduler/start-scheduler! datasource channels 2 store recorder publisher metrics))

(defmethod ig/halt-key! :workflow-engine/scheduler [_ scheduler-handle]
  ;; channels are halted separately via :workflow-engine/channels
  (log/info "Scheduler handle released"))

(defmethod ig/init-key :workflow-engine/server [{:keys [store recorder publisher metrics]} {:keys [datasource channels]}]
  (server/start-server! datasource channels store recorder publisher metrics))

(defmethod ig/halt-key! :workflow-engine/server [_ component]
  (when-let [srv (:server component)]
    (try
      (.stop srv)
      (catch Exception _))
    (reset! server/server nil)))

(def system-config
  {:workflow-engine/db {}
   :workflow-engine/store {:datasource (ig/ref :workflow-engine/db)}
   :workflow-engine/recorder {:datasource (ig/ref :workflow-engine/db)}
   :workflow-engine/publisher {}
   :workflow-engine/metrics {}
   :workflow-engine/channels {}
   :workflow-engine/scheduler {:datasource (ig/ref :workflow-engine/db)
                               :channels (ig/ref :workflow-engine/channels)
                               :store (ig/ref :workflow-engine/store)
                               :recorder (ig/ref :workflow-engine/recorder)
                               :publisher (ig/ref :workflow-engine/publisher)
                               :metrics (ig/ref :workflow-engine/metrics)}
   :workflow-engine/server {:datasource (ig/ref :workflow-engine/db)
                            :channels (ig/ref :workflow-engine/channels)
                            :store (ig/ref :workflow-engine/store)
                            :recorder (ig/ref :workflow-engine/recorder)
                            :publisher (ig/ref :workflow-engine/publisher)
                            :metrics (ig/ref :workflow-engine/metrics)}})

(defn start-system! []
  (ig/init system-config))

(defn stop-system! [system]
  (ig/halt! system))
