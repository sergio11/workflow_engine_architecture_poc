(ns workflow-engine.persistence.event-repo
  (:require [workflow-engine.persistence.db :as db]
            [cheshire.core :as json]))

(defn save-event!
  [datasource event]
  (db/execute-one! datasource
   ["INSERT INTO events (execution_id, type, step, payload) VALUES (?, ?, ?, ?::jsonb)"
    (:execution-id event)
    (name (:type event))
    (name (or (:step event) ""))
    (json/generate-string (:data event))]))

(defn get-events-by-execution
  [datasource execution-id]
  (let [rows (db/execute! datasource
               ["SELECT id, execution_id, type, step, payload::text as payload, timestamp FROM events WHERE execution_id = ? ORDER BY timestamp ASC" execution-id])]
    (mapv (fn [r] {:id (:id r)
                   :execution-id (:execution_id r)
                   :type (keyword (:type r))
                   :step (keyword (:step r))
                   :data (json/parse-string (:payload r) true)
                   :timestamp (:timestamp r)}) rows)))

(defn get-events-by-type
  [datasource event-type]
  (let [rows (db/execute! datasource
               ["SELECT id, execution_id, type, step, payload::text as payload, timestamp FROM events WHERE type = ? ORDER BY timestamp DESC" (name event-type)])]
    (mapv (fn [r] {:id (:id r)
                   :execution-id (:execution_id r)
                   :type (keyword (:type r))
                   :step (keyword (:step r))
                   :data (json/parse-string (:payload r) true)
                   :timestamp (:timestamp r)}) rows)))

(defn delete-events-by-execution!
  [datasource execution-id]
  (db/execute! datasource ["DELETE FROM events WHERE execution_id = ?" execution-id]))
