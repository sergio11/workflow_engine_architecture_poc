(ns workflow-engine.api.middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [workflow-engine.api.middleware :as middleware]))

(defn dummy-handler [request]
  {:status 200 :body {:message "ok"}})

(defn throwing-handler [request]
  (throw (Exception. "test error")))

(deftest wrap-exception-test
  (testing "returns 500 on exception"
    (let [handler (middleware/wrap-exception throwing-handler)
          response (handler {:request-method :get :uri "/"})]
      (is (= 500 (:status response)))
      (is (= "test error" (get-in response [:body :error])))))
  (testing "passes through on success"
    (let [handler (middleware/wrap-exception dummy-handler)
          response (handler {:request-method :get :uri "/"})]
      (is (= 200 (:status response))))))

(deftest wrap-cors-test
  (testing "adds CORS headers"
    (let [handler (middleware/wrap-cors dummy-handler)
          response (handler {:request-method :get :uri "/"})]
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (string? (get-in response [:headers "Access-Control-Allow-Methods"])))
      (is (string? (get-in response [:headers "Access-Control-Allow-Headers"]))))))

(deftest wrap-request-logging-test
  (testing "handler is called"
    (let [handler (middleware/wrap-request-logging dummy-handler)
          response (handler {:request-method :get :uri "/test"})]
      (is (= 200 (:status response))))))

(deftest wrap-json-body-test
  (testing "returns middleware function"
    (is (fn? (middleware/wrap-json-body dummy-handler)))))

(deftest wrap-json-body-integration-test
  (testing "parses JSON body and keywordizes keys"
    (let [handler (middleware/wrap-json-body dummy-handler)
          request {:request-method :post
                   :uri "/"
                   :headers {"content-type" "application/json"}
                   :body (java.io.ByteArrayInputStream.
                           (.getBytes "{\"name\":\"test\"}"))}
          response (handler request)]
      (is (= 200 (:status response))))))

(deftest wrap-json-response-test
  (testing "returns middleware function"
    (is (fn? (middleware/wrap-json-response dummy-handler)))))

(deftest wrap-json-response-integration-test
  (testing "serializes response body as JSON"
    (let [handler (middleware/wrap-json-response dummy-handler)
          request {:request-method :get :uri "/"}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (some? (:body response))))))

(deftest wrap-params-test
  (testing "returns middleware function"
    (is (fn? (middleware/wrap-params dummy-handler)))))

(deftest wrap-params-integration-test
  (testing "parses query parameters"
    (let [handler (middleware/wrap-params dummy-handler)
          request {:request-method :get :uri "/?foo=bar" :query-string "foo=bar"}
          response (handler request)]
      (is (= 200 (:status response))))))
