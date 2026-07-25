(ns workflow-engine.api.routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.api.routes :as routes]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [cheshire.core :as json]))

(def test-datasource (atom nil))

(defn db-fixture [f]
  (let [ds (db/create-datasource (config/test-config))]
    (reset! test-datasource ds)
    (db/execute! ds ["DELETE FROM events"])
    (db/execute! ds ["DELETE FROM executions"])
    (db/execute! ds ["DELETE FROM workflows"])
    (try
      (f)
      (finally
        (db/execute! ds ["DELETE FROM events"])
        (db/execute! ds ["DELETE FROM executions"])
        (db/execute! ds ["DELETE FROM workflows"])
        (db/close-datasource! ds)
        (reset! test-datasource nil)))))

(use-fixtures :once db-fixture)

(defn read-body [response]
  (let [body (:body response)]
    (if (instance? java.io.InputStream body)
      (json/parse-stream (java.io.InputStreamReader. body "UTF-8") true)
      body)))

(defn make-app []
  (routes/api-routes @test-datasource))

(defn app-get [app path]
  (let [resp (app {:request-method :get :uri path :body-params nil})]
    (assoc resp :body (read-body resp))))

(defn app-post [app path body]
  (let [resp (app {:request-method :post :uri path :body body :body-params body})]
    (assoc resp :body (read-body resp))))

(deftest health-route-test
  (testing "GET /api/v1/health returns 200"
    (let [app (make-app)
          response (app-get app "/api/v1/health")]
      (is (= 200 (:status response)))
      (is (= "ok" (get-in response [:body :status]))))))

(deftest create-workflow-route-test
  (testing "POST /api/v1/workflows creates workflow"
    (let [app (make-app)
          response (app-post app "/api/v1/workflows"
                     {:name "Route WF" :version 1
                      :steps [{:id :step1 :type :task}]})]
      (is (= 201 (:status response)))
      (is (some? (get-in response [:body :id]))))))

(deftest get-workflow-route-test
  (testing "GET /api/v1/workflows/:id returns workflow"
    (let [app (make-app)
          create-res (app-post app "/api/v1/workflows"
                       {:name "Get WF" :version 1 :steps []})
          wf-id (get-in create-res [:body :id])
          get-res (app-get app (str "/api/v1/workflows/" wf-id))]
      (is (= 200 (:status get-res)))
      (is (= "Get WF" (get-in get-res [:body :name])))))
  (testing "returns 404 for missing workflow"
    (let [app (make-app)
          response (app-get app "/api/v1/workflows/nonexistent")]
      (is (= 404 (:status response))))))

(deftest not-found-route-test
  (testing "unknown route returns 404"
    (let [app (make-app)
          response (app-get app "/api/v1/nonexistent")]
      (is (= 404 (:status response))))))
