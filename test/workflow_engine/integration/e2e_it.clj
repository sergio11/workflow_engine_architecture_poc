(ns workflow-engine.integration.e2e-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.events.store :as events]
            [workflow-engine.events.publisher :as publisher]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.worker.registry :as registry]))

(def test-datasource (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (registry/clear-registry!)
    (db/execute! ds ["DELETE FROM events"])
    (db/execute! ds ["DELETE FROM executions"])
    (db/execute! ds ["DELETE FROM workflows"])
    (metrics/clear-metrics!)
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (db/close-datasource! ds)
        (reset! test-datasource nil)
        (metrics/clear-metrics!)
        (registry/clear-registry!)))))

(use-fixtures :once db-fixture)

(def simple-wf
  (dsl/linear-workflow "simple-wf" "Simple Workflow" 1
    [[:greet :task (fn [ctx] {:message (str "Hello " (:user ctx))})]
     [:farewell :task (fn [ctx] {:message (str "Goodbye " (:user ctx))})]]))

(deftest full-workflow-execution-test
  (testing "end-to-end: create workflow, start, execute all steps, complete"
    (let [ds @test-datasource]
      (wf-repo/save-workflow! ds simple-wf)
      (let [exec (engine/start-execution! ds simple-wf {:user "Alice"})
            _ (is (= :pending (:status exec)))
            _ (is (= :greet (:current-step exec)))
            step1 (engine/execute-step! ds (assoc exec :status :running) simple-wf)
            _ (is (= :completed (:status step1)))
            _ (is (= :farewell (:current-step step1)))
            step2 (engine/execute-step! ds step1 simple-wf)]
        (is (= :completed (:status step2)))
        (is (nil? (:current-step step2)))))))

(deftest workflow-with-events-test
  (testing "events are recorded during execution"
    (let [ds @test-datasource]
      (wf-repo/save-workflow! ds simple-wf)
      (let [exec (engine/start-execution! ds simple-wf {:user "Bob"})
            _ (events/record-workflow-started! ds (:execution-id exec))
            _ (events/record-step-started! ds (:execution-id exec) :greet)
            step1 (engine/execute-step! ds (assoc exec :status :running) simple-wf)
            _ (events/record-step-completed! ds (:execution-id exec) :greet (:context step1))
            all-events (events/get-execution-events ds (:execution-id exec))]
        (is (>= (count all-events) 3))))))

(deftest cancel-execution-test
  (testing "can cancel a running execution"
    (let [ds @test-datasource]
      (wf-repo/save-workflow! ds simple-wf)
      (let [exec (engine/start-execution! ds simple-wf {})
            _ (db/execute! ds ["UPDATE executions SET status = 'running' WHERE id = ?" (:execution-id exec)])
            cancelled (engine/cancel-execution! ds (:execution-id exec))]
        (is (= :cancelled (:status cancelled)))))))

(deftest retry-execution-test
  (testing "can retry a failed execution"
    (let [ds @test-datasource]
      (wf-repo/save-workflow! ds simple-wf)
      (let [exec (engine/start-execution! ds simple-wf {:user "Charlie"})
            _ (db/execute! ds ["UPDATE executions SET status = 'failed', current_step = 'greet' WHERE id = ?" (:execution-id exec)])
            retried (engine/retry-execution! ds (:execution-id exec) simple-wf)]
        (is (= :completed (:status retried)))))))

(deftest metrics-during-execution-test
  (testing "metrics are updated automatically during execution"
    (let [ds @test-datasource
          wf (model/make-workflow "metrics-wf" "Metrics WF" 1
               [(model/make-step :s1 :task (fn [ctx] {:ok true}))])]
      (doseq [s (:steps wf)] (registry/register-handler! (:id s) (:handler s)))
      (wf-repo/save-workflow! ds wf)
      (let [exec (engine/start-execution! ds wf {})
            _ (engine/execute-step! ds (assoc exec :status :running) wf)]
        (is (pos? (metrics/get-counter :workflows-started)))
        (is (pos? (metrics/get-counter :steps-executed)))
        (is (pos? (metrics/get-counter :workflows-completed)))
        (is (some? (metrics/get-histogram :step-duration)))))))

(deftest full-e2e-from-db-test
  (testing "create workflow via repo, load from DB, execute all steps"
    (let [ds @test-datasource
          handler-fn (fn [ctx] {:greeted (:name ctx)})
          wf (model/make-workflow "e2e-db-wf" "E2E DB" 1
               [(model/make-step :s1 :task handler-fn)
                (model/make-step :s2 :task handler-fn)])
          _ (doseq [s (:steps wf)] (registry/register-handler! (:id s) (:handler s)))
          _ (wf-repo/save-workflow! ds wf)
          loaded-wf (wf-repo/get-workflow ds "e2e-db-wf")
          exec (engine/start-execution! ds loaded-wf {:name "World"})
          step1 (engine/execute-step! ds (assoc exec :status :running) loaded-wf)
          step2 (engine/execute-step! ds step1 loaded-wf)]
      (is (= :completed (:status step2)))
      (is (= {:greeted "World"} (get-in step2 [:context :last-result]))))))

(deftest events-published-during-execution-test
  (testing "events are published to subscribers during execution"
    (let [ds @test-datasource
          events-received (atom [])
          unsub (publisher/subscribe-all! (fn [event] (swap! events-received conj event)))
          wf (model/make-workflow "pub-wf" "Pub WF" 1
               [(model/make-step :s1 :task (fn [ctx] {:ok true}))])]
      (try
        (doseq [s (:steps wf)] (registry/register-handler! (:id s) (:handler s)))
        (wf-repo/save-workflow! ds wf)
        (let [exec (engine/start-execution! ds wf {})]
          (engine/execute-step! ds (assoc exec :status :running) wf))
        (is (>= (count @events-received) 3))
        (is (some #(= :workflow-started (:type %)) @events-received))
        (is (some #(= :step-completed (:type %)) @events-received))
        (finally
          (unsub))))))
