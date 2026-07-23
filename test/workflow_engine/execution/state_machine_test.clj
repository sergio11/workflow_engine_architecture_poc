(ns workflow-engine.execution.state-machine-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.execution.state-machine :as sm]))

(deftest valid-transition-test
  (testing "valid transitions"
    (is (sm/valid-transition? :pending :running))
    (is (sm/valid-transition? :running :completed))
    (is (sm/valid-transition? :running :failed))
    (is (sm/valid-transition? :running :waiting))
    (is (sm/valid-transition? :running :cancelled))
    (is (sm/valid-transition? :waiting :running))
    (is (sm/valid-transition? :waiting :cancelled))
    (is (sm/valid-transition? :failed :running)))
  (testing "invalid transitions"
    (is (not (sm/valid-transition? :pending :completed)))
    (is (not (sm/valid-transition? :completed :running)))
    (is (not (sm/valid-transition? :cancelled :running)))
    (is (not (sm/valid-transition? :pending :failed)))))

(deftest next-state-test
  (testing "state transitions"
    (is (= :running (sm/next-state :pending :start)))
    (is (= :waiting (sm/next-state :running :wait)))
    (is (= :completed (sm/next-state :running :complete)))
    (is (= :failed (sm/next-state :running :fail)))
    (is (= :cancelled (sm/next-state :running :cancel)))
    (is (= :running (sm/next-state :waiting :resume)))
    (is (= :cancelled (sm/next-state :waiting :cancel)))
    (is (= :running (sm/next-state :failed :retry))))
  (testing "nil for invalid"
    (is (nil? (sm/next-state :pending :complete)))
    (is (nil? (sm/next-state :completed :start)))))

(deftest determine-next-status-test
  (testing "task success"
    (is (= :completed (sm/determine-next-status :task {:result "ok"}))))
  (testing "task error"
    (is (= :failed (sm/determine-next-status :task {:error "boom"}))))
  (testing "wait"
    (is (= :completed (sm/determine-next-status :wait {:waited 1000}))))
  (testing "decision"
    (is (= :completed (sm/determine-next-status :decision {:branch :vip}))))
  (testing "parallel all success"
    (is (= :completed (sm/determine-next-status :parallel [{:ok true} {:ok true}]))))
  (testing "parallel with error"
    (is (= :failed (sm/determine-next-status :parallel [{:ok true} {:error "fail"}])))))
