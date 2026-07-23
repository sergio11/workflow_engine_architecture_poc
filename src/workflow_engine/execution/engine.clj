(ns workflow-engine.execution.engine
  (:require [workflow-engine.workflow.model :as model]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.execution.state-machine :as sm]
            [workflow-engine.execution.context :as ctx]
            [workflow-engine.persistence.execution-repo :as repo]
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
        _ (log/info "Started execution" (:execution-id execution) "for workflow" (:id workflow))]
    execution))

(defn execute-step!
  [datasource execution workflow]
  (let [current-step-id (:current-step execution)
        step (when current-step-id (dsl/get-step-by-id workflow current-step-id))]
    (if step
      (try
        (let [result ((:handler step) (:context execution))
              new-status (sm/determine-next-status (:type step) result)
              next-step (dsl/next-step workflow current-step-id)
              updates (cond-> {:status new-status
                               :context (ctx/merge-context (:context execution) {:last-result result})}
                        next-step (assoc :current-step (:id next-step))
                        (= new-status :completed) (assoc :completed-at (java.time.Instant/now)))]
          (repo/update-execution! datasource (:execution-id execution) updates)
          (log/info "Step" current-step-id "completed with status" new-status)
          (assoc execution :status new-status
                           :current-step (when next-step (:id next-step))
                           :context (ctx/merge-context (:context execution) {:last-result result})))
        (catch Exception e
          (log/error "Step" current-step-id "failed:" (.getMessage e))
          (repo/update-execution! datasource (:execution-id execution)
                                  {:status :failed
                                   :context (ctx/merge-context (:context execution) {:error (.getMessage e)})})
          (assoc execution :status :failed)))
      (do
        (repo/update-execution! datasource (:execution-id execution) {:status :completed
                                                                      :completed-at (java.time.Instant/now)})
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
