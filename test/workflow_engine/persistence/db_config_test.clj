(ns workflow-engine.persistence.db-config-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.persistence.db-config :as config]))

(deftest from-env-defaults-test
  (testing "uses defaults when env vars not set"
    (with-redefs [config/get-env (constantly nil)]
      (let [cfg (config/from-env)]
        (is (= "workflow_engine" (:db-name cfg)))
        (is (= "workflow_engine" (:db-user cfg)))
        (is (= "workflow_dev" (:db-password cfg)))
        (is (= "db" (:db-host cfg)))
        (is (= 5432 (:db-port cfg))))))
  (testing "uses env vars when set"
    (with-redefs [config/get-env (fn [k]
                                   (case k
                                     "DB_NAME" "custom_db"
                                     "DB_USER" "custom_user"
                                     "DB_PASSWORD" "secret"
                                     "DB_HOST" "remote-host"
                                     "DB_PORT" "5433"
                                     nil))]
      (let [cfg (config/from-env)]
        (is (= "custom_db" (:db-name cfg)))
        (is (= "custom_user" (:db-user cfg)))
        (is (= "secret" (:db-password cfg)))
        (is (= "remote-host" (:db-host cfg)))
        (is (= 5433 (:db-port cfg)))))))

(deftest test-config-defaults-test
  (testing "uses test defaults when env vars not set"
    (with-redefs [config/get-env (constantly nil)]
      (let [cfg (config/test-config)]
        (is (= "workflow_engine_test" (:db-name cfg)))
        (is (= "workflow_engine_test" (:db-user cfg)))
        (is (= "test_secret" (:db-password cfg)))
        (is (= "test-db" (:db-host cfg)))
        (is (= 5432 (:db-port cfg))))))
  (testing "uses env vars when set"
    (with-redefs [config/get-env (fn [k]
                                   (case k
                                     "DB_NAME" "override_db"
                                     "DB_USER" "override_user"
                                     "DB_PASSWORD" "override_pass"
                                     "DB_HOST" "override_host"
                                     "DB_PORT" "9999"
                                     nil))]
      (let [cfg (config/test-config)]
        (is (= "override_db" (:db-name cfg)))
        (is (= "override_user" (:db-user cfg)))
        (is (= "override_pass" (:db-password cfg)))
        (is (= "override_host" (:db-host cfg)))
        (is (= 9999 (:db-port cfg)))))))
