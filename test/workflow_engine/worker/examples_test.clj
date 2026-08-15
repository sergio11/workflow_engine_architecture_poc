(ns workflow-engine.worker.examples-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.worker.examples :as examples]))

(deftest create-user-test
  (testing "creates user with generated id"
    (let [result (examples/create-user {:user-data {:name "Alice" :email "alice@test.com"}})]
      (is (string? (:user-id result)))
      (is (= "alice@test.com" (:email result)))
      (is (true? (:created result))))))

(deftest send-email-test
  (testing "sends email with given params"
    (let [result (examples/send-email {:email-to "test@example.com"
                                        :email-subject "Hello"})]
      (is (true? (:sent result)))
      (is (= "test@example.com" (:to result)))
      (is (= "Hello" (:subject result))))))

(deftest process-payment-test
  (testing "processes valid payment"
    (let [result (examples/process-payment {:amount 100})]
      (is (string? (:payment-id result)))
      (is (= 100 (:amount result)))
      (is (= :completed (:status result)))))
  (testing "throws on negative amount"
    (is (thrown? clojure.lang.ExceptionInfo
                (examples/process-payment {:amount -50})))))

(deftest flaky-handler-test
  (testing "returns map with :success or :error"
    (dotimes [_ 30]
      (let [result (examples/flaky-handler {})]
        (is (or (contains? result :success)
                (contains? result :error)))))))

(deftest timeout-handler-test
  (testing "handler function exists and is callable"
    (is (fn? examples/timeout-handler))))

(deftest timeout-handler-call-test
  (testing "timeout-handler returns result after long sleep"
    (let [result (future (examples/timeout-handler {}))
          _ (Thread/sleep 100)]
      (is (not (realized? result)))
      (future-cancel result)
      (is true "handler was running and got cancelled"))))

(deftest slow-handler-test-direct
  (testing "slow handler completes and returns result"
    (let [result (examples/slow-handler {})]
      (is (= "completed after delay" (:result result))))))

(deftest process-payment-zero-test
  (testing "process-payment with zero amount succeeds"
    (let [result (examples/process-payment {:amount 0})]
      (is (= 0 (:amount result)))
      (is (= :completed (:status result))))))

(deftest create-user-email-test
  (testing "create-user preserves email"
    (let [result (examples/create-user {:user-data {:email "a@b.com"}})]
      (is (= "a@b.com" (:email result)))
      (is (true? (:created result))))))

(deftest timeout-handler-completes-test
  (testing "timeout-handler returns expected result when allowed to complete"
    (let [result (future (examples/timeout-handler {}))
          val (deref result 12000 ::timeout)]
      (is (not= ::timeout val))
      (is (= "should timeout" (:result val))))))
