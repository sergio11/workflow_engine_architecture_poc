(ns workflow-engine.worker.registry)

(defonce handler-registry (atom {}))

(defn register-handler!
  [step-id handler]
  (swap! handler-registry assoc step-id handler)
  handler)

(defn unregister-handler!
  [step-id]
  (swap! handler-registry dissoc step-id))

(defn get-handler
  [step-id]
  (get @handler-registry step-id))

(defn clear-registry! []
  (reset! handler-registry {}))

(defn registered-handlers []
  (keys @handler-registry))

(defn register-bulk!
  [handlers-map]
  (swap! handler-registry merge handlers-map))
