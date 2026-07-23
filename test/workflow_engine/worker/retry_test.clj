(ns workflow-engine.worker.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.worker.retry :as retry]))

(deftest exponential-backoff-test
  (testing "doubles delay each attempt"
    (is (= 1000 (retry/exponential-backoff 0 1000 30000)))
    (is (= 2000 (retry/exponential-backoff 1 1000 30000)))
    (is (= 4000 (retry/exponential-backoff 2 1000 30000))))
  (testing "caps at max-delay"
    (is (= 30000 (retry/exponential-backoff 10 1000 30000)))))

(deftest fixed-delay-test
  (testing "returns constant delay"
    (is (= 5000 (retry/fixed-delay 0 5000 0)))
    (is (= 5000 (retry/fixed-delay 5 5000 0)))))

(deftest should-retry-test
  (testing "retries within max-attempts"
    (let [policy (retry/make-retry-policy {:max-attempts 3})]
      (is (retry/should-retry? policy 0))
      (is (retry/should-retry? policy 1))
      (is (not (retry/should-retry? policy 2))
          "should not retry on last attempt (attempt = max-attempts - 1)")))
  (testing "no retry with max-attempts 1"
    (let [policy (retry/make-retry-policy {:max-attempts 1})]
      (is (not (retry/should-retry? policy 0))))))

(deftest make-retry-policy-test
  (testing "creates policy with defaults"
    (let [policy (retry/make-retry-policy {})]
      (is (= 3 (:max-attempts policy)))
      (is (= 1000 (:base-delay policy)))
      (is (= 30000 (:max-delay policy)))))
  (testing "creates policy with custom values"
    (let [policy (retry/make-retry-policy {:max-attempts 5 :base-delay 2000})]
      (is (= 5 (:max-attempts policy)))
      (is (= 2000 (:base-delay policy))))))

(deftest step-retry-policy-test
  (testing "nil retry config returns nil"
    (is (nil? (retry/step-retry-policy {:retry nil}))))
  (testing "int retry config creates policy"
    (let [policy (retry/step-retry-policy {:retry 3})]
      (is (some? policy))
      (is (= 3 (:max-attempts policy)))))
  (testing "map retry config creates policy"
    (let [policy (retry/step-retry-policy {:retry {:max-attempts 5 :base-delay 2000}})]
      (is (= 5 (:max-attempts policy)))
      (is (= 2000 (:base-delay policy))))))
