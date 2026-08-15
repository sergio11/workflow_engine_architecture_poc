(ns workflow-engine.events.publisher
  (:require [clojure.tools.logging :as log]))

(defonce subscribers (atom {}))

(defn subscribe!
  "Subscribe to an event type. Handler fn receives [event].
   Returns an unsubscribe function."
  [event-type handler-fn]
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! subscribers update event-type assoc id handler-fn)
    (fn []
      (swap! subscribers update event-type dissoc id))))

(defn subscribe-all!
  "Subscribe to all event types. Handler fn receives [event]."
  [handler-fn]
  (let [ids (atom [])]
    (doseq [event-type [:workflow-started :step-started :step-completed
                        :step-failed :workflow-completed :workflow-failed
                        :workflow-cancelled]]
      (let [unsub (subscribe! event-type handler-fn)]
        (swap! ids conj unsub)))
    (fn []
      (doseq [unsub @ids]
        (unsub)))))

(defn publish!
  "Publish an event to all subscribers of its type."
  [event]
  (let [event-type (:type event)
        handlers (get @subscribers event-type)]
    (doseq [[_id handler-fn] handlers]
      (try
        (handler-fn event)
        (catch Exception e
          (log/error e "Error in event subscriber:"))))))

(defn clear-subscribers!
  []
  (reset! subscribers {}))

(defn subscriber-count
  ([] (reduce + (map count (vals @subscribers))))
  ([event-type]
   (count (get @subscribers event-type {}))))
