(ns workflow-engine.worker.examples)

(defn create-user
  [context]
  (let [user-data (:user-data context)]
    {:user-id (str "user-" (System/currentTimeMillis))
     :email (:email user-data)
     :created true}))

(defn send-email
  [context]
  (let [to (:email-to context)
        subject (:email-subject context)]
    (println "Sending email to" to "with subject" subject)
    {:sent true :to to :subject subject}))

(defn process-payment
  [context]
  (let [amount (:amount context)]
    (when (neg? amount)
      (throw (ex-info "Invalid payment amount" {:amount amount})))
    {:payment-id (str "pay-" (System/currentTimeMillis))
     :amount amount
     :status :completed}))

(defn flaky-handler
  [context]
  (if (< (rand) 0.7)
    {:error "Random failure"}
    {:success true}))

(defn slow-handler
  [context]
  (Thread/sleep 2000)
  {:result "completed after delay"})

(defn timeout-handler
  [context]
  (Thread/sleep 10000)
  {:result "should timeout"})
