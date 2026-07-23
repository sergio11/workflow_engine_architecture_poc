(ns workflow-engine.worker.retry)

(defn exponential-backoff
  [attempt base-delay max-delay]
  (min (long (* base-delay (Math/pow 2 attempt))) max-delay))

(defn fixed-delay
  [_attempt base-delay _max-delay]
  base-delay)

(defn no-delay
  [_attempt]
  0)

(def default-retry-policy
  {:max-attempts 3
   :delay-fn fixed-delay
   :base-delay 1000
   :max-delay 30000})

(defn make-retry-policy
  [{:keys [max-attempts delay base-delay max-delay]
    :or {max-attempts 3
         delay fixed-delay
         base-delay 1000
         max-delay 30000}}]
  {:max-attempts max-attempts
   :delay-fn (or delay fixed-delay)
   :base-delay base-delay
   :max-delay max-delay})

(defn should-retry?
  [policy attempt]
  (< attempt (dec (:max-attempts policy))))

(defn retry-delay
  [policy attempt]
  ((:delay-fn policy) attempt (:base-delay policy) (:max-delay policy)))

(defn step-retry-policy
  [step]
  (let [retry-config (:retry step)]
    (cond
      (nil? retry-config) nil
      (int? retry-config) (make-retry-policy {:max-attempts retry-config})
      (map? retry-config) (make-retry-policy retry-config)
      :else nil)))
