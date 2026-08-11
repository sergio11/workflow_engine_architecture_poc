(ns workflow-engine.persistence.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [hikari-cp.core :as hikari]))

(def test-datasource (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (try
      (f)
      (finally
        (db/close-datasource! ds)
        (reset! test-datasource nil)))))

(use-fixtures :once db-fixture)

(deftest create-datasource-test
  (testing "creates a valid datasource"
    (let [ds (db/create-datasource (config/test-config))]
      (is (some? ds))
      (db/close-datasource! ds))))

(deftest execute-test
  (testing "executes a simple query"
    (let [result (db/execute! @test-datasource ["SELECT 1 as num"])]
      (is (= 1 (count result)))
      (is (= 1 (:num (first result)))))))

(deftest execute-one-test
  (testing "executes a single row query"
    (let [result (db/execute-one! @test-datasource ["SELECT 42 as value"])]
      (is (= 42 (:value result))))))

(deftest create-datasource-defaults-test
  (testing "uses default values when config keys are missing"
    (let [captured (atom nil)]
      (with-redefs [hikari/make-datasource (fn [config] (reset! captured config) nil)]
        (db/create-datasource {}))
      (is (some? @captured))
      (is (.contains ^String (:jdbc-url @captured) "localhost"))
      (is (.contains ^String (:jdbc-url @captured) "5432"))
      (is (.contains ^String (:jdbc-url @captured) "workflow_engine"))
      (is (= "workflow_engine" (:username @captured)))
      (is (= "workflow_dev" (:password @captured))))))

(deftest create-datasource-custom-config-test
  (testing "custom config overrides defaults"
    (let [captured (atom nil)]
      (with-redefs [hikari/make-datasource (fn [config] (reset! captured config) nil)]
        (db/create-datasource {:db-host "remotehost" :db-port 5433 :db-name "mydb"
                               :db-user "admin" :db-password "secret"}))
      (is (.contains ^String (:jdbc-url @captured) "remotehost"))
      (is (.contains ^String (:jdbc-url @captured) "5433"))
      (is (.contains ^String (:jdbc-url @captured) "mydb"))
      (is (= "admin" (:username @captured)))
      (is (= "secret" (:password @captured))))))

(deftest execute-return-type-test
  (testing "execute! returns a vector of maps"
    (let [result (db/execute! @test-datasource ["SELECT 1 as a, 2 as b"])]
      (is (vector? result))
      (is (= 1 (count result)))
      (is (= 1 (:a (first result))))
      (is (= 2 (:b (first result)))))))
