(ns workflow-engine.worker.handler
  (:require [workflow-engine.worker.retry :as retry]
            [clojure.tools.logging :as log]))

(defn execute-with-timeout
  [handler context timeout-ms]
  (if timeout-ms
    (let [future-result (future (handler context))
          result (deref future-result timeout-ms ::timeout)]
      (if (= result ::timeout)
        (do
          (future-cancel future-result)
          {:error "Step execution timed out" :timeout timeout-ms})
        result))
    (handler context)))

(defn execute-step
  [handler context step]
  (let [retry-policy (retry/step-retry-policy step)
        timeout-ms (:timeout step)]
    (if retry-policy
      (loop [attempt 0]
        (let [result (try
                       (execute-with-timeout handler context timeout-ms)
                       (catch Exception e
                         {:error (.getMessage e)}))]
          (if (and (:error result)
                   (retry/should-retry? retry-policy attempt))
            (let [delay-ms (retry/retry-delay retry-policy attempt)]
              (log/info "Step failed, retrying in" delay-ms "ms (attempt" (inc attempt) "/" (:max-attempts retry-policy) ")")
              (Thread/sleep delay-ms)
              (recur (inc attempt)))
            result)))
      (try
        (execute-with-timeout handler context timeout-ms)
        (catch Exception e
          {:error (.getMessage e)})))))
