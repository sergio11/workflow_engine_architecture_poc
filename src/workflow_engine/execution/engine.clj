(ns workflow-engine.execution.engine
  (:require [workflow-engine.workflow.model :as model]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.execution.context :as ctx]
            [workflow-engine.execution.ports :as ports]
            [workflow-engine.execution.step-executor :as executor]
            [workflow-engine.execution.transitions :as transitions]
            [workflow-engine.execution.state-machine :as sm]
            [workflow-engine.scheduler.channels :as channels]
            [clojure.tools.logging :as log]))

(defn start-execution!
  [store recorder publisher metrics datasource workflow input-data]
  (let [first-step (first (:steps workflow))
        execution (model/make-execution
                    (str (java.util.UUID/randomUUID))
                    (:id workflow)
                    (ctx/create-context input-data))
        execution (assoc execution :current-step (:id first-step))
        _ (ports/save-execution! store execution)
        _ (ports/record-workflow-started! recorder (:execution-id execution))
        _ (ports/publish! publisher {:type :workflow-started :execution-id (:execution-id execution)})
        _ (ports/record-workflow-started-metric! metrics)
        _ (log/info "Started execution" (:execution-id execution) "for workflow" (:id workflow))]
    execution))

(defn execute-step!
  [store recorder publisher metrics datasource execution workflow]
  (let [current-step-id (:current-step execution)
        step (when current-step-id (dsl/get-step-by-id workflow current-step-id))]
    (if step
      (let [start-time (System/currentTimeMillis)
            handler-fn (executor/resolve-handler step)]
        (try
          (ports/record-step-started! recorder (:execution-id execution) current-step-id)
          (ports/publish! publisher {:type :step-started :execution-id (:execution-id execution) :step current-step-id})
          (let [result (if handler-fn
                         (executor/execute-step handler-fn (:context execution) step)
                         {:error (str "No handler registered for step: " (:id step))})
                duration-ms (- (System/currentTimeMillis) start-time)
                new-status (sm/determine-next-status (:type step) result)
                next-step (transitions/determine-next-step workflow current-step-id result (:type step))
                updates (transitions/build-updates execution step result next-step new-status)]
            (ports/update-execution! store (:execution-id execution) updates)
            (ports/record-step-execution-metric! metrics duration-ms)
            (if (= new-status :failed)
              (do
                (ports/record-step-failed! recorder (:execution-id execution) current-step-id (:error result))
                (ports/publish! publisher {:type :step-failed :execution-id (:execution-id execution) :step current-step-id :error (:error result)})
                (ports/record-workflow-failed-metric! metrics)
                (log/error "Step" current-step-id "failed:" (:error result))
                (assoc execution :status :failed
                                 :current-step nil
                                 :context (ctx/merge-context (:context execution) {:last-result result})))
              (do
                (ports/record-step-completed! recorder (:execution-id execution) current-step-id result)
                (ports/publish! publisher {:type :step-completed :execution-id (:execution-id execution) :step current-step-id :result result})
                (log/info "Step" current-step-id "completed with status" new-status)
                (when-not next-step
                  (ports/record-workflow-completed! recorder (:execution-id execution))
                  (ports/publish! publisher {:type :workflow-completed :execution-id (:execution-id execution)})
                  (ports/record-workflow-completed-metric! metrics))
                (assoc execution :status new-status
                                 :current-step (when next-step (:id next-step))
                                 :context (ctx/merge-context (:context execution) {:last-result result})))))
          (catch Exception e
            (let [duration-ms (- (System/currentTimeMillis) start-time)]
              (log/error e "Step" current-step-id "failed:")
              (ports/update-execution! store (:execution-id execution)
                                       {:status :failed
                                        :context (ctx/merge-context (:context execution) {:error (.getMessage e)})})
              (ports/record-step-failed! recorder (:execution-id execution) current-step-id (.getMessage e))
              (ports/publish! publisher {:type :step-failed :execution-id (:execution-id execution) :step current-step-id :error (.getMessage e)})
              (ports/record-step-execution-metric! metrics duration-ms)
              (ports/record-workflow-failed-metric! metrics)
              (assoc execution :status :failed)))))
      (do
        (ports/update-execution! store (:execution-id execution) {:status :completed
                                                                   :completed-at (java.time.Instant/now)})
        (ports/record-workflow-completed! recorder (:execution-id execution))
        (ports/publish! publisher {:type :workflow-completed :execution-id (:execution-id execution)})
        (ports/record-workflow-completed-metric! metrics)
        (assoc execution :status :completed)))))

(defn advance-execution!
  [store recorder publisher metrics datasource execution-id workflow]
  (let [execution (ports/get-execution store execution-id)]
    (when (and execution (= :running (:status execution)))
      (execute-step! store recorder publisher metrics datasource execution workflow))))

(defn cancel-execution!
  [store recorder publisher metrics datasource execution-id]
  (let [execution (ports/get-execution store execution-id)]
    (when (and execution (#{:pending :running :waiting} (:status execution)))
      (ports/update-execution! store execution-id {:status :cancelled})
      (ports/record-workflow-cancelled! recorder execution-id)
      (ports/publish! publisher {:type :workflow-cancelled :execution-id execution-id})
      (log/info "Cancelled execution" execution-id)
      (assoc execution :status :cancelled))))

(defn resume-execution!
  [store recorder publisher metrics datasource execution-id workflow]
  (let [execution (ports/get-execution store execution-id)]
    (when (and execution (= :waiting (:status execution)))
      (ports/update-execution! store execution-id {:status :running})
      (log/info "Resumed execution" execution-id)
      (execute-step!
       store recorder publisher metrics datasource
       (assoc execution :status :running)
       workflow))))

(defn retry-execution!
  [store recorder publisher metrics datasource execution-id workflow]
  (let [execution (ports/get-execution store execution-id)]
    (when (and execution (= :failed (:status execution)))
      (let [current-step (or (:current-step execution) (:id (first (:steps workflow))))]
        (ports/update-execution! store execution-id {:status :running})
        (log/info "Retrying execution" execution-id)
        (execute-step!
         store recorder publisher metrics datasource
         (assoc execution :status :running :current-step current-step)
         workflow)))))

(defn handle-step-completion!
  "Shared logic for handling step completion (used by both sync and async paths)."
  [store recorder publisher metrics datasource execution workflow step result duration-ms]
  (let [current-step-id (:id step)
        new-status (sm/determine-next-status (:type step) result)
        next-step (transitions/determine-next-step workflow current-step-id result (:type step))
        updates (transitions/build-updates execution step result next-step new-status)]
    (ports/update-execution! store (:execution-id execution) updates)
    (ports/record-step-execution-metric! metrics duration-ms)
    (if (= new-status :failed)
      (do
        (ports/record-step-failed! recorder (:execution-id execution) current-step-id (:error result))
        (ports/publish! publisher {:type :step-failed :execution-id (:execution-id execution) :step current-step-id :error (:error result)})
        (ports/record-workflow-failed-metric! metrics)
        (log/error "Step" current-step-id "failed:" (:error result))
        (assoc execution :status :failed
                         :current-step nil
                         :context (ctx/merge-context (:context execution) {:last-result result})))
      (do
        (ports/record-step-completed! recorder (:execution-id execution) current-step-id result)
        (ports/publish! publisher {:type :step-completed :execution-id (:execution-id execution) :step current-step-id :result result})
        (log/info "Step" current-step-id "completed with status" new-status)
        (when-not next-step
          (ports/record-workflow-completed! recorder (:execution-id execution))
          (ports/publish! publisher {:type :workflow-completed :execution-id (:execution-id execution)})
          (ports/record-workflow-completed-metric! metrics))
        (assoc execution :status new-status
                         :current-step (when next-step (:id next-step))
                         :context (ctx/merge-context (:context execution) {:last-result result}))))))

(defn submit-step-for-execution!
  "Submit the current step of an execution to the scheduler for async processing."
  [store recorder publisher metrics datasource execution workflow ch]
  (let [current-step-id (:current-step execution)
        step (when current-step-id (dsl/get-step-by-id workflow current-step-id))]
    (if step
      (let [handler-fn (executor/resolve-handler step)]
        (if handler-fn
          (let [work-item {:handler-fn handler-fn
                           :context (:context execution)
                           :step step
                           :execution execution
                           :workflow workflow
                           :datasource datasource
                           :store store
                           :recorder recorder
                           :publisher publisher
                           :metrics metrics}]
            (channels/submit-work! ch work-item))
          (do
            (log/error "No handler registered for step:" current-step-id)
            (handle-step-completion! store recorder publisher metrics datasource execution workflow step
                                    {:error (str "No handler registered for step: " current-step-id)}
                                    0))))
      (do
        (ports/update-execution! store (:execution-id execution) {:status :completed
                                                                   :completed-at (java.time.Instant/now)})
        (ports/record-workflow-completed! recorder (:execution-id execution))
        (ports/publish! publisher {:type :workflow-completed :execution-id (:execution-id execution)})
        (ports/record-workflow-completed-metric! metrics)))))

(defn handle-async-completion!
  "Handle the completion result of an async step execution."
  [store recorder publisher metrics datasource execution workflow step result duration-ms]
  (ports/record-step-started! recorder (:execution-id execution) (:id step))
  (ports/publish! publisher {:type :step-started :execution-id (:execution-id execution) :step (:id step)})
  (handle-step-completion! store recorder publisher metrics datasource execution workflow step result duration-ms))

(defn handle-async-exception!
  "Handle an exception during async step execution."
  [store recorder publisher metrics datasource execution workflow step exception duration-ms]
  (let [result {:error (.getMessage exception)}]
    (handle-step-completion! store recorder publisher metrics datasource execution workflow step result duration-ms)))
