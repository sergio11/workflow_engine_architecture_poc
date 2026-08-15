(ns workflow-engine.scheduler.core
  (:require [clojure.core.async :as async :refer [go go-loop <! >!]]
            [workflow-engine.scheduler.channels :as channels]
            [workflow-engine.execution.engine :as engine]
            [workflow-engine.worker.handler :as handler]
            [clojure.tools.logging :as log]))

(defn process-work!
  "Process a work item from the channel"
  [work-item]
  (let [{:keys [handler-fn context step]} work-item
        start-time (System/currentTimeMillis)
        result (try
                 (handler/execute-step handler-fn context step)
                 (catch Exception e
                   {:error (.getMessage e)}))
        duration-ms (- (System/currentTimeMillis) start-time)]
    {:work-item work-item
     :result result
     :duration-ms duration-ms}))

(defn start-worker!
  "Start a single worker that pulls from the work channel"
  [ch]
  (go-loop []
    (when-let [work-item (<! (:work-ch ch))]
      (try
        (let [result (process-work! work-item)]
          (>! (:result-ch ch) result))
        (catch Exception e
          (log/error e "Worker error:")
          (>! (:result-ch ch) {:work-item work-item
                                :result {:error (.getMessage e)}
                                :duration-ms 0})))
      (recur))))

(defn advance-if-needed!
  "After a step completes, submit the next step to the scheduler if there is one."
  [datasource execution workflow ch store recorder publisher metrics]
  (when (and (= :running (:status execution))
             (:current-step execution))
    (engine/submit-step-for-execution! store recorder publisher metrics datasource execution workflow ch)))

(defn start-result-processor!
  "Start a result processor that handles completed work and auto-advances."
  [ch store recorder publisher metrics]
  (go-loop []
    (when-let [{:keys [work-item result duration-ms]} (<! (:result-ch ch))]
      (let [{:keys [execution workflow datasource]} work-item
            step (:step work-item)]
        (try
          (let [{:keys [status next-step execution :as completion-result]}
                (engine/handle-async-completion! store recorder publisher metrics datasource execution workflow step result duration-ms)]
            (when (and (= :running status) next-step)
              (advance-if-needed! datasource execution workflow ch store recorder publisher metrics)))
          (catch Exception e
            (log/error e "Result processor error:"))))
      (recur))))

(defn start-scheduler!
  "Start the scheduler with N workers"
  ([datasource ch store recorder publisher metrics]
   (start-scheduler! datasource ch 2 store recorder publisher metrics))
  ([datasource ch num-workers store recorder publisher metrics]
   (let [workers (mapv (fn [_] (start-worker! ch)) (range num-workers))
         processor (start-result-processor! ch store recorder publisher metrics)]
     (log/info "Scheduler started with" num-workers "workers")
     {:workers workers :processor processor})))

(defn stop-scheduler!
  "Stop the scheduler"
  [scheduler-handle ch]
  (when scheduler-handle
    (channels/close-channels! ch)
    (log/info "Scheduler stopped")))
