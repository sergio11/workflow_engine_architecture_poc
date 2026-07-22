(ns workflow-engine.persistence.workflow-repo
  (:require [workflow-engine.persistence.db :as db]
            [cheshire.core :as json]))

(defn save-workflow!
  [datasource workflow]
  (db/execute-one! datasource
   ["INSERT INTO workflows (id, name, version, definition) VALUES (?, ?, ?, ?::jsonb)
     ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, version = EXCLUDED.version, definition = EXCLUDED.definition, updated_at = NOW()"
    (:id workflow)
    (:name workflow)
    (:version workflow)
    (json/generate-string workflow)]))

(defn get-workflow
  [datasource workflow-id]
  (let [row (db/execute-one! datasource
              ["SELECT id, name, version, definition::text as definition FROM workflows WHERE id = ?" workflow-id])]
    (when row
      (let [wf (json/parse-string (:definition row) true)]
        (update wf :steps (fn [steps]
                            (mapv (fn [s] (update s :type keyword)) steps)))))))

(defn list-workflows
  [datasource]
  (let [rows (db/execute! datasource
               ["SELECT id, name, version, created_at FROM workflows ORDER BY created_at DESC"])]
    (mapv #(assoc % :definition nil) rows)))

(defn delete-workflow!
  [datasource workflow-id]
  (db/execute! datasource ["DELETE FROM workflows WHERE id = ?" workflow-id]))
