(ns workflow-engine.persistence.workflow-repo-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as repo]
            [workflow-engine.workflow.model :as model]))

(def test-datasource (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (db/execute! ds ["DELETE FROM workflows"])
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM workflows"])
        (db/close-datasource! ds)
        (reset! test-datasource nil)))))

(use-fixtures :once db-fixture)

(deftest save-and-get-workflow-test
  (testing "saves and retrieves a workflow"
    (let [wf (model/make-workflow "test-wf" "Test Workflow" 1
               [(model/make-step :step1 :task)])
          _ (repo/save-workflow! @test-datasource wf)
          retrieved (repo/get-workflow @test-datasource "test-wf")]
      (is (= "test-wf" (:id retrieved)))
      (is (= "Test Workflow" (:name retrieved)))
      (is (= 1 (:version retrieved)))
      (is (= :task (:type (first (:steps retrieved))))))))

(deftest list-workflows-test
  (testing "lists all workflows"
    (let [wf1 (model/make-workflow "wf1" "Workflow 1" 1 [])
          wf2 (model/make-workflow "wf2" "Workflow 2" 1 [])
          _ (repo/save-workflow! @test-datasource wf1)
          _ (repo/save-workflow! @test-datasource wf2)
          result (repo/list-workflows @test-datasource)]
      (is (>= (count result) 2)))))

(deftest delete-workflow-test
  (testing "deletes a workflow"
    (let [wf (model/make-workflow "del-wf" "Delete Me" 1 [])
          _ (repo/save-workflow! @test-datasource wf)
          _ (repo/delete-workflow! @test-datasource "del-wf")
          result (repo/get-workflow @test-datasource "del-wf")]
      (is (nil? result)))))
