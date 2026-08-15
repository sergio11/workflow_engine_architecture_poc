(ns workflow-engine.scheduler.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [workflow-engine.scheduler.core :as scheduler]
            [workflow-engine.scheduler.channels :as channels]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.execution.adapters :as adapters]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as db-config]))

(defn make-test-adapters []
  (let [ds (db/create-datasource (db-config/test-config))]
    {:datasource ds
     :store (adapters/create-store ds)
     :recorder (adapters/create-recorder ds)
     :publisher (adapters/create-publisher)
     :metrics (adapters/create-metrics-collector)}))

(defn close-test-adapters! [{:keys [datasource]}]
  (db/close-datasource! datasource))

(deftest start-and-stop-scheduler-test
  (testing "scheduler can start and stop"
    (let [{:keys [store recorder publisher metrics]} (make-test-adapters)
          ch (channels/create-channels)
          sched (scheduler/start-scheduler! nil ch 1 store recorder publisher metrics)]
      (is (some? sched))
      (is (= 1 (count (:workers sched))))
      (scheduler/stop-scheduler! sched ch))))

(deftest process-work-test
  (testing "processes work item"
    (let [handler-fn (fn [ctx] {:result "done"})
          step (model/make-step :s1 :task handler-fn)
          work-item {:handler-fn handler-fn :context {:user "123"} :step step}
          result (scheduler/process-work! work-item)]
      (is (= {:result "done"} (:result result)))
      (is (= work-item (:work-item result))))))

(deftest process-work-error-test
  (testing "returns error result when handler throws"
    (let [handler-fn (fn [ctx] (throw (Exception. "worker failed")))
          step (model/make-step :s1 :task handler-fn)
          work-item {:handler-fn handler-fn :context {} :step step}
          result (scheduler/process-work! work-item)]
      (is (some? (:error (:result result))))
      (is (= work-item (:work-item result))))))

(deftest start-multi-worker-test
  (testing "starts multiple workers"
    (let [{:keys [store recorder publisher metrics]} (make-test-adapters)
          ch (channels/create-channels)
          sched (scheduler/start-scheduler! nil ch 3 store recorder publisher metrics)]
      (is (some? sched))
      (is (= 3 (count (:workers sched))))
      (scheduler/stop-scheduler! sched ch))))

(deftest start-worker-processes-work-test
  (testing "worker pulls from work-ch and pushes to result-ch"
    (let [ch (channels/create-channels)
          worker (scheduler/start-worker! ch)
          work-item {:handler-fn (fn [ctx] {:ok true})
                     :context {}
                     :step (model/make-step :s1 :task (fn [_] {:ok true}))}]
      (channels/submit-work! ch work-item)
      (let [timeout-ch (async/timeout 3000)
            [result _] (async/alts!! [(:result-ch ch) timeout-ch])]
        (is (not (nil? result)))
        (is (= {:ok true} (:result result))))
      (channels/close-channels! ch))))

(deftest start-worker-exception-test
  (testing "worker catches exceptions and pushes error"
    (let [ch (channels/create-channels)
          worker (scheduler/start-worker! ch)
          work-item {:handler-fn (fn [ctx] (throw (Exception. "boom")))
                     :context {}
                     :step (model/make-step :s1 :task (fn [_] (throw (Exception. "boom"))))}]
      (channels/submit-work! ch work-item)
      (let [timeout-ch (async/timeout 3000)
            [result _] (async/alts!! [(:result-ch ch) timeout-ch])]
        (is (not (nil? result)))
        (is (some? (:error (:result result)))))
      (channels/close-channels! ch))))

(deftest start-result-processor-test
  (testing "result processor consumes from result-ch"
    (let [{:keys [store recorder publisher metrics]} (make-test-adapters)
          ch (channels/create-channels)
          processor (scheduler/start-result-processor! ch store recorder publisher metrics)]
      (channels/submit-result! ch {:result "test"})
      (Thread/sleep 100)
      (is true "processor consumed without error")
      (channels/close-channels! ch))))

(deftest start-scheduler-default-arity-test
  (testing "start-scheduler! with 2 args + adapters defaults to 2 workers"
    (let [{:keys [store recorder publisher metrics]} (make-test-adapters)
          ch (channels/create-channels)
          sched (scheduler/start-scheduler! nil ch store recorder publisher metrics)]
      (is (some? sched))
      (is (= 2 (count (:workers sched))))
      (is (some? (:processor sched)))
      (scheduler/stop-scheduler! sched ch))))

(deftest worker-unhandled-exception-test
  (testing "worker catch block handles unhandled exceptions from process-work!"
    (let [ch (channels/create-channels)]
      (with-redefs [scheduler/process-work! (fn [_] (throw (Exception. "unhandled-error")))]
        (let [worker (scheduler/start-worker! ch)
              work-item {:handler-fn (fn [_] {:ok true})
                         :context {}
                         :step (model/make-step :s1 :task (fn [_] {:ok true}))}]
          (channels/submit-work! ch work-item)
          (let [timeout-ch (async/timeout 3000)
                [result _] (async/alts!! [(:result-ch ch) timeout-ch])]
            (is (not (nil? result)))
            (is (some? (:error result))))
          (channels/close-channels! ch))))))

(deftest process-work-with-context-test
  (testing "passes datasource and context correctly"
    (let [ch (channels/create-channels)]
      (let [result (scheduler/process-work! {:handler-fn (fn [_] {:ok true})
                                             :context {:x 1}
                                             :step (model/make-step :s1 :task (fn [_] {:ok true}))})]
        (is (= {:ok true} (:result result)))))))

(deftest scheduler-lifecycle-test
  (testing "can start and stop scheduler"
    (let [ds (db/create-datasource (db-config/test-config))
          ch (channels/create-channels)
          store (adapters/create-store ds)
          recorder (adapters/create-recorder ds)
          pub (adapters/create-publisher)
          metrics-adapter (adapters/create-metrics-collector)
          sched (scheduler/start-scheduler! ds ch 1 store recorder pub metrics-adapter)]
      (is (some? sched))
      (is (contains? sched :workers))
      (is (contains? sched :processor))
      (scheduler/stop-scheduler! sched ch)
      (db/close-datasource! ds))))

(deftest result-processor-handles-success-test
  (testing "result processor processes successful work result"
    (let [ds (db/create-datasource (db-config/test-config))
          ch (channels/create-channels)
          store (adapters/create-store ds)
          recorder (adapters/create-recorder ds)
          pub (adapters/create-publisher)
          metrics-adapter (adapters/create-metrics-collector)
          sched (scheduler/start-scheduler! ds ch 1 store recorder pub metrics-adapter)]
      (try
        (Thread/sleep 500)
        (is true "scheduler started and ran without error")
        (finally
          (scheduler/stop-scheduler! sched ch)
          (db/close-datasource! ds))))))

(deftest stop-scheduler-nil-handle-test
  (testing "stop-scheduler! with nil handle does not throw"
    (let [ch (channels/create-channels)]
      (scheduler/stop-scheduler! nil ch)
      (is true "no exception"))))

(deftest advance-if-needed-no-op-test
  (testing "does nothing when execution status is not :running"
    (let [ch (channels/create-channels)]
      (scheduler/advance-if-needed! nil {:status :completed :current-step nil} nil ch nil nil nil nil)
      (is true "returned without calling engine")))
  (testing "does nothing when current-step is nil"
    (let [ch (channels/create-channels)]
      (scheduler/advance-if-needed! nil {:status :running :current-step nil} nil ch nil nil nil nil)
      (is true "returned without calling engine"))))

(deftest advance-if-needed-running-test
  (testing "submits next step when execution is running with current-step"
    (let [ch (channels/create-channels)
          ds (db/create-datasource (db-config/test-config))
          store (adapters/create-store ds)
          recorder (adapters/create-recorder ds)
          pub (adapters/create-publisher)
          metrics-adapter (adapters/create-metrics-collector)
          submitted (atom false)]
      (with-redefs [engine/submit-step-for-execution! (fn [& _args] (reset! submitted true))]
        (let [execution {:execution-id "test-exec"
                         :workflow-id "adv-wf"
                         :status :running
                         :current-step :s1
                         :context {}}]
          (scheduler/advance-if-needed! ds execution nil ch store recorder pub metrics-adapter)
          (is @submitted)))
      (db/close-datasource! ds))))

(deftest result-processor-error-handling-test
  (testing "result processor catches exceptions from handle-async-completion!"
    (let [{:keys [store recorder publisher metrics]} (make-test-adapters)
          ch (channels/create-channels)
          processor (scheduler/start-result-processor! ch store recorder publisher metrics)]
      (with-redefs [engine/handle-async-completion! (fn [& _] (throw (Exception. "engine error")))]
        (channels/submit-result! ch {:work-item {:execution {:status :running}
                                                 :workflow {}
                                                 :datasource nil}
                                    :result {:ok true}
                                    :duration-ms 10})
        (Thread/sleep 200)
        (is true "processor caught exception without crashing"))
      (channels/close-channels! ch))))

(deftest result-processor-advances-next-step-test
  (testing "result processor auto-advances when status is :running and next-step exists"
    (let [{:keys [store recorder publisher metrics]} (make-test-adapters)
          ch (channels/create-channels)
          submitted (atom nil)
          latch (java.util.concurrent.CountDownLatch. 1)]
      (with-redefs [engine/handle-async-completion!
                    (fn [store recorder publisher metrics ds exec wf step result dur]
                      {:status :running
                       :next-step {:id :s2}
                       :execution exec})
                    engine/submit-step-for-execution!
                    (fn [store recorder publisher metrics ds exec wf ch*]
                      (reset! submitted (:current-step exec))
                      (.countDown latch))]
        (let [processor (scheduler/start-result-processor! ch store recorder publisher metrics)]
          (channels/submit-result! ch
            {:work-item {:execution {:execution-id "test-exec"
                                     :status :running
                                     :current-step :s1}
                         :workflow {:id "test-wf"}
                         :datasource nil}
             :result {:ok true}
             :duration-ms 50})
          (.await latch 3 java.util.concurrent.TimeUnit/SECONDS)
          (is (= :s1 @submitted)
              "advance-if-needed! should have been called"))
        (channels/close-channels! ch)))))
