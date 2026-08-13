(ns workflow-engine.api.routes
  (:require [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m]
            [workflow-engine.api.handlers :as handlers]))

(defn api-routes [datasource]
  (ring/ring-handler
    (ring/router
      ["/api/v1"
       ["/workflows"
        ["" {:get (handlers/list-workflows datasource)
             :post (handlers/create-workflow datasource)}]
        ["/:id" {:get (handlers/get-workflow datasource)
                 :delete (handlers/delete-workflow datasource)}]]
       ["/executions"
        ["" {:get (handlers/list-executions datasource)
             :post (handlers/start-execution datasource)}]
        ["/:id" {:get (handlers/get-execution datasource)}]
        ["/:id/cancel" {:post (handlers/cancel-execution datasource)}]
        ["/:id/resume" {:post (handlers/resume-execution datasource)}]
        ["/:id/retry" {:post (handlers/retry-execution datasource)}]]
       ["/health" {:get handlers/health-check}]
        ["/metrics" {:get handlers/get-metrics}]]
      {:data {:muuntaja m/instance
              :middleware [muuntaja/format-middleware
                          coercion/coerce-exceptions-middleware]}})

    (ring/routes
      (ring/create-default-handler
        {:not-found (fn [_] {:status 404 :body {:error "Not found"}})}))))
