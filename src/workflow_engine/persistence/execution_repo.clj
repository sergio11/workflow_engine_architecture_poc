(ns workflow-engine.persistence.execution-repo
  (:require [workflow-engine.persistence.db :as db]
            [cheshire.core :as json]
            [cheshire.generate :as gen]
            [clojure.string :as str]))

(gen/add-encoder java.time.Instant
  (fn [instant generator]
    (.writeString generator (str instant))))

(defn save-execution!
  [datasource execution]
  (db/execute-one! datasource
   ["INSERT INTO executions (id, workflow_id, status, current_step, context) VALUES (?, ?, ?, ?, ?::jsonb)"
    (:execution-id execution)
    (:workflow-id execution)
    (name (:status execution))
    (name (or (:current-step execution) ""))
    (json/generate-string (:context execution))]))

(defn- instant->str [t] (when t (str t)))

(defn update-execution!
  [datasource execution-id updates]
  (let [set-clauses (cond-> []
                      (:status updates) (conj "status = ?")
                      (contains? updates :current-step) (conj "current_step = ?")
                      (:context updates) (conj "context = ?::jsonb")
                      (:started-at updates) (conj "started_at = ?::timestamptz")
                      (:completed-at updates) (conj "completed_at = ?::timestamptz"))
        values (cond-> []
                 (:status updates) (conj (name (:status updates)))
                 (contains? updates :current-step) (conj (if-let [cs (:current-step updates)] (name cs) nil))
                 (:context updates) (conj (json/generate-string (:context updates)))
                 (:started-at updates) (conj (instant->str (:started-at updates)))
                 (:completed-at updates) (conj (instant->str (:completed-at updates))))
        sql (str "UPDATE executions SET " (str/join ", " set-clauses) ", updated_at = NOW() WHERE id = ?")]
    (db/execute! datasource (into [sql] (conj values execution-id)))))

(defn get-execution
  [datasource execution-id]
  (let [row (db/execute-one! datasource
              ["SELECT id, workflow_id, status, current_step, context::text as context, started_at, completed_at, created_at FROM executions WHERE id = ?" execution-id])]
    (when row
      {:execution-id (:id row)
       :workflow-id (:workflow_id row)
       :status (keyword (:status row))
       :current-step (keyword (:current_step row))
       :context (json/parse-string (:context row) true)
       :started-at (:started_at row)
       :completed-at (:completed_at row)
       :created-at (:created_at row)})))

(defn list-executions
  [datasource workflow-id]
  (let [rows (db/execute! datasource
               ["SELECT id, status, current_step, started_at, completed_at FROM executions WHERE workflow_id = ? ORDER BY created_at DESC" workflow-id])]
    (mapv (fn [r] {:execution-id (:id r)
                   :status (keyword (:status r))
                   :current-step (keyword (:current_step r))
                   :started-at (:started_at r)
                   :completed-at (:completed_at r)}) rows)))
