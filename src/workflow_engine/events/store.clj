(ns workflow-engine.events.store
  (:require [workflow-engine.persistence.event-repo :as repo]
            [workflow-engine.workflow.model :as model]))

(defn record-event!
  [datasource execution-id event-type step data]
  (let [event (model/make-event event-type execution-id step data)]
    (repo/save-event! datasource event)
    event))

(defn record-workflow-started!
  [datasource execution-id]
  (record-event! datasource execution-id :workflow-started nil {:status :started}))

(defn record-step-started!
  [datasource execution-id step-id]
  (record-event! datasource execution-id :step-started step-id {:step step-id}))

(defn record-step-completed!
  [datasource execution-id step-id result]
  (record-event! datasource execution-id :step-completed step-id {:step step-id :result result}))

(defn record-step-failed!
  [datasource execution-id step-id error]
  (record-event! datasource execution-id :step-failed step-id {:step step-id :error error}))

(defn record-workflow-completed!
  [datasource execution-id]
  (record-event! datasource execution-id :workflow-completed nil {:status :completed}))

(defn record-workflow-failed!
  [datasource execution-id error]
  (record-event! datasource execution-id :workflow-failed nil {:status :failed :error error}))

(defn record-workflow-cancelled!
  [datasource execution-id]
  (record-event! datasource execution-id :workflow-cancelled nil {:status :cancelled}))

(defn get-execution-events
  [datasource execution-id]
  (repo/get-events-by-execution datasource execution-id))

(defn get-events-by-type
  [datasource event-type]
  (repo/get-events-by-type datasource event-type))

(defn clear-execution-events!
  [datasource execution-id]
  (repo/delete-events-by-execution! datasource execution-id))
