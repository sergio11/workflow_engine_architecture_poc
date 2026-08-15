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

(deftest chained-middleware-test
  (testing "all middleware layers work together"
    (let [handler (-> dummy-handler
                      middleware/wrap-exception
                      middleware/wrap-cors
                      middleware/wrap-params
                      middleware/wrap-json-body
                      middleware/wrap-json-response)
          request {:request-method :get
                   :uri "/test?x=1"
                   :query-string "x=1"
                   :body nil}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (some? (:body response)))))
  (testing "exception handler works in chain"
    (let [handler (-> throwing-handler
                      middleware/wrap-exception
                      middleware/wrap-cors
                      middleware/wrap-params)
          request {:request-method :get :uri "/"}
          response (handler request)]
      (is (= 500 (:status response)))
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"]))))
    (testing "JSON body middleware with POST request"
      (let [handler (-> dummy-handler
                        middleware/wrap-json-body
                        middleware/wrap-json-response)
            request {:request-method :post
                     :uri "/"
                     :headers {"content-type" "application/json"}
                     :body (java.io.ByteArrayInputStream.
                             (.getBytes "{\"key\":\"val\"}"))}
            response (handler request)]
        (is (= 200 (:status response)))))))

(deftest wrap-json-body-only-test
  (testing "wrap-json-body parses JSON and passes through"
    (let [handler (middleware/wrap-json-body dummy-handler)
          request {:request-method :put
                   :uri "/resource"
                   :headers {"content-type" "application/json"}
                   :body (java.io.ByteArrayInputStream.
                           (.getBytes "{\"update\":true}"))}
          response (handler request)]
      (is (= 200 (:status response))))))

(deftest wrap-json-response-only-test
  (testing "wrap-json-response serializes body"
    (let [handler (-> (fn [req] {:status 200 :body {:nested {:key "val"}}})
                      middleware/wrap-json-response)
          response (handler {:request-method :get :uri "/"})]
      (is (= 200 (:status response)))
      (is (some? (:body response))))))

(deftest wrap-exception-pass-through-test
  (testing "exception middleware passes through non-exception responses"
    (let [handler (middleware/wrap-exception dummy-handler)
          response (handler {:request-method :post :uri "/data"})]
      (is (= 200 (:status response)))
      (is (= {:message "ok"} (:body response))))))

(deftest wrap-exception-exception-message-test
  (testing "exception middleware captures exception message"
    (let [handler (middleware/wrap-exception
                    (fn [req] (throw (ex-info "Custom error" {:code 42}))))
          response (handler {:request-method :get :uri "/"})]
      (is (= 500 (:status response)))
      (is (= "Custom error" (get-in response [:body :error]))))))

(deftest wrap-cors-options-test
  (testing "CORS headers present for OPTIONS request"
    (let [handler (middleware/wrap-cors dummy-handler)
          response (handler {:request-method :options :uri "/"})]
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (= "GET, POST, PUT, DELETE, OPTIONS"
             (get-in response [:headers "Access-Control-Allow-Methods"])))
      (is (= "Content-Type, Authorization"
             (get-in response [:headers "Access-Control-Allow-Headers"]))))))

(deftest wrap-cors-post-test
  (testing "CORS headers present for POST request"
    (let [handler (middleware/wrap-cors dummy-handler)
          response (handler {:request-method :post :uri "/api/data"})]
      (is (= "*" (get-in response [:headers "Access-Control-Allow-Origin"])))
      (is (= 200 (:status response))))))

(deftest wrap-request-logging-output-test
  (testing "logging does not interfere with request processing"
    (let [handler (middleware/wrap-request-logging dummy-handler)
          response (handler {:request-method :post :uri "/api/test"})]
      (is (= 200 (:status response))))))
