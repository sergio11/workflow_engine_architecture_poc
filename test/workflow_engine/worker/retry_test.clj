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
      (is (= 2000 (:base-delay policy)))))
  (testing "unrecognized type returns nil"
    (is (nil? (retry/step-retry-policy {:retry "invalid"})))))

(deftest no-delay-test
  (testing "always returns 0"
    (is (= 0 (retry/no-delay 0)))
    (is (= 0 (retry/no-delay 5)))))

(deftest default-retry-policy-test
  (testing "has expected defaults"
    (is (= 3 (:max-attempts retry/default-retry-policy)))
    (is (= 1000 (:base-delay retry/default-retry-policy)))))

(deftest retry-delay-test
  (testing "calculates delay using policy delay-fn"
    (let [policy (retry/make-retry-policy {:base-delay 500 :delay-fn retry/fixed-delay})]
      (is (= 500 (retry/retry-delay policy 2))))))

(deftest retry-delay-with-exponential-test
  (testing "calculates delay with exponential backoff"
    (let [policy (retry/make-retry-policy {:base-delay 100 :delay-fn retry/exponential-backoff :max-delay 5000})]
      (is (= 100 (retry/retry-delay policy 0)))
      (is (= 200 (retry/retry-delay policy 1))))))

(deftest make-retry-policy-with-delay-fn-test
  (testing "uses provided delay-fn"
    (let [policy (retry/make-retry-policy {:delay-fn retry/no-delay})]
      (is (= retry/no-delay (:delay-fn policy))))))

(deftest make-retry-policy-defaults-test
  (testing "defaults include fixed-delay"
    (let [policy (retry/make-retry-policy {})]
      (is (= retry/fixed-delay (:delay-fn policy))))))

(deftest make-retry-policy-no-delay-test
  (testing "uses fixed-delay when delay is nil"
    (let [policy (retry/make-retry-policy {:delay nil})]
      (is (= retry/fixed-delay (:delay-fn policy)))
      (is (= 3 (:max-attempts policy))))))
