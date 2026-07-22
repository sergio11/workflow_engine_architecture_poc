(ns workflow-engine.persistence.execution-repo-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.execution-repo :as repo]
            [workflow-engine.workflow.model :as model]))

(def test-datasource (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (db/execute! ds ["DELETE FROM events"])
    (db/execute! ds ["DELETE FROM executions"])
    (db/execute! ds ["DELETE FROM workflows"])
    (db/execute! ds ["INSERT INTO workflows (id, name, version, definition) VALUES ('test-wf', 'Test', 1, '{}')"])
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (db/close-datasource! ds)
        (reset! test-datasource nil)))))

(use-fixtures :once db-fixture)

(deftest save-and-get-execution-test
  (testing "saves and retrieves an execution"
    (let [exec (model/make-execution "exec-1" "test-wf" {:user "123"})
          _ (repo/save-execution! @test-datasource exec)
          retrieved (repo/get-execution @test-datasource "exec-1")]
      (is (= "exec-1" (:execution-id retrieved)))
      (is (= "test-wf" (:workflow-id retrieved)))
      (is (= :pending (:status retrieved)))
      (is (= {:user "123"} (:context retrieved))))))

(deftest update-execution-test
  (testing "updates execution status"
    (let [exec (model/make-execution "exec-2" "test-wf" {})
          _ (repo/save-execution! @test-datasource exec)
          _ (repo/update-execution! @test-datasource "exec-2" {:status :running :current-step :step1})
          retrieved (repo/get-execution @test-datasource "exec-2")]
      (is (= :running (:status retrieved)))
      (is (= :step1 (:current-step retrieved))))))
