(ns workflow-engine.api.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.api.server :as server]
            [ring.adapter.jetty :as jetty]))

(defn cleanup-fixture [f]
  (reset! server/server nil)
  (try
    (f)
    (finally
      (reset! server/server nil))))

(use-fixtures :each cleanup-fixture)

(deftest stop-server-when-nil-test
  (testing "stop-server! is safe when no server running"
    (reset! server/server nil)
    (server/stop-server!)
    (is (nil? @server/server))))

(deftest stop-server-with-mock-test
  (testing "stop-server! stops server and resets atom"
    (reset! server/server :mock-server)
    (server/stop-server!)
    (is (nil? @server/server))))

(deftest start-server-test
  (testing "start-server! starts jetty and returns server map"
    (let [mock-jetty (proxy [org.eclipse.jetty.server.Server] [])]
      (with-redefs [jetty/run-jetty (fn [handler opts] mock-jetty)]
        (let [result (server/start-server! nil nil nil nil nil nil)]
          (is (some? (:server result)))
          (is (= mock-jetty @server/server)))))))

(deftest start-server-default-port-test
  (testing "start-server! uses default port 3000"
    (let [captured-opts (atom nil)
          mock-jetty (proxy [org.eclipse.jetty.server.Server] [])]
      (with-redefs [jetty/run-jetty (fn [handler opts] (reset! captured-opts opts) mock-jetty)]
        (server/start-server! nil nil nil nil nil nil)
        (is (= 3000 (:port @captured-opts)))
        (is (false? (:join? @captured-opts)))))))

(deftest start-server-custom-port-test
  (testing "start-server! uses port from get-port"
    (let [captured-opts (atom nil)
          mock-jetty (proxy [org.eclipse.jetty.server.Server] [])]
      (with-redefs [jetty/run-jetty (fn [handler opts] (reset! captured-opts opts) mock-jetty)
                    server/get-port (fn [] 8080)]
        (server/start-server! nil nil nil nil nil nil)
        (is (= 8080 (:port @captured-opts)))))))

(deftest get-port-default-test
  (testing "get-port returns an integer"
    (let [port (server/get-port)]
      (is (integer? port))
      (is (pos? port)))))
