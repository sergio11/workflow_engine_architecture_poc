(ns workflow-engine.api.server
  (:require [ring.adapter.jetty :as jetty]
            [workflow-engine.api.routes :as routes]
            [workflow-engine.api.middleware :as middleware]
            [clojure.tools.logging :as log]))

(defonce server (atom nil))

(defn get-port []
  (or (some-> (System/getenv "APP_PORT") Integer/parseInt) 3000))

(defn start-server!
  [datasource channels store recorder publisher metrics]
  (let [handler (-> (routes/api-routes datasource channels store recorder publisher metrics)
                    middleware/wrap-json-response
                    middleware/wrap-json-body
                    middleware/wrap-params
                    middleware/wrap-exception
                    middleware/wrap-cors)
        port (get-port)]
    (log/info "Starting server on port" port)
    (let [srv (jetty/run-jetty handler {:port port :join? false})]
      (reset! server srv)
      (log/info "Server started on port" port)
      {:server srv})))

(defn stop-server! []
  (when @server
    (try
      (.stop @server)
      (catch Exception e
        (log/warn "Error stopping server:" (.getMessage e))))
    (reset! server nil)
    (log/info "Server stopped")))
