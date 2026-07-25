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

(deftest execute-with-retry-test
  (testing "succeeds on first attempt"
    (let [policy (retry/make-retry-policy {:max-attempts 3 :base-delay 10 :delay-fn retry/no-delay})
          result (handler/execute-with-retry (fn [_] {:ok true}) {} policy)]
      (is (= {:ok true} result))))
  (testing "retries and eventually succeeds"
    (let [attempts (atom 0)
          handler-fn (fn [_]
                       (swap! attempts inc)
                       (if (< @attempts 2)
                         (throw (Exception. "transient"))
                         {:ok true}))
          policy (retry/make-retry-policy {:max-attempts 3 :base-delay 10 :delay-fn retry/no-delay})
          result (handler/execute-with-retry handler-fn {} policy)]
      (is (= {:ok true} result))
      (is (= 2 @attempts))))
  (testing "exhausts retries and returns error"
    (let [handler-fn (fn [_] (throw (Exception. "permanent")))
          policy (retry/make-retry-policy {:max-attempts 2 :base-delay 10 :delay-fn retry/no-delay})
          result (handler/execute-with-retry handler-fn {} policy)]
      (is (:error result)))))

(deftest execute-step-with-retry-test
  (testing "executes step with retry policy"
    (let [step (model/make-step :s1 :task (fn [_] {:done true})
                                {:max-attempts 2 :base-delay 10 :delay-fn retry/no-delay} nil)
          handler-fn (fn [_] {:done true})
          result (handler/execute-step handler-fn {} step)]
      (is (= {:done true} result)))))

(deftest execute-step-handler-exception-test
  (testing "returns error when handler throws"
    (let [step (model/make-step :s1 :task (fn [_] (throw (Exception. "boom"))))
          handler-fn (fn [_] (throw (Exception. "boom")))
          result (handler/execute-step handler-fn {} step)]
      (is (:error result))
      (is (= "boom" (:error result))))))
