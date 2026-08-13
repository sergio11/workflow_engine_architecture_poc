(ns workflow-engine.workflow.dsl
  (:require [workflow-engine.workflow.model :as model]))

(defn linear-workflow
  [id name version steps]
  (model/make-workflow id name version
    (mapv #(if (map? %)
             (model/step-from-map %)
             (apply model/make-step %))
          steps)))

(defn task-step
  [id handler]
  (model/make-step id :task handler nil nil))

(defn task-step-with-retry
  [id handler retry timeout]
  (model/make-step id :task handler retry timeout))

(defn wait-step
  [id duration]
  (model/make-step id :wait (fn [_ctx] (Thread/sleep duration) {:waited duration}) nil nil))

(defn decision-step
  [id condition true-branch false-branch]
  (model/make-step id :decision (fn [ctx] {:branch (if (condition ctx) true-branch false-branch)}) nil nil {true-branch true-branch false-branch false-branch}))

(defn parallel-step
  [id steps]
  (model/make-step id :parallel (fn [ctx] (doall (pmap #((:handler %) ctx) steps))) nil nil))

(defn get-steps
  [workflow]
  (:steps workflow))

(defn get-step-by-id
  [workflow step-id]
  (first (filter #(= (:id %) step-id) (:steps workflow))))

(defn next-step
  [workflow current-step-id]
  (let [steps (vec (:steps workflow))
        idx (first (keep-indexed (fn [i s] (when (= (:id s) current-step-id) i)) steps))]
    (when (and idx (< idx (dec (count steps))))
      (nth steps (inc idx)))))
