(ns workflow-engine.workflow.validator-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.workflow.validator :as validator]
            [workflow-engine.workflow.model :as model]))

(deftest validate-step-test
  (testing "valid step"
    (let [result (validator/validate-step (model/make-step :a :task))]
      (is (:valid? result))))
  (testing "missing id"
    (let [result (validator/validate-step {:type :task})]
      (is (not (:valid? result)))
      (is (some #(re-find #"id" %) (:errors result)))))
  (testing "missing type"
    (let [result (validator/validate-step {:id :a})]
      (is (not (:valid? result)))))
  (testing "invalid type"
    (let [result (validator/validate-step {:id :a :type :invalid})]
      (is (not (:valid? result))))))

(deftest validate-workflow-test
  (testing "valid workflow"
    (let [wf (model/make-workflow "w1" "Test" 1 [(model/make-step :a :task)])
          result (validator/validate-workflow wf)]
      (is (:valid? result))))
  (testing "missing id"
    (let [result (validator/validate-workflow {:name "T" :steps [{}]})]
      (is (not (:valid? result)))))
  (testing "missing steps"
    (let [result (validator/validate-workflow {:id "w" :name "T"})]
      (is (not (:valid? result)))))
  (testing "empty steps"
    (let [result (validator/validate-workflow {:id "w" :name "T" :steps []})]
      (is (not (:valid? result))))))

(deftest validate-execution-test
  (testing "valid execution"
    (let [exec (model/make-execution "e1" "w1" {})
          result (validator/validate-execution exec)]
      (is (:valid? result))))
  (testing "missing execution-id"
    (let [result (validator/validate-execution {:workflow-id "w" :status :pending})]
      (is (not (:valid? result)))))
  (testing "invalid status"
    (let [result (validator/validate-execution {:execution-id "e" :workflow-id "w" :status :invalid})]
      (is (not (:valid? result)))))
  (testing "missing workflow-id"
    (let [result (validator/validate-execution {:execution-id "e" :status :pending})]
      (is (not (:valid? result)))))
  (testing "missing status"
    (let [result (validator/validate-execution {:execution-id "e" :workflow-id "w"})]
      (is (not (:valid? result))))))

(deftest validate-workflow-missing-name-test
  (testing "missing name"
    (let [result (validator/validate-workflow {:id "w" :steps [{}]})]
      (is (not (:valid? result))))))
