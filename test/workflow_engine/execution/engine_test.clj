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

(deftest advance-execution-test
  (testing "advances a running execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "adv"})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'running' WHERE id = ?" (:execution-id exec)])
          advanced (engine/advance-execution! @test-datasource (:execution-id exec) test-workflow)]
      (is (= :completed (:status advanced)))))
  (testing "returns nil for non-existent execution"
    (is (nil? (engine/advance-execution! @test-datasource "nonexistent" test-workflow)))))

(deftest resume-execution-test
  (testing "resumes a waiting execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "res"})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'waiting' WHERE id = ?" (:execution-id exec)])
          resumed (engine/resume-execution! @test-datasource (:execution-id exec) test-workflow)]
      (is (= :completed (:status resumed)))))
  (testing "returns nil for non-waiting execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "res2"})]
      (is (nil? (engine/resume-execution! @test-datasource (:execution-id exec) test-workflow))))))

(deftest cancel-guard-test
  (testing "returns nil for non-cancellable execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "canc"})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'completed' WHERE id = ?" (:execution-id exec)])]
      (is (nil? (engine/cancel-execution! @test-datasource (:execution-id exec)))))))

(deftest retry-guard-test
  (testing "returns nil for non-failed execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "ret"})]
      (is (nil? (engine/retry-execution! @test-datasource (:execution-id exec) test-workflow))))))

(deftest execute-step-completion-test
  (testing "completes workflow when no more steps"
    (let [exec (engine/start-execution! @test-datasource test-workflow {:user "fin"})
          step1 (engine/execute-step! @test-datasource (assoc exec :status :running) test-workflow)
          step2 (engine/execute-step! @test-datasource step1 test-workflow)
          step3 (engine/execute-step! @test-datasource step2 test-workflow)]
      (is (= :completed (:status step3)))
      (is (nil? (:current-step step3))))))

(deftest execute-step-failure-test
  (testing "handles step exception and sets status to failed"
    (let [fail-wf (dsl/linear-workflow "fail-wf" "Fail" 1
                    [[:fail-step :task (fn [ctx] (throw (Exception. "step error")))]])
          _ (workflow-repo/save-workflow! @test-datasource fail-wf)
          exec (engine/start-execution! @test-datasource fail-wf {})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-datasource running fail-wf)]
      (is (= :failed (:status result))))))

(deftest cancel-pending-execution-test
  (testing "cancels a pending execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {})]
      (is (= :pending (:status exec)))
      (let [cancelled (engine/cancel-execution! @test-datasource (:execution-id exec))]
        (is (= :cancelled (:status cancelled)))))))

(deftest cancel-waiting-execution-test
  (testing "cancels a waiting execution"
    (let [exec (engine/start-execution! @test-datasource test-workflow {})]
      (db/execute! @test-datasource ["UPDATE executions SET status = 'waiting' WHERE id = ?" (:execution-id exec)])
      (let [cancelled (engine/cancel-execution! @test-datasource (:execution-id exec))]
        (is (= :cancelled (:status cancelled)))))))

(deftest retry-without-current-step-test
  (testing "retries from first step when current-step is nil"
    (let [exec (engine/start-execution! @test-datasource test-workflow {})]
      (db/execute! @test-datasource ["UPDATE executions SET status = 'failed', current_step = NULL WHERE id = ?" (:execution-id exec)])
      (let [retried (engine/retry-execution! @test-datasource (:execution-id exec) test-workflow)]
        (is (= :completed (:status retried)))))))
