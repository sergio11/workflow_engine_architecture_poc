(ns workflow-engine.worker.handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.worker.handler :as handler]
            [workflow-engine.worker.retry :as retry]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.workflow.dsl :as dsl]))

(deftest execute-with-timeout-test
  (testing "executes handler within timeout"
    (let [result (handler/execute-with-timeout (fn [_] {:ok true}) {} nil)]
      (is (= {:ok true} result))))
  (testing "returns timeout error when exceeded"
    (let [result (handler/execute-with-timeout (fn [_] (Thread/sleep 5000) {:ok true}) {} 100)]
      (is (:error result))
      (is (re-find #"timed out" (:error result))))))

(deftest execute-step-with-retry-test
  (testing "executes step with retry policy"
    (let [step (model/make-step :s1 :task (fn [_] {:done true})
                                {:max-attempts 2 :base-delay 10 :delay-fn retry/no-delay} nil)
          handler-fn (fn [_] {:done true})
          result (handler/execute-step handler-fn {} step)]
      (is (= {:done true} result)))))

(deftest execute-step-retry-with-timeout-test
  (testing "retry loop respects timeout on each attempt"
    (let [step (model/make-step :s1 :task nil
                                {:max-attempts 3 :base-delay 10 :delay-fn retry/no-delay}
                                100)
          handler-fn (fn [_] (Thread/sleep 5000) {:ok true})
          result (handler/execute-step handler-fn {} step)]
      (is (:error result))
      (is (re-find #"timed out" (:error result))))))

(deftest execute-step-handler-exception-test
  (testing "returns error when handler throws"
    (let [step (model/make-step :s1 :task (fn [_] (throw (Exception. "boom"))))
          handler-fn (fn [_] (throw (Exception. "boom")))
          result (handler/execute-step handler-fn {} step)]
      (is (:error result))
      (is (= "boom" (:error result))))))

(deftest execute-step-no-retry-success-test
  (testing "executes without retry policy on success"
    (let [step (model/make-step :s1 :task nil)
          handler-fn (fn [_] {:success true})
          result (handler/execute-step handler-fn {} step)]
      (is (= {:success true} result)))))

(deftest execute-step-no-retry-exception-test
  (testing "returns error without retry when handler throws"
    (let [step (model/make-step :s1 :task nil)
          handler-fn (fn [_] (throw (Exception. "no-retry-error")))
          result (handler/execute-step handler-fn {} step)]
      (is (= "no-retry-error" (:error result))))))
