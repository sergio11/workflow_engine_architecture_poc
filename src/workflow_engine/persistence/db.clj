(ns workflow-engine.persistence.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [hikari-cp.core :as hikari]))

(defn create-datasource
  [{:keys [db-name db-user db-password db-host db-port]}]
  (hikari/make-datasource
   {:jdbc-url (str "jdbc:postgresql://" (or db-host "localhost") ":" (or db-port 5432) "/" (or db-name "workflow_engine"))
    :username (or db-user "workflow_engine")
    :password (or db-password "workflow_dev")
    :maximum-pool-size 10
    :minimum-idle 5
    :idle-timeout 300000
    :connection-timeout 20000}))

(defn close-datasource! [datasource]
  (hikari/close-datasource datasource))

(defn execute! [datasource sql-params]
  (jdbc/execute! datasource sql-params
                 {:builder-fn rs/as-unqualified-maps}))

(defn execute-one! [datasource sql-params]
  (jdbc/execute-one! datasource sql-params
                     {:builder-fn rs/as-unqualified-maps}))


