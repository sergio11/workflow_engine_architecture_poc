(ns workflow-engine.scheduler.channels
  (:require [clojure.core.async :as async]))

(defonce work-ch (async/chan 100))
(defonce result-ch (async/chan 100))
(defonce event-ch (async/chan 100))

(defn create-channels
  "Create a new set of channels for the scheduler pipeline."
  []
  {:work-ch (async/chan 100)
   :result-ch (async/chan 100)
   :event-ch (async/chan 100)})

(defn close-channels!
  "Close all channels in the given channels map."
  [ch]
  (when (:work-ch ch) (async/close! (:work-ch ch)))
  (when (:result-ch ch) (async/close! (:result-ch ch)))
  (when (:event-ch ch) (async/close! (:event-ch ch))))

(defn submit-work!
  "Put work item onto the work channel"
  ([work-item]
   (async/put! work-ch work-item))
  ([ch work-item]
   (async/put! (:work-ch ch) work-item)))

(defn submit-result!
  "Put result onto the result channel"
  ([result]
   (async/put! result-ch result))
  ([ch result]
   (async/put! (:result-ch ch) result)))

(defn submit-event!
  "Put event onto the event channel"
  ([event]
   (async/put! event-ch event))
  ([ch event]
   (async/put! (:event-ch ch) event)))

(defn close-all! []
  (async/close! work-ch)
  (async/close! result-ch)
  (async/close! event-ch))
