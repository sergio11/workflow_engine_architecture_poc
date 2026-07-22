(ns workflow-engine.workflow.model-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.workflow.model :as model]))

(deftest make-step-test
  (testing "creates step with minimal args"
    (let [step (model/make-step :create-user :task)]
      (is (= :create-user (:id step)))
      (is (= :task (:type step)))))
  (testing "creates step with handler"
    (let [step (model/make-step :send-email :task (fn [ctx] :sent))]
      (is (= :send-email (:id step)))
      (is (fn? (:handler step)))))
  (testing "creates step with all args"
    (let [step (model/make-step :process :task (fn [ctx] :done) 3 30000)]
      (is (= 3 (:retry step)))
      (is (= 30000 (:timeout step))))))

(deftest make-workflow-test
  (testing "creates workflow with steps"
    (let [steps [(model/make-step :a :task) (model/make-step :b :task)]
          wf (model/make-workflow "onboarding" "Onboarding" 1 steps)]
      (is (= "onboarding" (:id wf)))
      (is (= "Onboarding" (:name wf)))
      (is (= 1 (:version wf)))
      (is (= 2 (count (:steps wf)))))))

(deftest make-execution-test
  (testing "creates execution with pending status"
    (let [exec (model/make-execution "exec-1" "onboarding" {:user "123"})]
      (is (= "exec-1" (:execution-id exec)))
      (is (= "onboarding" (:workflow-id exec)))
      (is (= :pending (:status exec)))
      (is (= {:user "123"} (:context exec)))
      (is (nil? (:current-step exec)))
      (is (= [] (:history exec))))))

(deftest make-event-test
  (testing "creates event with timestamp"
    (let [evt (model/make-event :workflow-started "exec-1" :create-user {})]
      (is (= :workflow-started (:type evt)))
      (is (= "exec-1" (:execution-id evt)))
      (is (= :create-user (:step evt)))
      (is (some? (:timestamp evt))))))
