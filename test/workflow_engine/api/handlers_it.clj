(ns workflow-engine.api.handlers-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.api.handlers :as handlers]
            [workflow-engine.execution.adapters :as adapters]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.persistence.execution-repo :as exec-repo]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.worker.registry :as registry]
            [workflow-engine.scheduler.channels :as channels]))

(def test-datasource (atom nil))
(def test-channels (atom nil))
(def test-store (atom nil))
(def test-recorder (atom nil))
(def test-publisher (atom nil))
(def test-metrics (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))
        ch (channels/create-channels)
        store (adapters/create-store ds)
        recorder (adapters/create-recorder ds)
        pub (adapters/create-publisher)
        metrics-adapter (adapters/create-metrics-collector)]
    (reset! test-datasource ds)
    (reset! test-channels ch)
    (reset! test-store store)
    (reset! test-recorder recorder)
    (reset! test-publisher pub)
    (reset! test-metrics metrics-adapter)
    (registry/clear-registry!)
    (db/execute! ds ["DELETE FROM events"])
    (db/execute! ds ["DELETE FROM executions"])
    (db/execute! ds ["DELETE FROM workflows"])
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (channels/close-channels! ch)
        (db/close-datasource! ds)
        (reset! test-datasource nil)
        (reset! test-channels nil)
        (reset! test-store nil)
        (reset! test-recorder nil)
        (reset! test-publisher nil)
        (reset! test-metrics nil)
        (registry/clear-registry!)))))

(use-fixtures :once db-fixture)

(deftest health-check-test
  (testing "returns health status"
    (let [response (handlers/health-check {})]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status]))))))

(deftest create-and-get-workflow-test
  (testing "creates and retrieves a workflow"
    (let [create-handler (handlers/create-workflow @test-datasource @test-store @test-recorder @test-publisher @test-metrics)
          create-req {:body {:name "Test WF" :version 1
                             :steps [[:s1 :task] [:s2 :task]]}}
          create-res (create-handler create-req)]
      (is (= 201 (:status create-res)))
      (is (some? (get-in create-res [:body :id])))
      (let [wf-id (get-in create-res [:body :id])
            get-handler (handlers/get-workflow @test-datasource)
            get-res (get-handler {:path-params {:id wf-id}})]
        (is (= 200 (:status get-res)))
        (is (= "Test WF" (get-in get-res [:body :name])))))))

(deftest get-workflow-not-found-test
  (testing "returns 404 for missing workflow"
    (let [handler (handlers/get-workflow @test-datasource)
          response (handler {:path-params {:id "nonexistent"}})]
      (is (= 404 (:status response))))))

(deftest list-workflows-test
  (testing "lists workflows"
    (let [wf (model/make-workflow "list-wf" "List Me" 1 [])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [handler (handlers/list-workflows @test-datasource)
          response (handler {})]
      (is (= 200 (:status response)))
      (is (>= (count (:body response)) 1)))))

(deftest start-execution-test
  (testing "starts an execution for existing workflow"
    (let [wf (model/make-workflow "exec-wf" "Exec WF" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:body {:workflow-id "exec-wf" :input {:user "123"}}})]
      (is (= 202 (:status response)))
      (is (some? (get-in response [:body :execution-id])))
      (is (= :pending (keyword (get-in response [:body :status])))))))

(deftest start-execution-workflow-not-found-test
  (testing "returns 404 when workflow not found"
    (let [handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:body {:workflow-id "nonexistent"}})]
      (is (= 404 (:status response))))))

(deftest delete-workflow-test
  (testing "deletes an existing workflow"
    (let [wf (model/make-workflow "del-wf" "Delete Me" 1 [])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [handler (handlers/delete-workflow @test-datasource)
          response (handler {:path-params {:id "del-wf"}})]
      (is (= 204 (:status response))))))

(deftest get-execution-test
  (testing "gets an existing execution"
    (let [wf (model/make-workflow "exec-g-wf" "Exec GW" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          start-res (start-handler {:body {:workflow-id "exec-g-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          get-handler (handlers/get-execution @test-datasource)
          response (get-handler {:path-params {:id exec-id}})]
      (is (= 200 (:status response)))
      (is (= exec-id (get-in response [:body :execution-id])))))
  (testing "returns 404 for missing execution"
    (let [handler (handlers/get-execution @test-datasource)
          response (handler {:path-params {:id "nonexistent"}})]
      (is (= 404 (:status response))))))

(deftest list-executions-test
  (testing "lists executions for a workflow"
    (let [wf (model/make-workflow "list-ex-wf" "List EX" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          _ (start-handler {:body {:workflow-id "list-ex-wf" :input {:user "a"}}})
          list-handler (handlers/list-executions @test-datasource)
          response (list-handler {:query-params {"workflow_id" "list-ex-wf"}})]
      (is (= 200 (:status response)))
      (is (vector? (:body response))))))

(deftest cancel-execution-test
  (testing "cancels a running execution"
    (let [wf (model/make-workflow "cancel-wf" "Cancel WF" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          start-res (start-handler {:body {:workflow-id "cancel-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'running' WHERE id = ?" exec-id])
          cancel-handler (handlers/cancel-execution @test-datasource @test-store @test-recorder @test-publisher @test-metrics)
          response (cancel-handler {:path-params {:id exec-id}})]
      (is (= 200 (:status response)))))
  (testing "returns 404 for non-existent execution"
    (let [handler (handlers/cancel-execution @test-datasource @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:path-params {:id "nonexistent"}})]
      (is (= 404 (:status response))))))

(deftest resume-execution-test
  (testing "resumes a waiting execution"
    (let [wf (model/make-workflow "resume-wf" "Resume WF" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          start-res (start-handler {:body {:workflow-id "resume-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'waiting', current_step = 's1' WHERE id = ?" exec-id])
          resume-handler (handlers/resume-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (resume-handler {:path-params {:id exec-id} :body {:workflow-id "resume-wf"}})]
      (is (= 200 (:status response)))))
  (testing "returns 404 for non-existent execution"
    (let [handler (handlers/resume-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:path-params {:id "nonexistent"} :body {:workflow-id "resume-wf"}})]
      (is (= 404 (:status response))))))

(deftest create-workflow-validation-test
  (testing "returns 400 for workflow without name"
    (let [handler (handlers/create-workflow @test-datasource @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:body {:steps [[:s1 :task]]}})]
      (is (= 400 (:status response)))
      (is (some? (get-in response [:body :details])))))
  (testing "returns 400 for workflow without steps"
    (let [handler (handlers/create-workflow @test-datasource @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:body {:name "Empty"}})]
      (is (= 400 (:status response))))))

(deftest retry-execution-test
  (testing "retries a failed execution"
    (let [handler-fn (fn [_] {:ok true})
          wf (model/make-workflow "retry-wf" "Retry WF" 1
               [(model/make-step :s1 :task handler-fn)])]
      (doseq [s (:steps wf)]
        (registry/register-handler! (:id s) (:handler s)))
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          start-res (start-handler {:body {:workflow-id "retry-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'failed', current_step = 's1' WHERE id = ?" exec-id])
          retry-handler (handlers/retry-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (retry-handler {:path-params {:id exec-id} :body {:workflow-id "retry-wf"}})]
      (is (= 200 (:status response)))
      (is (= :running (keyword (get-in response [:body :status])))))))

(deftest retry-execution-not-found-test
  (testing "returns 404 for non-existent execution on retry"
    (let [wf (model/make-workflow "retry-nf-wf" "Retry NF" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [handler (handlers/retry-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:path-params {:id "nonexistent"} :body {:workflow-id "retry-nf-wf"}})]
      (is (= 404 (:status response))))))

(deftest list-executions-no-workflow-id-test
  (testing "returns empty when no workflow_id query param"
    (let [handler (handlers/list-executions @test-datasource)
          response (handler {:query-params {}})]
      (is (= 200 (:status response)))
      (is (= [] (:body response))))))

(deftest resume-execution-not-resumable-test
  (testing "returns 400 for non-waiting execution on resume"
    (let [wf (model/make-workflow "resume-nr-wf" "Resume NR" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          start-res (start-handler {:body {:workflow-id "resume-nr-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          handler (handlers/resume-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:path-params {:id exec-id} :body {:workflow-id "resume-nr-wf"}})]
      (is (= 400 (:status response))))))

(deftest retry-execution-no-workflow-id-test
  (testing "returns 404 when workflow-id is missing from body"
    (let [handler (handlers/retry-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:path-params {:id "some-id"} :body {}})]
      (is (= 404 (:status response))))))

(deftest resume-execution-no-workflow-test
  (testing "returns 404 when workflow does not exist"
    (let [handler (handlers/resume-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:path-params {:id "some-id"} :body {:workflow-id "nonexistent"}})]
      (is (= 404 (:status response))))))

(deftest retry-execution-nonexistent-workflow-test
  (testing "returns 404 when workflow does not exist on retry"
    (let [handler (handlers/retry-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:path-params {:id "some-id"} :body {:workflow-id "nonexistent"}})]
      (is (= 404 (:status response))))))

(deftest retry-execution-no-current-step-test
  (testing "retries failed execution without current-step using first workflow step"
    (let [wf (model/make-workflow "retry-ncs-wf" "Retry NCS" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (doseq [s (:steps wf)]
        (registry/register-handler! (:id s) (:handler s)))
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          start-res (start-handler {:body {:workflow-id "retry-ncs-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'failed', current_step = NULL WHERE id = ?" exec-id])
          retry-handler (handlers/retry-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (retry-handler {:path-params {:id exec-id} :body {:workflow-id "retry-ncs-wf"}})]
      (is (= 200 (:status response)))
      (is (= :running (keyword (get-in response [:body :status])))))))

(deftest retry-execution-not-failed-state-test
  (testing "returns 400 when execution is not in failed state"
    (let [wf (model/make-workflow "retry-nf-st-wf" "Retry NF State" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          start-res (start-handler {:body {:workflow-id "retry-nf-st-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          _ (db/execute! @test-datasource
               ["UPDATE executions SET status = 'running' WHERE id = ?" exec-id])
          retry-handler (handlers/retry-execution @test-datasource @test-channels @test-store @test-recorder @test-publisher @test-metrics)
          response (retry-handler {:path-params {:id exec-id}
                                   :body {:workflow-id "retry-nf-st-wf"}})]
      (is (= 400 (:status response)))
      (is (= "Execution is not in failed state"
             (get-in response [:body :error]))))))

(deftest create-workflow-with-map-steps-and-handlers-test
  (testing "registers handlers when steps are maps with :handler keys"
    (registry/clear-registry!)
    (let [handler-fn (fn [ctx] {:ok true})
          handler (handlers/create-workflow @test-datasource @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:body {:name "Map Handler WF"
                                    :version 1
                                    :steps [{:id :s1 :type :task :handler handler-fn}]}})]
      (is (= 201 (:status response)))
      (is (= handler-fn (registry/get-handler :s1))))))

(deftest create-workflow-with-handlers-test
  (testing "creates workflow with handler functions"
    (let [handler (handlers/create-workflow @test-datasource @test-store @test-recorder @test-publisher @test-metrics)
          response (handler {:body {:name "Handler WF"
                                    :version 1
                                    :steps [[:s1 :task (fn [ctx] {:ok true})]]}})]
      (is (= 201 (:status response)))
      (is (some? (get-in response [:body :id]))))))

(deftest get-metrics-test
  (testing "returns metrics snapshot"
    (let [handler handlers/get-metrics
          response (handler {})]
      (is (= 200 (:status response)))
      (is (map? (get-in response [:body :counters])))
      (is (map? (get-in response [:body :gauges])))
      (is (map? (get-in response [:body :histograms]))))))
