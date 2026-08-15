(ns workflow-engine.api.handlers
  (:require [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.persistence.execution-repo :as exec-repo]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.worker.registry :as registry]
            [workflow-engine.workflow.validator :as validator]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.version :as version]))

(defn create-workflow
  [datasource store recorder publisher metrics]
  (fn [request]
    (let [body (:body request)
          wf (model/make-workflow
               (str (java.util.UUID/randomUUID))
               (:name body)
               (or (:version body) 1)
                (mapv #(if (map? %)
                         (model/step-from-map %)
                         (apply model/make-step
                                (cond-> (vec %)
                                  (and (second %) (string? (second %)))
                                  (update 1 keyword))))
                      (:steps body)))
          validation (validator/validate-workflow wf)]
      (if (:valid? validation)
        (do
          (doseq [step (:steps wf)]
            (when (:handler step)
              (registry/register-handler! (:id step) (:handler step))))
          (wf-repo/save-workflow! datasource wf)
          {:status 201
           :body {:id (:id wf) :name (:name wf) :version (:version wf)}})
        {:status 400
         :body {:error "Invalid workflow" :details (:errors validation)}}))))

(defn get-workflow
  [datasource]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          wf (wf-repo/get-workflow datasource id)]
      (if wf
        {:status 200
         :body {:id (:id wf) :name (:name wf) :version (:version wf) :steps (:steps wf)}}
        {:status 404
         :body {:error "Workflow not found"}}))))

(defn list-workflows
  [datasource]
  (fn [_request]
    (let [workflows (wf-repo/list-workflows datasource)]
      {:status 200
       :body (mapv #(select-keys % [:id :name :version :created_at]) workflows)})))

(defn delete-workflow
  [datasource]
  (fn [request]
    (let [id (get-in request [:path-params :id])]
      (wf-repo/delete-workflow! datasource id)
      {:status 204
       :body nil})))

(defn start-execution
  [datasource channels store recorder publisher metrics]
  (fn [request]
    (let [body (:body request)
          wf (wf-repo/get-workflow datasource (:workflow-id body))]
      (if wf
        (let [exec (engine/start-execution! store recorder publisher metrics datasource wf (:input body {}))]
          (engine/submit-step-for-execution! store recorder publisher metrics datasource exec wf channels)
          {:status 202
           :body {:execution-id (:execution-id exec)
                  :workflow-id (:workflow-id exec)
                  :status :pending
                  :message "Execution submitted for async processing"}})
        {:status 404
         :body {:error "Workflow not found"}}))))

(defn get-execution
  [datasource]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          exec (exec-repo/get-execution datasource id)]
      (if exec
        {:status 200
         :body exec}
        {:status 404
         :body {:error "Execution not found"}}))))

(defn list-executions
  [datasource]
  (fn [request]
    (let [workflow-id (get-in request [:query-params "workflow_id"])
          executions (if workflow-id
                      (exec-repo/list-executions datasource workflow-id)
                      [])]
      {:status 200
       :body executions})))

(defn cancel-execution
  [datasource store recorder publisher metrics]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          result (engine/cancel-execution! store recorder publisher metrics datasource id)]
      (if result
        {:status 200
         :body {:execution-id (:execution-id result) :status (:status result)}}
        {:status 404
         :body {:error "Execution not found or not cancellable"}}))))

(defn resume-execution
  [datasource channels store recorder publisher metrics]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          wf (wf-repo/get-workflow datasource (get-in request [:body :workflow-id]))
          execution (when wf (exec-repo/get-execution datasource id))]
      (cond
        (nil? execution)
        {:status 404
         :body {:error "Execution not found"}}

        (not= :waiting (:status execution))
        {:status 400
         :body {:error "Execution is not in waiting state"}}

        :else
        (do
          (exec-repo/update-execution! datasource id {:status :running})
          (let [updated-exec (assoc execution :status :running)]
            (engine/submit-step-for-execution! store recorder publisher metrics datasource updated-exec wf channels))
          {:status 200
           :body {:execution-id id :status :running :message "Execution resumed"}})))))

(defn retry-execution
  [datasource channels store recorder publisher metrics]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          body (:body request)
          wf (when (:workflow-id body) (wf-repo/get-workflow datasource (:workflow-id body)))
          execution (when wf (exec-repo/get-execution datasource id))]
      (cond
        (nil? execution)
        {:status 404
         :body {:error "Execution not found"}}

        (not= :failed (:status execution))
        {:status 400
         :body {:error "Execution is not in failed state"}}

        :else
        (let [current-step (or (:current-step execution) (:id (first (:steps wf))))]
          (exec-repo/update-execution! datasource id {:status :running})
          (let [updated-exec (assoc execution :status :running :current-step current-step)]
            (engine/submit-step-for-execution! store recorder publisher metrics datasource updated-exec wf channels))
          {:status 200
           :body {:execution-id id :status :running :message "Execution retried"}})))))

(defn health-check [_request]
  {:status 200
   :body {:status "ok" :version version/version}})

(defn get-metrics [_request]
  {:status 200
   :body {:counters (metrics/snapshot-counters)
          :gauges (metrics/snapshot-gauges)
          :histograms (metrics/snapshot-histograms)}})
