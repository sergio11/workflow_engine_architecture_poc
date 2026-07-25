(ns workflow-engine.api.handlers-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.api.handlers :as handlers]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.workflow.model :as model]))

(def test-datasource (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (db/execute! ds ["DELETE FROM events"])
    (db/execute! ds ["DELETE FROM executions"])
    (db/execute! ds ["DELETE FROM workflows"])
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (db/close-datasource! ds)
        (reset! test-datasource nil)))))

(use-fixtures :once db-fixture)

(deftest health-check-test
  (testing "returns health status"
    (let [response (handlers/health-check {})]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status]))))))

(deftest create-and-get-workflow-test
  (testing "creates and retrieves a workflow"
    (let [create-handler (handlers/create-workflow @test-datasource)
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
    (let [handler (handlers/start-execution @test-datasource)
          response (handler {:body {:workflow-id "exec-wf" :input {:user "123"}}})]
      (is (= 201 (:status response)))
      (is (some? (get-in response [:body :execution-id])))
      (is (= :pending (keyword (get-in response [:body :status])))))))

(deftest start-execution-workflow-not-found-test
  (testing "returns 404 when workflow not found"
    (let [handler (handlers/start-execution @test-datasource)
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
    (let [start-handler (handlers/start-execution @test-datasource)
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
    (let [start-handler (handlers/start-execution @test-datasource)
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
    (let [start-handler (handlers/start-execution @test-datasource)
          start-res (start-handler {:body {:workflow-id "cancel-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'running' WHERE id = ?" exec-id])
          cancel-handler (handlers/cancel-execution @test-datasource)
          response (cancel-handler {:path-params {:id exec-id}})]
      (is (= 200 (:status response)))))
  (testing "returns 404 for non-existent execution"
    (let [handler (handlers/cancel-execution @test-datasource)
          response (handler {:path-params {:id "nonexistent"}})]
      (is (= 404 (:status response))))))

(deftest resume-execution-test
  (testing "resumes a waiting execution"
    (let [wf (model/make-workflow "resume-wf" "Resume WF" 1
               [(model/make-step :s1 :task (fn [_] {:ok true}))])]
      (wf-repo/save-workflow! @test-datasource wf))
    (let [start-handler (handlers/start-execution @test-datasource)
          start-res (start-handler {:body {:workflow-id "resume-wf" :input {}}})
          exec-id (get-in start-res [:body :execution-id])
          _ (db/execute! @test-datasource ["UPDATE executions SET status = 'waiting', current_step = 's1' WHERE id = ?" exec-id])
          resume-handler (handlers/resume-execution @test-datasource)
          response (resume-handler {:path-params {:id exec-id} :body {:workflow-id "resume-wf"}})]
      (is (= 200 (:status response)))))
  (testing "returns 404 for non-existent execution"
    (let [handler (handlers/resume-execution @test-datasource)
          response (handler {:path-params {:id "nonexistent"} :body {:workflow-id "resume-wf"}})]
      (is (= 404 (:status response))))))
