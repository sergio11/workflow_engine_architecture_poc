(ns workflow-engine.scheduler.core
  (:require [clojure.core.async :as async :refer [go go-loop <! >!]]
            [workflow-engine.scheduler.channels :as channels]
            [workflow-engine.worker.handler :as handler]
            [clojure.tools.logging :as log]))

(defn process-work!
  "Process a work item from the channel"
  [datasource work-item]
  (let [{:keys [handler-fn context step]} work-item
        result (handler/execute-step handler-fn context step)]
    {:work-item work-item
     :result result}))

(defn start-worker!
  "Start a single worker that pulls from the work channel"
  [datasource]
  (go-loop []
    (when-let [work-item (<! channels/work-ch)]
      (try
        (let [result (process-work! datasource work-item)]
          (>! channels/result-ch result))
        (catch Exception e
          (log/error "Worker error:" (.getMessage e))
          (>! channels/result-ch {:error (.getMessage e)})))
      (recur))))

(defn start-result-processor!
  "Start a result processor that handles completed work"
  []
  (go-loop []
    (when-let [result (<! channels/result-ch)]
      (log/info "Result received:" (:result result))
      (recur))))

(defn start-scheduler!
  "Start the scheduler with N workers"
  ([datasource]
   (start-scheduler! datasource 1))
  ([datasource num-workers]
   (let [workers (mapv (fn [_] (start-worker! datasource)) (range num-workers))
         processor (start-result-processor!)]
     (log/info "Scheduler started with" num-workers "workers")
     {:workers workers :processor processor})))

(defn stop-scheduler!
  "Stop the scheduler by closing channels"
  ([] (channels/close-all!) (log/info "Scheduler stopped"))
  ([scheduler-handle]
   (when scheduler-handle
     (channels/close-all!)
     (log/info "Scheduler stopped"))))
