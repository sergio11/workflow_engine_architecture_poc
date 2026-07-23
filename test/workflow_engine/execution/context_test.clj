(ns workflow-engine.execution.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.execution.context :as ctx]))

(deftest create-context-test
  (testing "creates context with started-at"
    (let [c (ctx/create-context {:user "123"})]
      (is (= "123" (:user c)))
      (is (some? (:started-at c))))))

(deftest update-context-test
  (testing "updates a key"
    (let [c (ctx/create-context {:user "123"})
          updated (ctx/update-context c :status :active)]
      (is (= :active (:status updated)))
      (is (= "123" (:user updated))))))

(deftest merge-context-test
  (testing "merges multiple keys"
    (let [c (ctx/create-context {:user "123"})
          merged (ctx/merge-context c {:role :admin :active true})]
      (is (= "123" (:user merged)))
      (is (= :admin (:role merged)))
      (is (true? (:active merged))))))

(deftest get-from-context-test
  (testing "gets value"
    (let [c {:user "123"}]
      (is (= "123" (ctx/get-from-context c :user)))
      (is (nil? (ctx/get-from-context c :missing))))))

(deftest add-to-history-test
  (testing "adds event to history"
    (let [c {:history []}
          updated (ctx/add-to-history c {:type :step-completed})]
      (is (= 1 (count (:history updated))))
      (is (= :step-completed (:type (first (:history updated)))))))
  (testing "works with nil history"
    (let [c {}
          updated (ctx/add-to-history c {:type :started})]
      (is (= 1 (count (:history updated)))))))
