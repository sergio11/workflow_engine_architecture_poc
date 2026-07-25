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
