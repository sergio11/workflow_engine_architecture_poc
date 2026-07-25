(ns workflow-engine.scheduler.channels
  (:require [clojure.core.async :as async]))

(def work-ch (async/chan 100))
(def result-ch (async/chan 100))
(def event-ch (async/chan 100))

(defn submit-work!
  "Put work item onto the work channel"
  [work-item]
  (async/put! work-ch work-item))

(defn submit-result!
  "Put result onto the result channel"
  [result]
  (async/put! result-ch result))

(defn submit-event!
  "Put event onto the event channel"
  [event]
  (async/put! event-ch event))

(defn close-all! []
  (async/close! work-ch)
  (async/close! result-ch)
  (async/close! event-ch))
