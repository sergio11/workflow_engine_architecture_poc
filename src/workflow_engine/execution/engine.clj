(ns workflow-engine.execution.engine
  (:require [workflow-engine.workflow.model :as model]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.execution.state-machine :as sm]
            [workflow-engine.execution.context :as ctx]
            [workflow-engine.persistence.execution-repo :as repo]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.events.store :as event-store]
            [workflow-engine.events.publisher :as publisher]
            [clojure.tools.logging :as log]))

(defn start-execution!
  [datasource workflow input-data]
  (let [first-step (first (:steps workflow))
        execution (model/make-execution
                    (str (java.util.UUID/randomUUID))
                    (:id workflow)
                    (ctx/create-context input-data))
        execution (assoc execution :current-step (:id first-step))
        _ (repo/save-execution! datasource execution)
        _ (event-store/record-workflow-started! datasource (:execution-id execution))
        _ (publisher/publish! {:type :workflow-started :execution-id (:execution-id execution)})
        _ (metrics/record-workflow-started!)
        _ (log/info "Started execution" (:execution-id execution) "for workflow" (:id workflow))]
    execution))

(defn execute-step!
  [datasource execution workflow]
  (let [current-step-id (:current-step execution)
        step (when current-step-id (dsl/get-step-by-id workflow current-step-id))]
    (if step
      (let [start-time (System/currentTimeMillis)]
        (try
          (event-store/record-step-started! datasource (:execution-id execution) current-step-id)
          (publisher/publish! {:type :step-started :execution-id (:execution-id execution) :step current-step-id})
          (let [result ((:handler step) (:context execution))
                duration-ms (- (System/currentTimeMillis) start-time)
                new-status (sm/determine-next-status (:type step) result)
                next-step (dsl/next-step workflow current-step-id)
                updates (cond-> {:status new-status
                                 :context (ctx/merge-context (:context execution) {:last-result result})}
                          next-step (assoc :current-step (:id next-step))
                          (= new-status :completed) (assoc :completed-at (java.time.Instant/now)))]
            (repo/update-execution! datasource (:execution-id execution) updates)
            (event-store/record-step-completed! datasource (:execution-id execution) current-step-id result)
            (publisher/publish! {:type :step-completed :execution-id (:execution-id execution) :step current-step-id :result result})
            (metrics/record-step-execution! duration-ms)
            (log/info "Step" current-step-id "completed with status" new-status)
            (when-not next-step
              (event-store/record-workflow-completed! datasource (:execution-id execution))
              (publisher/publish! {:type :workflow-completed :execution-id (:execution-id execution)})
              (metrics/record-workflow-completed!))
            (assoc execution :status new-status
                             :current-step (when next-step (:id next-step))
                             :context (ctx/merge-context (:context execution) {:last-result result})))
          (catch Exception e
            (let [duration-ms (- (System/currentTimeMillis) start-time)]
              (log/error "Step" current-step-id "failed:" (.getMessage e))
              (repo/update-execution! datasource (:execution-id execution)
                                      {:status :failed
                                       :context (ctx/merge-context (:context execution) {:error (.getMessage e)})})
              (event-store/record-step-failed! datasource (:execution-id execution) current-step-id (.getMessage e))
              (publisher/publish! {:type :step-failed :execution-id (:execution-id execution) :step current-step-id :error (.getMessage e)})
              (metrics/record-step-execution! duration-ms)
              (metrics/record-workflow-failed!)
              (assoc execution :status :failed)))))
      (do
        (repo/update-execution! datasource (:execution-id execution) {:status :completed
                                                                      :completed-at (java.time.Instant/now)})
        (event-store/record-workflow-completed! datasource (:execution-id execution))
        (publisher/publish! {:type :workflow-completed :execution-id (:execution-id execution)})
        (metrics/record-workflow-completed!)
        (assoc execution :status :completed)))))

(defn advance-execution!
  [datasource execution-id workflow]
  (let [execution (repo/get-execution datasource execution-id)]
    (when (and execution (= :running (:status execution)))
      (execute-step! datasource execution workflow))))

(defn cancel-execution!
  [datasource execution-id]
  (let [execution (repo/get-execution datasource execution-id)]
    (when (and execution (#{:pending :running :waiting} (:status execution)))
      (repo/update-execution! datasource execution-id {:status :cancelled})
      (event-store/record-workflow-cancelled! datasource execution-id)
      (publisher/publish! {:type :workflow-cancelled :execution-id execution-id})
      (log/info "Cancelled execution" execution-id)
      (assoc execution :status :cancelled))))

(defn resume-execution!
  [datasource execution-id workflow]
  (let [execution (repo/get-execution datasource execution-id)]
    (when (and execution (= :waiting (:status execution)))
      (repo/update-execution! datasource execution-id {:status :running})
      (log/info "Resumed execution" execution-id)
      (execute-step!
       datasource
       (assoc execution :status :running)
       workflow))))

(defn retry-execution!
  [datasource execution-id workflow]
  (let [execution (repo/get-execution datasource execution-id)]
    (when (and execution (= :failed (:status execution)))
      (let [current-step (or (:current-step execution) (:id (first (:steps workflow))))]
        (repo/update-execution! datasource execution-id {:status :running})
        (log/info "Retrying execution" execution-id)
        (execute-step!
         datasource
         (assoc execution :status :running :current-step current-step)
         workflow)))))
