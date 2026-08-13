(ns workflow-engine.config.system
  (:require [integrant.core :as ig]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.api.server :as server]
            [workflow-engine.events.publisher :as pub]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.scheduler.core :as scheduler]))

(defmethod ig/init-key :workflow-engine/db [_ {:keys [db-config] :as _opts}]
  (let [datasource (db/create-datasource (or db-config (config/from-env)))]
    datasource))

(defmethod ig/halt-key! :workflow-engine/db [_ datasource]
  (db/close-datasource! datasource))

(defmethod ig/init-key :workflow-engine/publisher [_ _]
  pub/subscribers)

(defmethod ig/halt-key! :workflow-engine/publisher [_ _]
  (pub/clear-subscribers!))

(defmethod ig/init-key :workflow-engine/metrics [_ _]
  (metrics/clear-metrics!))

(defmethod ig/init-key :workflow-engine/scheduler [_ {:keys [datasource]}]
  (scheduler/start-scheduler! datasource 2))

(defmethod ig/halt-key! :workflow-engine/scheduler [_ scheduler-handle]
  (when scheduler-handle
    (scheduler/stop-scheduler! scheduler-handle)))

(defmethod ig/init-key :workflow-engine/server [_ {:keys [datasource]}]
  (server/start-server! datasource))

(defmethod ig/halt-key! :workflow-engine/server [_ {:keys [server]}]
  (when server
    (.stop server)))

(def system-config
  {:workflow-engine/db {}
   :workflow-engine/publisher {}
   :workflow-engine/metrics {}
   :workflow-engine/scheduler {:datasource (ig/ref :workflow-engine/db)}
   :workflow-engine/server {:datasource (ig/ref :workflow-engine/db)}})

(defn start-system! []
  (ig/init system-config))

(defn stop-system! [system]
  (ig/halt! system))
