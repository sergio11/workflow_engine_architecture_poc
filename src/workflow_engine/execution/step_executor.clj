(ns workflow-engine.execution.step-executor
  (:require [workflow-engine.worker.handler :as wh]
            [workflow-engine.worker.registry :as registry]))

(defn resolve-handler
  "Resolve handler for a step. Checks inline handler first, then registry."
  [step]
  (or (:handler step)
      (registry/get-handler (:id step))
      (registry/get-handler (name (:id step)))))

(defn execute-step
  "Execute a step with its handler, context, and retry/timeout config."
  [handler-fn context step]
  (wh/execute-step handler-fn context step))
