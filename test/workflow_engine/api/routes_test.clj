(ns workflow-engine.api.routes-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.api.routes :as routes]
            [workflow-engine.persistence.db :as db]
            [workflow-engine.persistence.db-config :as config]
            [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.persistence.execution-repo :as exec-repo]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.worker.registry :as registry]
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

(defn app-post-json [app path json-str]
  (let [resp (app {:request-method :post
                   :uri path
                   :headers {"content-type" "application/json"}
                   :body (java.io.ByteArrayInputStream.
                           (.getBytes json-str "UTF-8"))})]
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
                      :steps [[:step1 :task]]})]
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

(deftest create-workflow-with-json-body-test
  (testing "JSON body with map-shaped steps creates a valid workflow"
    (let [app (make-app)
          response (app-post-json app "/api/v1/workflows"
                     (json/generate-string
                       {:name "JSON WF" :version 2
                        :steps [{:id "jone" :type "task"}
                                {:id "jtwo" :type "task"
                                 :retry {:max-attempts 2} :timeout 5000}]}))]
      (is (= 201 (:status response)))
      (let [wf-id (get-in response [:body :id])
            stored (wf-repo/get-workflow @test-datasource wf-id)]
        (is (= 2 (count (:steps stored))))
        (is (= :task (:type (first (:steps stored)))))
        (is (= :jone (:id (first (:steps stored)))))))))

(deftest invalid-json-workflow-test
  (testing "workflow with an invalid step type is rejected"
    (let [app (make-app)
          response (app-post-json app "/api/v1/workflows"
                     (json/generate-string
                       {:name "Bad WF" :steps [{:id "x" :type :nope}]}))]
      (is (= 400 (:status response)))
      (is (seq (get-in response [:body :details :errors]))))))

(deftest full-rest-execution-test
  (testing "create from JSON, start execution, drive to completion via registry handlers"
    (let [app (make-app)
          _ (registry/clear-registry!)
          create-resp (app-post-json app "/api/v1/workflows"
                        (json/generate-string
                          {:name "Exec WF"
                           :steps [{:id "e1" :type "task"}
                                   {:id "e2" :type "task"}]}))]
      (is (= 201 (:status create-resp)))
      (let [wf-id (get-in create-resp [:body :id])
            _ (registry/register-handler! "e1" (fn [ctx] {:greeting (str "Hello " (:user ctx))}))
            _ (registry/register-handler! "e2" (fn [ctx] {:bye "Goodbye"}))
            start-resp (app-post-json app "/api/v1/executions"
                         (json/generate-string
                           {:workflow-id wf-id :input {:user "World"}}))]
        (is (= 201 (:status start-resp)))
        (let [exec-id (get-in start-resp [:body :execution-id])
              wf (wf-repo/get-workflow @test-datasource wf-id)
              exec (assoc (exec-repo/get-execution @test-datasource exec-id) :status :running)
              step1 (engine/execute-step! @test-datasource exec wf)
              step2 (engine/execute-step! @test-datasource step1 wf)]
          (is (= :completed (:status step2)))
          (is (= :e2 (:current-step step1)))
          (is (= "Goodbye" (get-in step2 [:context :last-result :bye]))))))))

(deftest full-rest-e2e-with-retry-test
  (testing "JSON workflow with retry config: create → start → execute step with retry → complete"
    (let [app (make-app)
          _ (registry/clear-registry!)
          create-resp (app-post-json app "/api/v1/workflows"
                        (json/generate-string
                          {:name "Retry WF"
                           :steps [{:id "flaky" :type "task"
                                    :retry {:max-attempts 3 :base-delay 10 :max-delay 50}}
                                   {:id "final" :type "task"}]}))]
      (is (= 201 (:status create-resp)))
      (let [wf-id (get-in create-resp [:body :id])
            attempts (atom 0)
            _ (registry/register-handler! "flaky"
                (fn [_ctx]
                  (if (< (swap! attempts inc) 2)
                    {:error "transient" :retryable true}
                    {:ok true})))
            _ (registry/register-handler! "final" (fn [_ctx] {:done true}))
            start-resp (app-post-json app "/api/v1/executions"
                         (json/generate-string {:workflow-id wf-id :input {}}))]
        (is (= 201 (:status start-resp)))
        (let [exec-id (get-in start-resp [:body :execution-id])
              wf (wf-repo/get-workflow @test-datasource wf-id)
              exec (assoc (exec-repo/get-execution @test-datasource exec-id) :status :running)
              step1 (engine/execute-step! @test-datasource exec wf)
              step2 (engine/execute-step! @test-datasource step1 wf)]
          (is (= :completed (:status step2)))
          (is (= :final (:current-step step1)))
          (is (>= @attempts 2))
          (is (true? (get-in step2 [:context :last-result :done]))))))))
