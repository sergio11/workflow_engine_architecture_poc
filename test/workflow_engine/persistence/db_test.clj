(ns workflow-engine.persistence.db-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]))

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
