(ns workflow-engine.execution.context)

(defn create-context
  [input-data]
  (merge {:started-at (java.time.Instant/now)} input-data))

(defn update-context
  [context key value]
  (assoc context key value))

(defn merge-context
  [context updates]
  (merge context updates))

(defn get-from-context
  [context key]
  (get context key))

(defn add-to-history
  [context event]
  (update context :history (fnil conj []) event))
