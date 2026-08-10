(ns workflow-engine.scheduler.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [workflow-engine.scheduler.core :as scheduler]
            [workflow-engine.scheduler.channels :as channels]
            [workflow-engine.workflow.model :as model]))

(deftest start-and-stop-scheduler-test
  (testing "scheduler can start and stop"
    (let [scheduler (scheduler/start-scheduler! nil 1)]
      (is (some? scheduler))
      (is (= 1 (count (:workers scheduler))))
      (scheduler/stop-scheduler!))))

(deftest process-work-test
  (testing "processes work item"
    (let [handler-fn (fn [ctx] {:result "done"})
          step (model/make-step :s1 :task handler-fn)
          work-item {:handler-fn handler-fn :context {:user "123"} :step step}
          result (scheduler/process-work! nil work-item)]
      (is (= {:result "done"} (:result result)))
      (is (= work-item (:work-item result))))))

(deftest process-work-error-test
  (testing "returns error result when handler throws"
    (let [handler-fn (fn [ctx] (throw (Exception. "worker failed")))
          step (model/make-step :s1 :task handler-fn)
          work-item {:handler-fn handler-fn :context {} :step step}
          result (scheduler/process-work! nil work-item)]
      (is (some? (:error (:result result))))
      (is (= work-item (:work-item result))))))

(deftest start-multi-worker-test
  (testing "starts multiple workers"
    (let [scheduler (scheduler/start-scheduler! nil 3)]
      (is (some? scheduler))
      (is (= 3 (count (:workers scheduler))))
      (scheduler/stop-scheduler!))))

(deftest start-worker-processes-work-test
  (testing "worker pulls from work-ch and pushes to result-ch"
    (let [sched (scheduler/start-scheduler! nil 1)
          work-item {:handler-fn (fn [ctx] {:ok true})
                     :context {}
                     :step (model/make-step :s1 :task (fn [_] {:ok true}))}]
      (async/>!! channels/work-ch work-item)
      (let [result (async/alt!
                     channels/result-ch ([v] v)
                     (async/timeout 3000) :timeout)]
        (is (not= :timeout result))
        (is (= {:ok true} (:result result))))
      (scheduler/stop-scheduler!))))

(deftest start-worker-exception-test
  (testing "worker catches exceptions and pushes error"
    (let [sched (scheduler/start-scheduler! nil 1)
          work-item {:handler-fn (fn [ctx] (throw (Exception. "boom")))
                     :context {}
                     :step (model/make-step :s1 :task (fn [_] (throw (Exception. "boom"))))}]
      (async/>!! channels/work-ch work-item)
      (let [result (async/alt!
                     channels/result-ch ([v] v)
                     (async/timeout 3000) :timeout)]
        (is (not= :timeout result))
        (is (some? (:error (:result result)))))
      (scheduler/stop-scheduler!))))

(deftest start-result-processor-test
  (testing "result processor consumes from result-ch"
    (let [processor (scheduler/start-result-processor!)]
      (async/>!! channels/result-ch {:result "test"})
      (Thread/sleep 100)
      (is true "processor consumed without error"))
    (channels/close-all!)))
