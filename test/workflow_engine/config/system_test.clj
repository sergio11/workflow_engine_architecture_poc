(ns workflow-engine.config.system-test
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [workflow-engine.config.system :as system]
            [workflow-engine.persistence.db :as db]))

(deftest db-init-and-halt-test
  (testing "init and halt db component"
    (let [datasource (ig/init-key :workflow-engine/db {})]
      (is (some? datasource))
      (ig/halt-key! :workflow-engine/db datasource)
      (is true "datasource closed without error"))))

(deftest publisher-init-test
  (testing "init publisher component"
    (let [pub (ig/init-key :workflow-engine/publisher {})]
      (is (some? pub)))))

(deftest metrics-init-test
  (testing "init metrics component"
    (let [m (ig/init-key :workflow-engine/metrics {})]
      (is (some? m)))))
