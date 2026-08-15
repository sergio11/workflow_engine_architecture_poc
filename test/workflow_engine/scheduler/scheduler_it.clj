(ns workflow-engine.scheduler.scheduler-it
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.execution.adapters :as adapters]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.persistence.execution-repo :as exec-repo]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.worker.registry :as registry]
            [workflow-engine.scheduler.core :as scheduler]
            [workflow-engine.scheduler.channels :as channels]
            [workflow-engine.metrics.collector :as metrics]))

(def test-datasource (atom nil))
(def test-channels (atom nil))
(def test-scheduler (atom nil))
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
    (metrics/clear-metrics!)
    (let [sched (scheduler/start-scheduler! ds ch 2 store recorder pub metrics-adapter)]
      (reset! test-scheduler sched))
    (try
      (f)
      (finally
        (when-let [sched @test-scheduler]
          (scheduler/stop-scheduler! sched ch))
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (channels/close-channels! ch)
        (db/close-datasource! ds)
        (reset! test-datasource nil)
        (reset! test-channels nil)
        (reset! test-scheduler nil)
        (reset! test-store nil)
        (reset! test-recorder nil)
        (reset! test-publisher nil)
        (reset! test-metrics nil)
        (metrics/clear-metrics!)
        (registry/clear-registry!)))))

(use-fixtures :once db-fixture)

(defn poll-execution!
  "Poll execution status until terminal state."
  [datasource exec-id & {:keys [timeout-ms interval-ms]
                         :or {timeout-ms 10000 interval-ms 50}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [exec (exec-repo/get-execution datasource exec-id)]
        (if (or (nil? exec)
                (#{:completed :failed :cancelled} (:status exec)))
          exec
          (if (> (System/currentTimeMillis) deadline)
            (do
              (println "Timeout waiting for execution" exec-id)
              exec)
            (do
              (Thread/sleep interval-ms)
              (recur))))))))

(deftest async-single-step-execution-test
  (testing "single step workflow executes via scheduler"
    (let [ds @test-datasource
          ch @test-channels
          wf (dsl/linear-workflow "async-single" "Async Single" 1
               [[:step1 :task (fn [ctx] {:result "done"})]])]
      (wf-repo/save-workflow! ds wf)
      (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {:test "data"})]
        (is (= :pending (:status exec)))
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec wf ch)
        (let [result (poll-execution! ds (:execution-id exec))]
          (is (= :completed (:status result)))
          (is (nil? (:current-step result))))))))

(deftest async-multi-step-execution-test
  (testing "multi-step workflow executes all steps via scheduler"
    (let [ds @test-datasource
          ch @test-channels
          wf (dsl/linear-workflow "async-multi" "Async Multi" 1
               [[:s1 :task (fn [ctx] {:step 1})]
                [:s2 :task (fn [ctx] {:step 2})]
                [:s3 :task (fn [ctx] {:step 3})]])]
      (wf-repo/save-workflow! ds wf)
      (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})]
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec wf ch)
        (let [result (poll-execution! ds (:execution-id exec))]
          (is (= :completed (:status result)))
          (is (nil? (:current-step result)))
          (is (= {:step 3} (get-in result [:context :last-result]))))))))

(deftest async-step-failure-test
  (testing "workflow fails when step throws exception"
    (let [ds @test-datasource
          ch @test-channels
          wf (dsl/linear-workflow "async-fail" "Async Fail" 1
               [[:fail-step :task (fn [ctx] (throw (Exception. "step failed")))]])]
      (wf-repo/save-workflow! ds wf)
      (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})]
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec wf ch)
        (let [result (poll-execution! ds (:execution-id exec))]
          (is (= :failed (:status result)))
          (is (some? (get-in result [:context :last-result :error]))))))))

(deftest async-events-recorded-test
  (testing "events are recorded during async execution"
    (let [ds @test-datasource
          ch @test-channels
          wf (dsl/linear-workflow "async-events" "Async Events" 1
               [[:ev1 :task (fn [ctx] {:event-recorded true})]])]
      (wf-repo/save-workflow! ds wf)
      (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})]
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec wf ch)
        (let [result (poll-execution! ds (:execution-id exec))]
          (is (= :completed (:status result)))
          (let [events (exec-repo/get-execution ds (:execution-id exec))]
            (is (some? events))))))))

(deftest async-retry-on-flaky-handler-test
  (testing "flaky handler retries via scheduler"
    (let [ds @test-datasource
          ch @test-channels
          attempts (atom 0)
          wf (dsl/linear-workflow "async-retry" "Async Retry" 1
               [{:id "flaky" :type :task
                 :retry {:max-attempts 3 :base-delay 10 :max-delay 100}}])]
      (registry/register-handler! "flaky"
        (fn [_ctx]
          (if (< (swap! attempts inc) 3)
            {:error "transient"}
            {:ok true})))
      (wf-repo/save-workflow! ds wf)
      (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})]
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec wf ch)
        (let [result (poll-execution! ds (:execution-id exec))]
          (is (= :completed (:status result)))
          (is (= 3 @attempts)))))))

(deftest async-no-handler-fails-test
  (testing "workflow fails when no handler is registered"
    (let [ds @test-datasource
          ch @test-channels
          wf (dsl/linear-workflow "async-no-handler" "No Handler" 1
               [["orphan" :task]])]
      (wf-repo/save-workflow! ds wf)
      (let [exec (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})]
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec wf ch)
        (let [result (poll-execution! ds (:execution-id exec))]
          (is (= :failed (:status result))))))))

(deftest async-concurrent-executions-test
  (testing "multiple executions run concurrently via scheduler"
    (let [ds @test-datasource
          ch @test-channels
          counter (atom 0)
          wf (dsl/linear-workflow "async-concurrent" "Concurrent" 1
               [[:inc :task (fn [ctx] (swap! counter inc) {:count @counter})]])]
      (wf-repo/save-workflow! ds wf)
      (let [exec1 (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})
            exec2 (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})
            exec3 (engine/start-execution! @test-store @test-recorder @test-publisher @test-metrics ds wf {})]
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec1 wf ch)
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec2 wf ch)
        (engine/submit-step-for-execution! @test-store @test-recorder @test-publisher @test-metrics ds exec3 wf ch)
        (let [r1 (poll-execution! ds (:execution-id exec1))
              r2 (poll-execution! ds (:execution-id exec2))
              r3 (poll-execution! ds (:execution-id exec3))]
          (is (= :completed (:status r1)))
          (is (= :completed (:status r2)))
          (is (= :completed (:status r3)))
          (is (= 3 @counter)))))))
