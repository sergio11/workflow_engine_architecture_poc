(ns workflow-engine.persistence.db-config)

(defn from-env []
  {:db-name (or (System/getenv "DB_NAME") "workflow_engine")
   :db-user (or (System/getenv "DB_USER") "workflow_engine")
   :db-password (or (System/getenv "DB_PASSWORD") "workflow_dev")
   :db-host (or (System/getenv "DB_HOST") "db")
   :db-port (or (some-> (System/getenv "DB_PORT") Integer/parseInt) 5432)})

(defn test-config []
  {:db-name (or (System/getenv "DB_NAME") "workflow_engine_test")
   :db-user (or (System/getenv "DB_USER") "workflow_engine_test")
   :db-password (or (System/getenv "DB_PASSWORD") "test_secret")
   :db-host (or (System/getenv "DB_HOST") "test-db")
   :db-port (or (some-> (System/getenv "DB_PORT") Integer/parseInt) 5432)})
