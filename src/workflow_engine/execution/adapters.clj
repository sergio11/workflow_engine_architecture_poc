(ns workflow-engine.execution.adapters
  (:require [workflow-engine.execution.ports :as ports]
            [workflow-engine.persistence.execution-repo :as repo]
            [workflow-engine.events.store :as event-store]
            [workflow-engine.events.publisher :as publisher]
            [workflow-engine.metrics.collector :as metrics]))

(defrecord DatasourceExecutionStore [datasource]
  ports/ExecutionStore
  (save-execution! [_ execution]
    (repo/save-execution! datasource execution))
  (update-execution! [_ execution-id updates]
    (repo/update-execution! datasource execution-id updates))
  (get-execution [_ execution-id]
    (repo/get-execution datasource execution-id)))

(defrecord DatasourceEventRecorder [datasource]
  ports/EventRecorder
  (record-workflow-started! [_ execution-id]
    (event-store/record-workflow-started! datasource execution-id))
  (record-step-started! [_ execution-id step-id]
    (event-store/record-step-started! datasource execution-id step-id))
  (record-step-completed! [_ execution-id step-id result]
    (event-store/record-step-completed! datasource execution-id step-id result))
  (record-step-failed! [_ execution-id step-id error]
    (event-store/record-step-failed! datasource execution-id step-id error))
  (record-workflow-completed! [_ execution-id]
    (event-store/record-workflow-completed! datasource execution-id))
  (record-workflow-failed! [_ execution-id error]
    (event-store/record-workflow-failed! datasource execution-id error))
  (record-workflow-cancelled! [_ execution-id]
    (event-store/record-workflow-cancelled! datasource execution-id)))

(defrecord AtomEventPublisher []
  ports/EventPublisher
  (publish! [_ event]
    (publisher/publish! event)))

(defrecord AtomMetricsCollector []
  ports/MetricsCollector
  (record-workflow-started-metric! [_]
    (metrics/record-workflow-started!))
  (record-workflow-completed-metric! [_]
    (metrics/record-workflow-completed!))
  (record-workflow-failed-metric! [_]
    (metrics/record-workflow-failed!))
  (record-step-execution-metric! [_ duration-ms]
    (metrics/record-step-execution! duration-ms)))

(defn create-store [datasource]
  (->DatasourceExecutionStore datasource))

(defn create-recorder [datasource]
  (->DatasourceEventRecorder datasource))

(defn create-publisher []
  (->AtomEventPublisher))

(defn create-metrics-collector []
  (->AtomMetricsCollector))
