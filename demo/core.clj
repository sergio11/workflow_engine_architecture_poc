(ns demo.core
  (:require [demo.formatting :as fmt]
            [demo.scenarios :as scenarios]
            [demo.repl-demo :as repl]
            [workflow-engine.workflow.dsl :as dsl]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.persistence.workflow-repo :as wf-repo]
            [workflow-engine.events.store :as event-store]
            [workflow-engine.metrics.collector :as metrics]
            [workflow-engine.worker.registry :as registry]
            [workflow-engine.persistence.event-repo :as event-repo]
            [clojure.string :as str]))

(defonce last-execution-id (atom nil))

(declare show-metrics show-events)

(defn print-menu []
  (fmt/print-box
    [""
     (str (fmt/bold-text " workflow-engine — Clojure Workflow Engine Demo"))
     ""
     (str (fmt/cyan-text " 1") ". User Registration Pipeline (linear)")
     (str (fmt/cyan-text " 2") ". Payment Processing (retry + flaky)")
     (str (fmt/cyan-text " 3") ". Order Fulfillment (branching)")
     (str (fmt/cyan-text " 4") ". Run ALL scenarios sequentially")
     (str (fmt/cyan-text " 5") ". Show live metrics dashboard")
     (str (fmt/cyan-text " 6") ". Show event stream (last execution)")
     (str (fmt/cyan-text " 7") ". REPL walkthrough (guided)")
     (str (fmt/cyan-text " 8") ". Show Clojure advantages summary")
     (str (fmt/cyan-text " 0") ". Exit")
     ""]))

(defn run-linear-scenario [scenario-fn title]
  (fmt/print-header title)
  (scenarios/register-all-scenarios!)
  (let [wf (scenario-fn)
        _ (wf-repo/save-workflow! @repl/datasource-ref wf)
        wf-db (wf-repo/get-workflow @repl/datasource-ref (:id wf))
        exec (engine/start-execution!
               @repl/datasource-ref wf-db
               (case (:id wf)
                 "wf-user-registration" {:user-data {:email "demo@workflow-engine.io" :name "Demo User"}}
                 "wf-payment-processing" {:amount 99.99}
                 "wf-order-fulfillment" {:product-id "PROD-42" :quantity 2}))]
    (reset! last-execution-id (:execution-id exec))
    (fmt/print-key-value "Execution ID" (fmt/cyan-text (:execution-id exec)))
    (fmt/print-key-value "Workflow" (:name wf))
    (fmt/print-key-value "Status" (fmt/status-badge (:status exec)))
    (println)
    (loop [current exec step-num 1]
      (let [result (engine/execute-step! @repl/datasource-ref current wf-db)]
        (fmt/print-step step-num (count (:steps wf-db))
          (str (fmt/green-text (str "Step " (:current-step current) " → "))
               (fmt/status-badge (:status result))))
        (when (and result (:last-result (:context result)))
          (fmt/print-info (str "Result: " (pr-str (:last-result (:context result))))))
        (if (and result
                 (not= :completed (:status result))
                 (not= :failed (:status result))
                 (:current-step result))
          (recur result (inc step-num))
          (do
            (println)
            (fmt/print-key-value "Final Status" (fmt/status-badge (:status result)))
            result))))))

(defn run-all-scenarios! []
  (fmt/print-header "Running ALL Scenarios")
  (scenarios/register-all-scenarios!)
  (let [exec1 (run-linear-scenario scenarios/create-user-registration-workflow "Scenario 1: User Registration")]
    (Thread/sleep 300)
    (let [exec2 (run-linear-scenario scenarios/create-payment-workflow "Scenario 2: Payment Processing")]
      (Thread/sleep 300)
      (run-linear-scenario scenarios/create-order-workflow "Scenario 3: Order Fulfillment")
      (println)
      (show-metrics)
      (println)
      (show-events (:execution-id exec1)))))

(defn show-metrics []
  (fmt/print-header "Live Metrics Dashboard")
  (let [snapshot (metrics/snapshot)]
    (fmt/print-table
      ["Metric" "Value"]
      [["Workflows Started" (fmt/green-text (str (get-in snapshot [:counters :workflows-started] 0)))]
       ["Workflows Completed" (fmt/green-text (str (get-in snapshot [:counters :workflows-completed] 0)))]
       ["Workflows Failed" (fmt/red-text (str (get-in snapshot [:counters :workflows-failed] 0)))]
       ["Steps Executed" (fmt/cyan-text (str (get-in snapshot [:counters :steps-executed] 0)))]
       ["Step Retries" (fmt/yellow-text (str (get-in snapshot [:counters :step-retries] 0)))]])
    (let [hist (metrics/get-histogram :step-duration)]
      (when hist
        (println)
        (fmt/print-key-value "Avg Step Duration" (fmt/green-text (fmt/format-duration (:mean hist))))
        (fmt/print-key-value "Min" (fmt/green-text (fmt/format-duration (:min hist))))
        (fmt/print-key-value "Max" (fmt/yellow-text (fmt/format-duration (:max hist))))
        (fmt/print-key-value "P50" (fmt/green-text (fmt/format-duration (:p50 hist))))
        (fmt/print-key-value "P95" (fmt/yellow-text (fmt/format-duration (:p95 hist))))
        (fmt/print-key-value "P99" (fmt/red-text (fmt/format-duration (:p99 hist))))))))

(defn show-events [exec-id]
  (fmt/print-header "Event Stream")
  (let [events (event-repo/get-events-by-execution @repl/datasource-ref exec-id)]
    (fmt/print-table
      ["Type" "Step" "Timestamp"]
      (mapv (fn [e]
              [(fmt/cyan-text (name (:type e)))
               (or (:step e) "-")
               (fmt/dim-text (str (:timestamp e)))])
            events))))

(defn show-clojure-advantages []
  (fmt/print-header "Why Clojure for Workflow Engines?")
  (println)
  (doseq [[title desc icon]
          [["Data-as-Code"
            "Workflows are plain maps. No classes, no serialization boundaries.\n    Inspect, transform, compose with standard library functions."
            "\u2728"]
           ["Immutability by Default"
            "Every state transition returns new data. No race conditions.\n    Perfect for concurrent workflow execution."
             "\ud83d\udee1"]
           ["REPL-Driven Development"
            "Modify handlers at runtime. Test interactively. No restart cycles.\n    Closest thing to mind-melding with your system."
            "\u2699"]
           ["core.async — CSP Concurrency"
            "Communicating sequential processes without locks.\n    Scheduler uses channels for work distribution."
            "\u26a1"]
           ["Functional Composition"
            "Compose handlers with comp, partial,->>. Small functions\n    combined into powerful pipelines."
             "\ud83e\udde9"]
           ["Java Interop"
            "Access entire JVM ecosystem. PostgreSQL drivers, JSON libs,\n    HTTP clients — all native Clojure deps."
            "\u2615"]
           ["Spec & Validation"
            "clojure.spec for data validation. Workflows validated\n    before execution. Runtime checks are free."
             "\ud83d\udde9"]
           ["Event Sourcing"
            "Every state change is an immutable event. Full audit trail.\n    Replay, debug, and analyze execution history."
             "\ud83d\udce9"]]]
    (println (str "  " icon " " (fmt/bold-text (fmt/magenta-text title)) " " (fmt/dim-text "\u2014")))
    (println (str "     " (fmt/dim-text desc)))
    (println)))

(defn start-demo! []
  (fmt/print-header "Starting Workflow Engine System")
  (repl/setup!)
  (loop []
    (print-menu)
    (print (str (fmt/bold-text "  \u276f Select option: ")))
    (flush)
    (let [input (str/trim (or (read-line) ""))]
      (case input
        "1" (do (run-linear-scenario scenarios/create-user-registration-workflow "Scenario 1: User Registration Pipeline") (recur))
        "2" (do (run-linear-scenario scenarios/create-payment-workflow "Scenario 2: Payment Processing with Retry") (recur))
        "3" (do (run-linear-scenario scenarios/create-order-workflow "Scenario 3: Order Fulfillment") (recur))
        "4" (do (run-all-scenarios!) (recur))
        "5" (do (show-metrics) (recur))
        "6" (do (if @last-execution-id
                  (show-events @last-execution-id)
                  (fmt/print-warning "No executions yet. Run a scenario first (1-4)"))
                (recur))
        "7" (do (repl/demonstrate-repl-driven-dev) (recur))
        "8" (do (show-clojure-advantages) (recur))
        "0" (do (fmt/print-success "Thanks for watching the workflow-engine demo!")
                   (fmt/print-info "Clojure: where data meets concurrency.")
                   (repl/teardown!))
        (do (fmt/print-warning (str "Invalid option: " input " — try 0-8"))
            (recur))))))

(defn -main [& args]
  (let [opts (set args)]
    (if (or (opts "--help") (opts "-h"))
      (do
        (println "Usage: clojure -M:demo -m demo.core [OPTIONS]")
        (println)
        (println "Options:")
        (println "  (none)         Start interactive menu")
        (println "  --run-all      Run all 3 scenarios sequentially, show metrics & events")
        (println "  --scenario N   Run scenario N (1=User Registration, 2=Payment, 3=Order)")
        (println "  --help, -h     Show this help message"))
      (do
        (repl/setup!)
        (try
          (cond
            (opts "--run-all")
            (run-all-scenarios!)

            (some opts ["--scenario" "-s"])
            (let [num (loop [a args]
                        (when (seq a)
                          (let [[f s] a]
                            (cond
                              (#{"--scenario" "-s"} f) s
                              (and s (re-matches #"\d" (subs s 0 1))) s
                              :else (recur (rest a))))))]
              (case num
                "1" (run-linear-scenario scenarios/create-user-registration-workflow "Scenario 1: User Registration Pipeline")
                "2" (run-linear-scenario scenarios/create-payment-workflow "Scenario 2: Payment Processing with Retry")
                "3" (run-linear-scenario scenarios/create-order-workflow "Scenario 3: Order Fulfillment")
                (do (println (str "Unknown scenario: " num " — use 1, 2, or 3"))
                    (System/exit 1))))

            :else
            (start-demo!))
          (finally
            (repl/teardown!)))))))

