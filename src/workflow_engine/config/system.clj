(ns workflow-engine.config.system
  (:require [integrant.core :as ig]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.api.server :as server]
            [workflow-engine.events.publisher :as pub]
            [workflow-engine.metrics.collector :as metrics]))

(defmethod ig/init-key :workflow-engine/db [_ _]
  (let [datasource (db/create-datasource (config/from-env))]
    datasource))

(defmethod ig/halt-key! :workflow-engine/db [_ datasource]
  (db/close-datasource! datasource))

(defmethod ig/init-key :workflow-engine/publisher [_ _]
  pub/subscribers)

(defmethod ig/halt-key! :workflow-engine/publisher [_ _]
  (pub/clear-subscribers!))

(defmethod ig/init-key :workflow-engine/metrics [_ _]
  (metrics/clear-metrics!))

(defmethod ig/init-key :workflow-engine/server [_ {:keys [datasource]}]
  (server/start-server! (config/from-env)))

(defmethod ig/halt-key! :workflow-engine/server [_ {:keys [server]}]
  (when server
    (.stop server)))

(def system-config
  {::db (ig/init-key :workflow-engine/db {})
   ::publisher (ig/init-key :workflow-engine/publisher {})
   ::metrics (ig/init-key :workflow-engine/metrics {})
   ::server {:datasource (ig/ref ::db)}})

(defn start-system! []
  (ig/init system-config))

(defn stop-system! [system]
  (ig/halt! system))
