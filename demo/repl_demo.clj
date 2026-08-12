(ns demo.repl-demo
  (:require [workflow-engine.config.system :as system]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.workflow.model :as model]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.execution.context :as ctx]
            [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.persistence.execution-repo :as exec-repo]
            [workflow-engine.events.store :as event-store]
            [workflow-engine.events.publisher :as publisher]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.worker.registry :as registry]
            [demo.scenarios :as scenarios]
            [demo.formatting :as fmt]))

(defonce system-ref (atom nil))
(defonce datasource-ref (atom nil))

(defn setup! []
  (fmt/print-header "REPL-DRIVEN DEMO — Workflow Engine")
  (println (fmt/bold-text "  Step 0: Initialize the system"))
  (println (fmt/dim-text "  Using Integrant for lifecycle management"))
  (println)
  (let [sys (system/start-system!)]
    (reset! system-ref sys)
    (reset! datasource-ref (::system/db sys))
    (fmt/print-success "System started — Integrant manages all components")
    (fmt/print-info "Datasource connected to PostgreSQL")
    (fmt/print-info "Event publisher ready (atom-based pub/sub)")
    (fmt/print-info "Metrics collector initialized"))
  (println))

(defn teardown! []
  (when-let [sys @system-ref]
    (system/stop-system! sys)
    (reset! system-ref nil)
    (reset! datasource-ref nil)
    (fmt/print-success "System stopped — all resources released")))

(defn show-dsl-usage []
  (fmt/print-header "1. DSL — Workflows as Data")
  (println (fmt/bold-text "  In Clojure, workflows are just maps. No classes, no interfaces."))
  (println)
  (fmt/print-code-block
    "(dsl/linear-workflow
      \"wf-registration\"
      \"User Registration\"
      1
      [[\"create-user\" :task]
       [\"send-email\" :task]
       [\"register-analytics\" :task]])")
  (println)
  (fmt/print-clojure-advantage
    "Data-as-Code"
    "The workflow DSL returns plain maps. You can inspect, transform,\n    and compose them like any other data structure.")
  (let [wf (scenarios/create-user-registration-workflow)]
    (fmt/print-success (str "Created workflow: " (:name wf) " with " (count (:steps wf)) " steps"))
    (fmt/print-table
      ["Step ID" "Type"]
      (mapv (fn [s] [(fmt/green-text (:id s)) (name (:type s))]) (:steps wf)))
    wf))

(defn demonstrate-immutability []
  (fmt/print-header "2. Immutability — No Shared State Bugs")
  (println (fmt/bold-text "  Every execution step returns a NEW map. Nothing is mutated."))
  (println)
  (let [ctx1 (ctx/create-context {:user-data {:email "alice@example.com" :name "Alice"}})
        ctx2 (ctx/merge-context ctx1 {:last-result {:user-id "USR-123"}})
        ctx3 (ctx/update-context ctx2 :step-count (fnil inc 0))]
    (fmt/print-key-value "Context 1 (original)" (select-keys ctx1 [:user-data]))
    (fmt/print-key-value "Context 2 (merged)" (dissoc ctx2 :user-data))
    (fmt/print-key-value "Context 3 (updated)" (dissoc ctx3 :user-data))
    (println)
    (fmt/print-clojure-advantage
      "Value Semantics"
      "ctx1 is unchanged. Each operation returns a new context.\n    No mutex, no locks, no race conditions.")
    (println)))

(defn run-scenario-1! []
  (fmt/print-header "3. Scenario 1 — User Registration Pipeline")
  (println (fmt/bold-text "  Execute a 3-step linear workflow"))
  (println)
  (scenarios/register-all-scenarios!)
  (let [wf (scenarios/create-user-registration-workflow)
        _ (wf-repo/save-workflow! @datasource-ref wf)
        exec (engine/start-execution! @datasource-ref wf {:user-data {:email "demo@workflow-engine.io" :name "Demo User"}})
        exec-id (:execution-id exec)]
    (fmt/print-key-value "Execution ID" (fmt/cyan-text exec-id))
    (fmt/print-key-value "Status" (fmt/status-badge (:status exec)))
    (println)
    (fmt/print-info "Executing steps sequentially...")
    (println)
    (loop [current exec step-num 1]
      (let [result (engine/execute-step! @datasource-ref current wf)]
        (fmt/print-step step-num 3
          (str (fmt/green-text (str "Step " (:current-step current) " \u2192 "))
               (fmt/status-badge (:status result))))
        (if (and result (not= :completed (:status result)) (not= :failed (:status result)))
          (recur result (inc step-num))
          result)))))

(defn show-events [exec-id]
  (fmt/print-header "4. Event Stream — Every State Change is Recorded")
  (println (fmt/bold-text "  Events are persisted AND published in-process"))
  (println)
  (let [events (event-store/get-execution-events @datasource-ref exec-id)]
    (fmt/print-table
      ["Type" "Step" "Timestamp"]
      (mapv (fn [e]
              [(fmt/cyan-text (name (:type e)))
               (or (:step e) "-")
               (fmt/dim-text (str (:timestamp e)))])
            events))
    (println)
    (fmt/print-clojure-advantage
      "Event Sourcing"
      "Every state change produces an immutable event record.\n    Full audit trail with zero extra code.")))

(defn show-metrics []
  (fmt/print-header "5. Metrics — In-Memory Collection")
  (println (fmt/bold-text "  Counters, gauges, histograms — all using atoms"))
  (println)
  (let [snapshot (metrics/snapshot)]
    (fmt/print-metrics-row "Workflows Started" (get-in snapshot [:counters :workflows-started] 0) fmt/green-text)
    (fmt/print-metrics-row "Workflows Completed" (get-in snapshot [:counters :workflows-completed] 0) fmt/green-text)
    (fmt/print-metrics-row "Workflows Failed" (get-in snapshot [:counters :workflows-failed] 0) fmt/red-text)
    (fmt/print-metrics-row "Steps Executed" (get-in snapshot [:counters :steps-executed] 0) fmt/cyan-text)
    (fmt/print-metrics-row "Step Retries" (get-in snapshot [:counters :step-retries] 0) fmt/yellow-text))
  (println)
  (fmt/print-clojure-advantage
    "Atoms — Lock-Free State"
    "Metrics use swap! on atoms. Compare-and-swap is atomic,\n    no locks, no deadlocks, no contention."))

(defn demonstrate-repl-driven-dev []
  (fmt/print-header "6. REPL-Driven Development")
  (println (fmt/bold-text "  Modify behavior at runtime without restarting"))
  (println)
  (fmt/print-code-block
    ";; Original handler
(defn create-user [context]
  {:user-id (str \"user-\" (System/currentTimeMillis))})

;; Modify at REPL — no restart needed!
(registry/register-handler! \"create-user\"
  (fn [context]
    {:user-id (str \"USR-\" (System/currentTimeMillis))
     :env \"production\"  ;; <-- hot-patched!
     :version \"1.1\"}))
;; Next execution uses the NEW handler immediately")
  (println)
  (scenarios/register-all-scenarios!)
  (let [original-handler (registry/get-handler "create-user")]
    (registry/register-handler! "create-user"
      (fn [ctx]
        (Thread/sleep (+ 50 (rand-int 100)))
        {:user-id (str "USR-HOTPATCHED-" (System/currentTimeMillis))
         :patched true
         :version "1.1-live"}))
    (let [wf (scenarios/create-user-registration-workflow)
          _ (wf-repo/save-workflow! @datasource-ref wf)
          exec (engine/start-execution! @datasource-ref wf {:user-data {:email "patch@test.io" :name "Patch Test"}})]
      (engine/execute-step! @datasource-ref exec wf)
      (println)
      (let [exec-after (exec-repo/get-execution @datasource-ref (:execution-id exec))]
        (fmt/print-success "Handler hot-patched successfully!")
        (fmt/print-key-value "New Result" (str (:context exec-after)))))))

(defn run-scenario-2! []
  (fmt/print-header "Scenario 2 — Payment Processing (Retry)")
  (println (fmt/bold-text "  Flaky handler with retry policy"))
  (println)
  (scenarios/register-all-scenarios!)
  (let [wf (scenarios/create-payment-workflow)
        _ (wf-repo/save-workflow! @datasource-ref wf)
        exec (engine/start-execution! @datasource-ref wf {:amount 99.99})
        exec-id (:execution-id exec)]
    (fmt/print-key-value "Execution ID" (fmt/cyan-text exec-id))
    (fmt/print-key-value "Input" "{:amount 99.99}")
    (println)
    (loop [current exec step-num 1]
      (let [result (engine/execute-step! @datasource-ref current wf)]
        (fmt/print-step step-num 3
          (str (fmt/green-text (str "Step " (:current-step current) " \u2192 "))
               (fmt/status-badge (:status result))))
        (if (and result (not= :completed (:status result)) (not= :failed (:status result)))
          (recur result (inc step-num))
          result)))))

(defn run-all-scenarios! []
  (scenarios/register-all-scenarios!)
  (let [exec1 (run-scenario-1!)
        _ (Thread/sleep 500)
        exec2 (run-scenario-2!)
        _ (println)]
    (show-metrics)
    (println)
    (show-events (:execution-id exec1))))

(defn print-welcome []
  (fmt/print-box
    [""
     (str (fmt/bold-text " workflow-engine Demo"))
     ""
     (str (fmt/dim-text " Clojure + core.async + PostgreSQL"))
     (str (fmt/dim-text " Everything is data. Everything is composable."))
     ""]))
