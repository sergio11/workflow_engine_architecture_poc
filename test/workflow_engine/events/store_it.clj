(ns workflow-engine.events.store-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.events.store :as store]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]))

(def test-datasource (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (db/execute! ds ["DELETE FROM events"])
    (db/execute! ds ["DELETE FROM executions"])
    (db/execute! ds ["DELETE FROM workflows"])
    (db/execute! ds ["INSERT INTO workflows (id, name, version, definition) VALUES ('test-wf', 'Test', 1, '{}')"])
    (db/execute! ds ["INSERT INTO executions (id, workflow_id, status) VALUES ('exec-1', 'test-wf', 'pending')"])
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (db/close-datasource! ds)
        (reset! test-datasource nil)))))

(use-fixtures :once db-fixture)

(defn clean-events-fixture [f]
  (db/execute! @test-datasource ["DELETE FROM events"])
  (f))

(use-fixtures :each clean-events-fixture)

(deftest record-event-test
  (testing "records an event and retrieves it"
    (let [event (store/record-event! @test-datasource "exec-1" :step-started :step-a {:step :step-a})
          events (store/get-execution-events @test-datasource "exec-1")]
      (is (= 1 (count events)))
      (is (= :step-started (:type (first events))))
      (is (= :step-a (:step (first events)))))))

(deftest record-workflow-started-test
  (testing "records workflow started event"
    (store/record-workflow-started! @test-datasource "exec-1")
    (let [events (store/get-execution-events @test-datasource "exec-1")]
      (is (>= (count events) 1))
      (is (= :workflow-started (:type (last events)))))))

(deftest record-step-lifecycle-test
  (testing "records step started, completed, failed events"
    (store/record-step-started! @test-datasource "exec-1" :step-a)
    (store/record-step-completed! @test-datasource "exec-1" :step-a {:result "done"})
    (store/record-step-failed! @test-datasource "exec-1" :step-b {:error "oops"})
    (let [events (store/get-execution-events @test-datasource "exec-1")]
      (is (>= (count events) 3)))))

(deftest get-events-by-type-test
  (testing "filters events by type"
    (store/record-step-started! @test-datasource "exec-1" :step-a)
    (store/record-step-completed! @test-datasource "exec-1" :step-a {:result "ok"})
    (let [started (store/get-events-by-type @test-datasource :step-started)]
      (is (some #(= :step-started (:type %)) started)))))

(deftest record-workflow-completed-test
  (testing "records workflow completed event"
    (store/record-workflow-completed! @test-datasource "exec-1")
    (let [events (store/get-execution-events @test-datasource "exec-1")]
      (is (>= (count events) 1))
      (is (= :workflow-completed (:type (last events)))))))

(deftest record-workflow-failed-test
  (testing "records workflow failed event"
    (store/record-workflow-failed! @test-datasource "exec-1" {:error "crash"})
    (let [events (store/get-execution-events @test-datasource "exec-1")]
      (is (>= (count events) 1))
      (is (= :workflow-failed (:type (last events)))))))

(deftest record-workflow-cancelled-test
  (testing "records workflow cancelled event"
    (store/record-workflow-cancelled! @test-datasource "exec-1")
    (let [events (store/get-execution-events @test-datasource "exec-1")]
      (is (>= (count events) 1))
      (is (= :workflow-cancelled (:type (last events)))))))

(deftest clear-execution-events-test
  (testing "clears all events for execution"
    (store/record-step-started! @test-datasource "exec-1" :step-a)
    (store/record-step-completed! @test-datasource "exec-1" :step-a {:result "ok"})
    (store/clear-execution-events! @test-datasource "exec-1")
    (let [events (store/get-execution-events @test-datasource "exec-1")]
      (is (= 0 (count events))))))
