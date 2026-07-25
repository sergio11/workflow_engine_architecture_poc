(ns workflow-engine.core
  (:require [workflow-engine.config.system :as system]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main
  [& _args]
  (log/info "Workflow Engine starting...")
  (let [sys (system/start-system!)]
    (log/info "Workflow Engine started successfully")
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (log/info "Shutting down...")
                 (system/stop-system! sys)
                 (log/info "Shutdown complete"))))))
