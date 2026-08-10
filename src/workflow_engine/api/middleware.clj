(ns workflow-engine.api.middleware
  (:require [ring.middleware.json :as ring-json]
            [ring.middleware.params :as params]
            [clojure.tools.logging :as log]))

(defn wrap-json-body [handler]
  (ring-json/wrap-json-body handler {:keywords? true :bigdecimals? false}))

(defn wrap-json-response [handler]
  (ring-json/wrap-json-response handler {:pretty true}))

(defn wrap-params [handler]
  (params/wrap-params handler))

(defn wrap-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (log/error e "Unhandled exception")
        {:status 500
         :body {:error (.getMessage e)}}))))

(defn wrap-cors [handler]
  (fn [request]
    (let [response (handler request)]
      (-> response
          (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
          (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, POST, PUT, DELETE, OPTIONS")
          (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type, Authorization")))))

(defn wrap-request-logging [handler]
  (fn [request]
    (println (:request-method request) (:uri request))
    (handler request)))
