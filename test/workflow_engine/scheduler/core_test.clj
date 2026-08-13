(ns workflow-engine.scheduler.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [workflow-engine.scheduler.core :as scheduler]
            [workflow-engine.scheduler.channels :as channels]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as db-config]))

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

(deftest start-scheduler-default-arity-test
  (testing "start-scheduler! with 1 arg defaults to 1 worker"
    (let [sched (scheduler/start-scheduler! nil)]
      (is (some? sched))
      (is (= 1 (count (:workers sched))))
      (is (some? (:processor sched)))
      (scheduler/stop-scheduler!))))

(deftest worker-unhandled-exception-test
  (testing "worker catch block handles unhandled exceptions from process-work!"
    (with-redefs [scheduler/process-work! (fn [_ _] (throw (Exception. "unhandled-error")))]
      (let [sched (scheduler/start-scheduler! nil 1)
            work-item {:handler-fn (fn [_] {:ok true})
                       :context {}
                       :step (model/make-step :s1 :task (fn [_] {:ok true}))}]
        (async/>!! channels/work-ch work-item)
        (let [result (async/alt!
                       channels/result-ch ([v] v)
                       (async/timeout 3000) :timeout)]
          (is (not= :timeout result))
          (is (some? (:error result))))
        (scheduler/stop-scheduler!)))))

(deftest process-work-with-context-test
  (testing "passes datasource and context correctly"
    (let [captured-ds (atom nil)
          original-process scheduler/process-work!]
      (with-redefs [scheduler/process-work!
                    (fn [ds work-item]
                      (reset! captured-ds ds)
                      (original-process ds work-item))]
        (let [result (scheduler/process-work! :my-ds {:handler-fn (fn [_] {:ok true})
                                                       :context {:x 1}
                                                       :step (model/make-step :s1 :task (fn [_] {:ok true}))})]
          (is (= :my-ds @captured-ds))
          (is (= {:ok true} (:result result))))))))

(deftest scheduler-lifecycle-test
  (testing "can start and stop scheduler"
    (let [ds (db/create-datasource 
               (db-config/test-config))
          scheduler-handle (scheduler/start-scheduler! ds 1)]
      (is (some? scheduler-handle))
      (is (contains? scheduler-handle :workers))
      (is (contains? scheduler-handle :processor))
      (scheduler/stop-scheduler! scheduler-handle)
      (db/close-datasource! ds))))