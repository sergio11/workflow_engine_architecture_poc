(ns workflow-engine.persistence.db-config)

(defn get-env [k]
  (System/getenv k))

(defn from-env []
  {:db-name (or (get-env "DB_NAME") "workflow_engine")
   :db-user (or (get-env "DB_USER") "workflow_engine")
   :db-password (or (get-env "DB_PASSWORD") "workflow_dev")
   :db-host (or (get-env "DB_HOST") "db")
   :db-port (or (some-> (get-env "DB_PORT") Integer/parseInt) 5432)})

(defn test-config []
  {:db-name (or (get-env "DB_NAME") "workflow_engine_test")
   :db-user (or (get-env "DB_USER") "workflow_engine_test")
   :db-password (or (get-env "DB_PASSWORD") "test_secret")
   :db-host (or (get-env "DB_HOST") "test-db")
   :db-port (or (some-> (get-env "DB_PORT") Integer/parseInt) 5432)})
