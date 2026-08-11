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

(deftest list-executions-test
  (testing "lists executions for a workflow"
    (let [exec1 (model/make-execution "exec-list-1" "test-wf" {:user "a"})
          exec2 (model/make-execution "exec-list-2" "test-wf" {:user "b"})
          _ (repo/save-execution! @test-datasource exec1)
          _ (repo/save-execution! @test-datasource exec2)
          results (repo/list-executions @test-datasource "test-wf")]
      (is (>= (count results) 2))))
  (testing "returns empty for non-existent workflow"
    (let [results (repo/list-executions @test-datasource "nonexistent")]
      (is (= 0 (count results))))))

(deftest update-context-test
  (testing "updates execution context"
    (let [exec (model/make-execution "exec-ctx" "test-wf" {:step1 "old"})
          _ (repo/save-execution! @test-datasource exec)
          _ (repo/update-execution! @test-datasource "exec-ctx" {:context {:step1 "new" :step2 "added"}})
          retrieved (repo/get-execution @test-datasource "exec-ctx")]
      (is (= {:step1 "new" :step2 "added"} (:context retrieved))))))

(deftest update-started-at-test
  (testing "updates execution with started_at timestamp"
    (let [exec (model/make-execution "exec-sa" "test-wf" {})
          _ (repo/save-execution! @test-datasource exec)
          now (java.time.Instant/now)
          _ (repo/update-execution! @test-datasource "exec-sa" {:status :running :started-at now})
          retrieved (repo/get-execution @test-datasource "exec-sa")]
      (is (= :running (:status retrieved)))
      (is (some? (:started-at retrieved))))))

(deftest update-completed-at-test
  (testing "updates execution with completed_at timestamp"
    (let [exec (model/make-execution "exec-ca" "test-wf" {})
          _ (repo/save-execution! @test-datasource exec)
          now (java.time.Instant/now)
          _ (repo/update-execution! @test-datasource "exec-ca" {:status :completed :completed-at now})
          retrieved (repo/get-execution @test-datasource "exec-ca")]
      (is (= :completed (:status retrieved)))
      (is (some? (:completed-at retrieved))))))

(deftest get-execution-not-found-test
  (testing "returns nil for non-existent execution"
    (is (nil? (repo/get-execution @test-datasource "nonexistent-id")))))

(deftest list-executions-empty-test
  (testing "returns empty for workflow with no executions"
    (let [results (repo/list-executions @test-datasource "no-exec-wf")]
      (is (= [] results)))))
