(ns workflow-engine.worker.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.worker.registry :as registry]))

(defn cleanup-fixture [f]
  (registry/clear-registry!)
  (try
    (f)
    (finally
      (registry/clear-registry!))))

(use-fixtures :each cleanup-fixture)

(deftest register-and-get-test
  (testing "registers and retrieves a handler"
    (let [handler (fn [_] {:ok true})]
      (registry/register-handler! :step-a handler)
      (is (= handler (registry/get-handler :step-a)))))
  (testing "returns nil for unregistered handler"
    (is (nil? (registry/get-handler :nonexistent)))))

(deftest unregister-test
  (testing "removes a handler"
    (registry/register-handler! :step-a (fn [_] {}))
    (registry/unregister-handler! :step-a)
    (is (nil? (registry/get-handler :step-a)))))

(deftest register-bulk-test
  (testing "registers multiple handlers at once"
    (registry/register-bulk! {:step-a (fn [_] {:a true})
                               :step-b (fn [_] {:b true})})
    (is (some? (registry/get-handler :step-a)))
    (is (some? (registry/get-handler :step-b)))))

(deftest registered-handlers-test
  (testing "lists all registered handler IDs"
    (registry/register-bulk! {:step-a (fn [_] {})
                               :step-b (fn [_] {})
                               :step-c (fn [_] {})})
    (is (= 3 (count (registry/registered-handlers))))))

(deftest register-handler-return-value-test
  (testing "register-handler! returns the handler"
    (let [handler (fn [_] {:ok true})]
      (is (= handler (registry/register-handler! :step-x handler))))))

(deftest unregister-nonexistent-test
  (testing "unregistering non-existent handler is safe"
    (registry/unregister-handler! :nonexistent)
    (is (nil? (registry/get-handler :nonexistent)))))
