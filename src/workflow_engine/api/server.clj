(ns workflow-engine.api.server
  (:require [ring.adapter.jetty :as jetty]
            [workflow-engine.api.routes :as routes]
            [workflow-engine.api.middleware :as middleware]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [clojure.tools.logging :as log]))

(defonce server (atom nil))

(defn start-server!
  ([]
   (start-server! (config/from-env)))
  ([db-config]
   (let [datasource (db/create-datasource db-config)
         handler (-> (routes/api-routes datasource)
                     middleware/wrap-json-response
                     middleware/wrap-json-body
                     middleware/wrap-params
                     middleware/wrap-exception
                     middleware/wrap-cors)
         port (or (some-> (System/getenv "APP_PORT") Integer/parseInt) 3000)]
     (log/info "Starting server on port" port)
     (reset! server (jetty/run-jetty handler {:port port :join? false}))
     (log/info "Server started on port" port)
     {:server @server :datasource datasource})))

(defn stop-server! []
  (when @server
    (.stop @server)
    (reset! server nil)
    (log/info "Server stopped")))
