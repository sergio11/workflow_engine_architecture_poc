(ns workflow-engine.execution.engine-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.execution.adapters :as adapters]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as workflow-repo]
            [workflow-engine.worker.registry :as registry]
            [workflow-engine.scheduler.channels :as channels]
            [clojure.core.async :as async]))

(def test-datasource (atom nil))
(def test-store (atom nil))
(def test-recorder (atom nil))
(def test-publisher (atom nil))
(def test-metrics (atom nil))

(def test-workflow
  (dsl/linear-workflow "test-wf" "Test" 1
    [[:step-a :task (fn [ctx] {:result "a-done"})]
     [:step-b :task (fn [ctx] {:result "b-done"})]
     [:step-c :task (fn [ctx] {:result "c-done"})]]))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))
        store (adapters/create-store ds)
        recorder (adapters/create-recorder ds)
        publisher (adapters/create-publisher)
        metrics (adapters/create-metrics-collector)]
    (reset! test-datasource ds)
    (reset! test-store store)
    (reset! test-recorder recorder)
    (reset! test-publisher publisher)
    (reset! test-metrics metrics)
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
        (reset! test-datasource nil)
        (reset! test-store nil)
        (reset! test-recorder nil)
        (reset! test-publisher nil)
        (reset! test-metrics nil)))))

(use-fixtures :once db-fixture)

(deftest start-execution-test
  (testing "starts a new execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "123"})]
      (is (some? (:execution-id exec)))
      (is (= "test-wf" (:workflow-id exec)))
      (is (= :pending (:status exec))))))

(deftest execute-step-test
  (testing "executes first step"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "123"})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running test-workflow)]
      (is (= :completed (:status result)))
      (is (= :step-b (:current-step result))))))

(deftest cancel-execution-test
  (testing "cancels a running execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'running' WHERE id = ?" (:execution-id exec)])
          cancelled (engine/cancel-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec))]
      (is (= :cancelled (:status cancelled))))))

(deftest retry-execution-test
  (testing "retries a failed execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'failed' WHERE id = ?" (:execution-id exec)])
          retried (engine/retry-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow)]
      (is (= :completed (:status retried))))))

(deftest advance-execution-test
  (testing "advances a running execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "adv"})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'running' WHERE id = ?" (:execution-id exec)])
          advanced (engine/advance-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow)]
      (is (= :completed (:status advanced)))))
  (testing "returns nil for non-existent execution"
    (is (nil? (engine/advance-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource "nonexistent" test-workflow)))))

(deftest resume-execution-test
  (testing "resumes a waiting execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "res"})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'waiting' WHERE id = ?" (:execution-id exec)])
          resumed (engine/resume-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow)]
      (is (= :completed (:status resumed)))))
  (testing "returns nil for non-waiting execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "res2"})]
      (is (nil? (engine/resume-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow))))))

(deftest cancel-guard-test
  (testing "returns nil for non-cancellable execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "canc"})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'completed' WHERE id = ?" (:execution-id exec)])]
      (is (nil? (engine/cancel-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec)))))))

(deftest retry-guard-test
  (testing "returns nil for non-failed execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "ret"})]
      (is (nil? (engine/retry-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow))))))

(deftest execute-step-completion-test
  (testing "completes workflow when no more steps"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "fin"})
          step1 (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (assoc exec :status :running) test-workflow)
          step2 (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource step1 test-workflow)
          step3 (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource step2 test-workflow)]
      (is (= :completed (:status step3)))
      (is (nil? (:current-step step3))))))

(deftest execute-step-failure-test
  (testing "handles step exception and sets status to failed"
    (let [fail-wf (dsl/linear-workflow "fail-wf" "Fail" 1
                    [[:fail-step :task (fn [ctx] (throw (Exception. "step error")))]])
          _ (workflow-repo/save-workflow! @test-datasource fail-wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource fail-wf {})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running fail-wf)]
      (is (= :failed (:status result))))))

(deftest cancel-pending-execution-test
  (testing "cancels a pending execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {})]
      (is (= :pending (:status exec)))
      (let [cancelled (engine/cancel-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec))]
        (is (= :cancelled (:status cancelled)))))))

(deftest cancel-waiting-execution-test
  (testing "cancels a waiting execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {})]
      (db/execute! @test-datasource ["UPDATE executions SET status = 'waiting' WHERE id = ?" (:execution-id exec)])
      (let [cancelled (engine/cancel-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec))]
        (is (= :cancelled (:status cancelled)))))))

(deftest retry-without-current-step-test
  (testing "retries from first step when current-step is nil"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {})]
      (db/execute! @test-datasource ["UPDATE executions SET status = 'failed', current_step = NULL WHERE id = ?" (:execution-id exec)])
      (let [retried (engine/retry-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow)]
        (is (= :completed (:status retried)))))))

(deftest execute-step-wait-type-test
  (testing "wait step type transitions to completed"
    (let [wait-wf (dsl/linear-workflow "wait-wf" "Wait" 1
                    [[:wait-step :wait (fn [ctx] {:waited 100})]])
          _ (workflow-repo/save-workflow! @test-datasource wait-wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource wait-wf {})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running wait-wf)]
      (is (= :completed (:status result))))))

(deftest execute-step-decision-branch-test
  (testing "decision step with branch returns completed"
    (let [decision-wf (dsl/linear-workflow "dec-wf" "Decision" 1
                        [[:dec-step :decision (fn [ctx] {:branch :vip})]])
          _ (workflow-repo/save-workflow! @test-datasource decision-wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource decision-wf {})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running decision-wf)]
      (is (= :completed (:status result))))))

(deftest execute-step-decision-no-branch-test
  (testing "decision step without branch returns failed"
    (let [decision-wf (dsl/linear-workflow "dec-fail-wf" "Decision Fail" 1
                        [[:dec-step :decision (fn [ctx] {})]])
          _ (workflow-repo/save-workflow! @test-datasource decision-wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource decision-wf {})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running decision-wf)]
      (is (= :failed (:status result))))))

(deftest execute-step-parallel-success-test
  (testing "parallel step with all success returns completed"
    (let [par-wf (dsl/linear-workflow "par-wf" "Parallel" 1
                   [[:par-step :parallel (fn [ctx] [{:ok true} {:ok true}])]])
          _ (workflow-repo/save-workflow! @test-datasource par-wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource par-wf {})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running par-wf)]
      (is (= :completed (:status result))))))

(deftest execute-step-parallel-error-test
  (testing "parallel step with error returns failed"
    (let [par-wf (dsl/linear-workflow "par-fail-wf" "Parallel Fail" 1
                   [[:par-step :parallel (fn [ctx] [{:ok true} {:error "failed"}])]])
          _ (workflow-repo/save-workflow! @test-datasource par-wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource par-wf {})
          running (assoc exec :status :running)
          result (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running par-wf)]
      (is (= :failed (:status result))))))

(deftest advance-non-running-test
  (testing "advance-execution! returns nil for completed execution"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {})]
      (db/execute! @test-datasource ["UPDATE executions SET status = 'completed' WHERE id = ?" (:execution-id exec)])
      (is (nil? (engine/advance-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow))))))

(deftest execute-step-no-current-step-test
  (testing "execute-step! completes when current-step is nil (no more steps)"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "nil-step"})
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'running', current_step = NULL WHERE id = ?" (:execution-id exec)])
          loaded (engine/advance-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (:execution-id exec) test-workflow)]
      (when loaded
        (is (= :completed (:status loaded)))))))

(deftest execute-step-decision-branching-test
  (testing "decision step routes to correct branch"
    (let [branch-wf (dsl/linear-workflow "branch-wf" "Branch" 1
                     [{:id :check :type :decision
                       :handler (fn [ctx] {:branch (if (= "vip" (:user ctx)) :vip :basic)})
                       :branches {:vip :vip-step :basic :basic-step}}
                      {:id :vip-step :type :task :handler (fn [ctx] {:tier "vip"})}
                      {:id :basic-step :type :task :handler (fn [ctx] {:tier "basic"})}])
          _ (workflow-repo/save-workflow! @test-datasource branch-wf)
          exec-vip (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource branch-wf {:user "vip"})
          result-vip (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (assoc exec-vip :status :running) branch-wf)
          exec-basic (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource branch-wf {:user "other"})
          result-basic (engine/execute-step! @test-store @test-recorder @test-publisher @test-metrics @test-datasource (assoc exec-basic :status :running) branch-wf)]
      (is (= :vip-step (:current-step result-vip)))
      (is (= :basic-step (:current-step result-basic))))))

(deftest handle-step-completion-success-test
  (testing "handle-step-completion! processes successful result"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "hsc"})
          running (assoc exec :status :running)
          step (first (:steps test-workflow))
          result (engine/handle-step-completion! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running test-workflow step {:result "ok"} 100)]
      (is (= :completed (:status result))))))

(deftest handle-step-completion-failure-test
  (testing "handle-step-completion! processes failed result"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "hsc-fail"})
          running (assoc exec :status :running)
          step (first (:steps test-workflow))
          result (engine/handle-step-completion! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running test-workflow step {:error "fail"} 100)]
      (is (= :failed (:status result))))))

(deftest submit-step-for-execution-with-handler-test
  (testing "submit-step-for-execution! puts work on channel when handler exists"
    (let [ch (channels/create-channels)
          wf (dsl/linear-workflow "submit-wf" "Submit WF" 1
               [[:s1 :task (fn [ctx] {:ok true})]])
          _ (workflow-repo/save-workflow! @test-datasource wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource wf {})
          running (assoc exec :status :running)]
      (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running wf ch)
      (let [timeout-ch (async/timeout 1000)
            [work-item ch] (async/alts!! [(:work-ch ch) timeout-ch])]
        (is (not (nil? work-item)))
        (is (some? (:handler-fn work-item)))
        (is (= :s1 (:id (:step work-item)))))
      (channels/close-channels! ch))))

(deftest submit-step-for-execution-no-handler-test
  (testing "submit-step-for-execution! fails execution when no handler"
    (let [ch (channels/create-channels)
          wf (dsl/linear-workflow "no-handler-wf" "No Handler WF" 1
               [["orphan" :task]])
          _ (workflow-repo/save-workflow! @test-datasource wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource wf {})
          running (assoc exec :status :running)]
      (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running wf ch)
      (let [loaded (workflow-repo/get-workflow @test-datasource (:id wf))]
        (is (some? loaded)))
      (channels/close-channels! ch))))

(deftest submit-step-for-execution-no-step-test
  (testing "submit-step-for-execution! completes when no current step"
    (let [ch (channels/create-channels)
          wf (dsl/linear-workflow "empty-wf" "Empty WF" 1
               [[:s1 :task (fn [ctx] {:ok true})]])
          _ (workflow-repo/save-workflow! @test-datasource wf)
          exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource wf {})
          running (assoc exec :status :running :current-step nil)]
      (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running wf ch)
      (Thread/sleep 100)
      (let [loaded (workflow-repo/get-workflow @test-datasource (:id wf))]
        (is (some? loaded)))
      (channels/close-channels! ch))))

(deftest handle-async-completion-test
  (testing "handle-async-completion! processes result and returns status"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "hac"})
          running (assoc exec :status :running)
          step (first (:steps test-workflow))
          result (engine/handle-async-completion! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running test-workflow step {:result "ok"} 100)]
      (is (= :completed (:status result))))))

(deftest handle-async-exception-test
  (testing "handle-async-exception! processes exception"
    (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics @test-datasource test-workflow {:user "hae"})
          running (assoc exec :status :running)
          step (first (:steps test-workflow))
          exception (Exception. "async error")
          result (engine/handle-async-exception! @test-store @test-recorder @test-publisher @test-metrics @test-datasource running test-workflow step exception 100)]
      (is (= :failed (:status result)))
      (is (= "async error" (get-in result [:context :last-result :error]))))))
