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

(deftest slow-handler-test
  (testing "returns result map"
    (let [result (examples/slow-handler {})]
      (is (= "completed after delay" (:result result))))))

(deftest timeout-handler-test
  (testing "handler function exists and is callable"
    (is (fn? examples/timeout-handler))))
