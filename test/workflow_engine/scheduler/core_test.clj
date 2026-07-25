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
