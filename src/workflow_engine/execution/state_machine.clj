(ns workflow-engine.execution.state-machine)

(def valid-transitions
  {:pending   #{:running}
   :running   #{:waiting :completed :failed :cancelled}
   :waiting   #{:running :cancelled}
   :failed    #{:running}
   :completed #{}
   :cancelled #{}})

(defn valid-transition?
  [from to]
  (contains? (get valid-transitions from #{}) to))

(defn next-state
  [current-state event]
  (case [current-state event]
    [:pending :start] :running
    [:running :wait] :waiting
    [:running :complete] :completed
    [:running :fail] :failed
    [:running :cancel] :cancelled
    [:waiting :resume] :running
    [:waiting :cancel] :cancelled
    [:failed :retry] :running
    nil))

(defn determine-next-status
  [step-type result]
  (case step-type
    :task (if (:error result) :failed :completed)
    :wait :completed
    :decision (if (:branch result) :completed :failed)
    :parallel (if (every? #(not (:error %)) result) :completed :failed)
    :failed))
