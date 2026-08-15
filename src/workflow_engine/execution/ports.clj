(ns workflow-engine.execution.ports)

(defprotocol ExecutionStore
  (save-execution! [store execution])
  (update-execution! [store execution-id updates])
  (get-execution [store execution-id]))

(defprotocol EventRecorder
  (record-workflow-started! [recorder execution-id])
  (record-step-started! [recorder execution-id step-id])
  (record-step-completed! [recorder execution-id step-id result])
  (record-step-failed! [recorder execution-id step-id error])
  (record-workflow-completed! [recorder execution-id])
  (record-workflow-failed! [recorder execution-id error])
  (record-workflow-cancelled! [recorder execution-id]))

(defprotocol EventPublisher
  (publish! [publisher event]))

(defprotocol MetricsCollector
  (record-workflow-started-metric! [collector])
  (record-workflow-completed-metric! [collector])
  (record-workflow-failed-metric! [collector])
  (record-step-execution-metric! [collector duration-ms]))
