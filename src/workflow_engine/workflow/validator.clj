(ns workflow-engine.workflow.validator
  (:require [workflow-engine.workflow.model :as model]))

(defn validate-step
  [step]
  (cond
    (nil? (:id step)) {:valid? false :errors ["Step missing :id"]}
    (nil? (:type step)) {:valid? false :errors ["Step missing :type"]}
    (not (contains? model/step-types (:type step))) {:valid? false :errors [(str "Invalid step type: " (:type step))]}
    :else {:valid? true :errors []}))

(defn validate-workflow
  [workflow]
  (let [errors (cond-> []
                 (nil? (:id workflow)) (conj "Workflow missing :id")
                 (nil? (:name workflow)) (conj "Workflow missing :name")
                 (nil? (:steps workflow)) (conj "Workflow missing :steps")
                 (empty? (:steps workflow)) (conj "Workflow has no steps")
                 :always (into (mapcat #(let [result (validate-step %)]
                                         (when-not (:valid? result) (:errors result)))
                                      (:steps workflow))))]
    {:valid? (empty? errors)
     :errors errors}))

(defn validate-execution
  [execution]
  (cond
    (nil? (:execution-id execution)) {:valid? false :errors ["Execution missing :execution-id"]}
    (nil? (:workflow-id execution)) {:valid? false :errors ["Execution missing :workflow-id"]}
    (nil? (:status execution)) {:valid? false :errors ["Execution missing :status"]}
    (not (contains? model/execution-statuses (:status execution))) {:valid? false :errors [(str "Invalid status: " (:status execution))]}
    :else {:valid? true :errors []}))
