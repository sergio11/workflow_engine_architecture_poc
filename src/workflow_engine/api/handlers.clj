(ns workflow-engine.api.handlers
  (:require [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.persistence.execution-repo :as exec-repo]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.worker.registry :as registry]
            [workflow-engine.workflow.validator :as validator]
            [workflow-engine.version :as version]))

(defn create-workflow
  [datasource]
  (fn [request]
    (let [body (:body request)
          wf (model/make-workflow
               (str (java.util.UUID/randomUUID))
               (:name body)
               (or (:version body) 1)
               (mapv model/step-from-map (:steps body)))
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
  [datasource]
  (fn [request]
    (let [body (:body request)
          wf (wf-repo/get-workflow datasource (:workflow-id body))]
      (if wf
        (let [exec (engine/start-execution! datasource wf (:input body {}))]
          {:status 201
           :body {:execution-id (:execution-id exec)
                  :workflow-id (:workflow-id exec)
                  :status (:status exec)}})
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
  [datasource]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          result (engine/cancel-execution! datasource id)]
      (if result
        {:status 200
         :body {:execution-id (:execution-id result) :status (:status result)}}
        {:status 404
         :body {:error "Execution not found or not cancellable"}}))))

(defn resume-execution
  [datasource]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          wf (wf-repo/get-workflow datasource (get-in request [:body :workflow-id]))
          result (when wf (engine/resume-execution! datasource id wf))]
      (if result
        {:status 200
         :body {:execution-id (:execution-id result) :status (:status result)}}
        {:status 404
          :body {:error "Execution not found or not resumable"}}))))

(defn retry-execution
  [datasource]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          body (:body request)
          wf (when (:workflow-id body) (wf-repo/get-workflow datasource (:workflow-id body)))
          result (when wf (engine/retry-execution! datasource id wf))]
      (if result
        {:status 200
         :body {:execution-id (:execution-id result) :status (:status result)}}
        {:status 404
         :body {:error "Execution not found or not retriable"}}))))

(defn health-check [_request]
  {:status 200
   :body {:status "ok" :version version/version}})
