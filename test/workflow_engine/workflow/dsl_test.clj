(ns workflow-engine.workflow.dsl-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.workflow.dsl :as dsl]))

(deftest linear-workflow-test
  (testing "creates linear workflow"
    (let [steps [[:create-user :task nil nil nil]
                 [:send-email :task nil nil nil]]
          wf (dsl/linear-workflow "test" "Test" 1 steps)]
      (is (= "test" (:id wf)))
      (is (= 2 (count (:steps wf))))
      (is (= :create-user (:id (first (:steps wf))))))))

(deftest task-step-test
  (testing "creates task step vector"
    (let [step (dsl/task-step :my-task (fn [ctx] :done))]
      (is (= :my-task (first step)))
      (is (= :task (second step))))))

(deftest wait-step-test
  (testing "creates wait step with handler"
    (let [step (dsl/wait-step :pause 1000)]
      (is (= :pause (:id step)))
      (is (= :wait (:type step)))
      (is (fn? (:handler step))))))

(deftest decision-step-test
  (testing "creates decision step"
    (let [step (dsl/decision-step :check (fn [ctx] true) :vip :basic)]
      (is (= :check (:id step)))
      (is (= :decision (:type step)))
      (is (fn? (:handler step))))))

(deftest get-step-by-id-test
  (testing "finds step by id"
    (let [steps [[:a :task nil nil nil] [:b :task nil nil nil]]
          wf (dsl/linear-workflow "t" "T" 1 steps)
          found (dsl/get-step-by-id wf :b)]
      (is (= :b (:id found))))))

(deftest next-step-test
  (testing "returns next step in sequence"
    (let [steps [[:a :task nil nil nil] [:b :task nil nil nil] [:c :task nil nil nil]]
          wf (dsl/linear-workflow "t" "T" 1 steps)
          next-s (dsl/next-step wf :a)]
      (is (= :b (:id next-s)))))
  (testing "returns nil for last step"
    (let [steps [[:a :task nil nil nil]]
          wf (dsl/linear-workflow "t" "T" 1 steps)]
      (is (nil? (dsl/next-step wf :a))))))

(deftest task-step-with-retry-test
  (testing "creates step with retry and timeout"
    (let [step (dsl/task-step-with-retry :retry-task (fn [ctx] :done) {:max-attempts 3} 5000)]
      (is (= :retry-task (first step)))
      (is (= :task (second step)))
      (is (= {:max-attempts 3} (nth step 3)))
      (is (= 5000 (nth step 4))))))

(deftest parallel-step-test
  (testing "creates parallel step with sub-steps"
    (let [sub-steps [{:handler (fn [ctx] :a)} {:handler (fn [ctx] :b)}]
          step (dsl/parallel-step :parallel sub-steps)]
      (is (= :parallel (:id step)))
      (is (= :parallel (:type step)))
      (is (fn? (:handler step)))
      (is (= [:a :b] ((:handler step) {}))))))

(deftest get-steps-test
  (testing "returns steps from workflow"
    (let [steps [[:a :task nil nil nil] [:b :task nil nil nil]]
          wf (dsl/linear-workflow "t" "T" 1 steps)]
      (is (= 2 (count (dsl/get-steps wf)))))))
