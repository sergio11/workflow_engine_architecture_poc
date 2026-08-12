(ns demo.scenarios
  (:require [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.worker.registry :as registry]
            [demo.formatting :as fmt]))

(defn register-user-handlers! []
  (registry/register-bulk!
    {"create-user"
     (fn [ctx]
       (let [user-data (:user-data ctx)]
         (Thread/sleep (+ 50 (rand-int 100)))
         {:user-id (str "USR-" (System/currentTimeMillis))
          :email (:email user-data)
          :name (:name user-data)
          :created true}))

     "send-welcome-email"
     (fn [ctx]
       (let [last-result (:last-result ctx)]
         (Thread/sleep (+ 30 (rand-int 70)))
         {:sent true
          :to (:email last-result)
          :subject "Welcome to workflow-engine!"
          :body (str "Hello " (:name last-result) ", welcome!")}))

     "register-analytics"
     (fn [ctx]
       (let [last-result (:last-result ctx)]
         (Thread/sleep (+ 20 (rand-int 40)))
         {:event "user_registered"
          :user-id (:user-id last-result)
          :timestamp (java.time.Instant/now)}))}))

(defn create-user-registration-workflow []
  (register-user-handlers!)
  (dsl/linear-workflow
    "wf-user-registration"
    "User Registration Pipeline"
    1
    [{:id "create-user" :type :task}
     {:id "send-welcome-email" :type :task}
     {:id "register-analytics" :type :task}]))

(defn register-payment-handlers! []
  (registry/register-bulk!
    {"validate-payment"
     (fn [ctx]
       (let [amount (:amount ctx)]
         (Thread/sleep 50)
         (when (or (nil? amount) (neg? amount))
           (throw (ex-info "Invalid payment amount" {:amount amount})))
         {:valid true :amount amount :currency "USD"}))

     "process-payment"
     (fn [_ctx]
       (Thread/sleep (+ 100 (rand-int 200)))
       (if (< (rand) 0.4)
         (do
           (println (fmt/yellow-text "    \u26a0 Payment processor flaky — retrying..."))
           {:error "Temporary failure" :retryable true})
         {:payment-id (str "PAY-" (System/currentTimeMillis))
          :status :processed
          :amount 99.99}))

     "confirm-payment"
     (fn [ctx]
       (let [payment (:last-result ctx)]
         (Thread/sleep 50)
         {:confirmed true
          :payment-id (:payment-id payment)
          :receipt (str "RCP-" (System/currentTimeMillis))}))}))

(defn create-payment-workflow []
  (register-payment-handlers!)
  (let [retry-config {:max-attempts 3 :base-delay 500 :max-delay 5000}]
    (dsl/linear-workflow
      "wf-payment-processing"
      "Payment Processing"
      1
      [{:id "validate-payment" :type :task}
       {:id "process-payment" :type :task :retry retry-config :timeout 10000}
       {:id "confirm-payment" :type :task}])))

(defn register-order-handlers! []
  (registry/register-bulk!
    {"check-stock"
     (fn [ctx]
       (Thread/sleep 80)
       (let [product-id (:product-id ctx)
             quantity (or (:quantity ctx) 1)]
         {:product-id product-id
          :quantity quantity
          :in-stock (> (rand-int 10) 2)}))

     "ship-order"
     (fn [ctx]
       (let [stock (:last-result ctx)]
         (Thread/sleep 100)
         {:shipped true
          :tracking (str "TRK-" (System/currentTimeMillis))
          :product-id (:product-id stock)
          :quantity (:quantity stock)}))

     "notify-backorder"
     (fn [ctx]
       (let [stock (:last-result ctx)]
         (Thread/sleep 50)
         {:notified true
          :product-id (:product-id stock)
          :message "Item on backorder, you'll be notified when available"}))}))

(defn create-order-workflow []
  (register-order-handlers!)
  (dsl/linear-workflow
    "wf-order-fulfillment"
    "Order Fulfillment"
    1
    [{:id "check-stock" :type :task}
     {:id "ship-order" :type :task}
     {:id "notify-backorder" :type :task}]))

(defn register-all-scenarios! []
  (register-user-handlers!)
  (register-payment-handlers!)
  (register-order-handlers!))

(def scenario-1-description
  "Pipeline lineal: crear usuario \u2192 email bienvenida \u2192 analytics.
Demuestra DSL funcional, composition de handlers, y event sourcing.")

(def scenario-2-description
  "Procesamiento de pago con retry en handler inestable.
Demuestra exponential backoff, state machine, manejo de errores.")

(def scenario-3-description
  "Fulfillment de orden con verificaci\u00f3n de stock.
Demuestra context propagation y m\u00faltiples ramas de ejecuci\u00f3n.")
