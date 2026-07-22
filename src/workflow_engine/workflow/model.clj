(ns workflow-engine.workflow.model)

(defrecord Workflow [id name version steps metadata])

(defrecord Step [id type handler retry timeout])

(defrecord Execution [execution-id workflow-id status current-step started-at updated-at context history])

(defrecord Event [type execution-id step timestamp data])

(def step-types #{:task :wait :decision :parallel})

(def execution-statuses #{:pending :running :waiting :failed :completed :cancelled})

(def event-types #{:workflow-started :step-started :step-completed :step-failed :workflow-completed :workflow-failed :workflow-cancelled})

(defn make-step
  ([id type]
   (->Step id type nil nil nil))
  ([id type handler]
   (->Step id type handler nil nil))
  ([id type handler retry timeout]
   (->Step id type handler retry timeout)))

(defn make-workflow
  [id name version steps]
  (->Workflow id name version steps {}))

(defn make-execution
  [execution-id workflow-id context]
  (->Execution execution-id workflow-id :pending nil (java.time.Instant/now) (java.time.Instant/now) context []))

(defn make-event
  [type execution-id step data]
  (->Event type execution-id step (java.time.Instant/now) data))
