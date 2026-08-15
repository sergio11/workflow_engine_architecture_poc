(ns workflow-engine.execution.transitions
  (:require [workflow-engine.execution.state-machine :as sm]
            [workflow-engine.workflow.dsl :as dsl]))

(defn determine-next-step
  "Determine the next step for a workflow after completing the given step."
  [workflow current-step-id result step-type]
  (cond
    (and (= step-type :decision)
         (= :completed (sm/determine-next-status step-type result))
         (:branch result))
    (let [step (dsl/get-step-by-id workflow current-step-id)
          branch-kw (:branch result)
          branch-step-id (get-in step [:branches branch-kw])]
      (when branch-step-id
        (dsl/get-step-by-id workflow branch-step-id)))

    (and (= step-type :decision)
         (= :failed (sm/determine-next-status step-type result)))
    nil

    :else
    (dsl/next-step workflow current-step-id)))

(defn build-updates
  "Build the execution updates map based on step result and next step."
  [execution step result next-step new-status]
  (cond-> {:status new-status
           :context (update (:context execution) :last-result merge result)}
    (and next-step (not= new-status :failed)) (assoc :current-step (:id next-step))
    (and (not next-step)) (assoc :current-step nil)
    (= new-status :completed) (assoc :completed-at (java.time.Instant/now))))
