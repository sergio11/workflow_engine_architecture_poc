(ns workflow-engine.execution.engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as workflow-repo]
            [cheshire.core :as json]))

(def test-datasource (atom nil))
(def test-workflow
  (dsl/linear-workflow "test-wf" "Test" 1
    [[:step-a :task (fn [ctx] {:result "a-done"})]
     [:step-b :task (fn [ctx] {:result "b-done"})]
     [:step-c :task (fn [ctx] {:result "c-done"})]]))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (db/execute! ds ["DELETE FROM events"])
    (db/execute! ds ["DELETE FROM executions"])
    (db/execute! ds ["DELETE FROM workflows"])
    (workflow-repo/save-workflow! ds test-workflow)
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (db/close-datasource! ds)
        (reset! test-datasource nil)))))

(use-fixtures :once db-fixture)

(deftest start-execution-test
  (testing "starts a new execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "123"})]
      (is (some? (:execution-id exec)))
      (is (= "test-wf" (:workflow-id exec)))
      (is (= :pending (:status exec))))))

(deftest execute-step-test
  (testing "executes first step"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "123"})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-datasource running test-workflow)]
      (is (= :completed (:status result)))
      (is (= :step-b (:current-step result))))))

(deftest cancel-execution-test
  (testing "cancels a running execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'running' WHERE id = ?" (:execution-id exec)])
          cancelled (engine/cancel-execution! @test-datasource (:execution-id exec))]
      (is (= :cancelled (:status cancelled))))))

(deftest retry-execution-test
  (testing "retries a failed execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'failed' WHERE id = ?" (:execution-id exec)])
          retried (engine/retry-execution! @test-datasource (:execution-id exec) test-workflow)]
      (is (= :completed (:status retried))))))
