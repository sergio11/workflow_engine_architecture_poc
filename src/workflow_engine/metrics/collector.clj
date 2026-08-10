(ns workflow-engine.metrics.collector)

(defonce metrics (atom {:counters {}
                        :gauges {}
                        :histograms {}}))

(defn inc-counter! [name]
  (swap! metrics update-in [:counters name] (fnil inc 0)))

(defn dec-counter! [name]
  (swap! metrics update-in [:counters name] (fnil dec 0)))

(defn set-gauge! [name value]
  (swap! metrics assoc-in [:gauges name] value))

(defn record-histogram! [name value]
  (swap! metrics update-in [:histograms name]
    (fn [hist]
      (let [hist (or hist {:values [] :sum 0 :count 0})
            new-values (conj (:values hist) value)
            new-values (if (> (count new-values) 1000)
                         (subvec new-values (- (count new-values) 1000))
                         new-values)]
        {:values new-values
         :sum (+ (:sum hist) value)
         :count (inc (:count hist))}))))

(defn get-counter [name]
  (get-in @metrics [:counters name] 0))

(defn get-gauge [name]
  (get-in @metrics [:gauges name]))

(defn get-histogram [name]
  (let [hist (get-in @metrics [:histograms name])]
    (when hist
      (let [sorted (sort (:values hist))
            count (:count hist)]
        {:count count
         :sum (:sum hist)
         :mean (when (pos? count) (/ (:sum hist) count))
         :min (first sorted)
         :max (last sorted)
         :p50 (nth sorted (int (* 0.5 count)) nil)
         :p95 (nth sorted (int (* 0.95 count)) nil)
         :p99 (nth sorted (int (* 0.99 count)) nil)}))))

(defn clear-metrics! []
  (reset! metrics {:counters {} :gauges {} :histograms {}}))

(defn snapshot []
  @metrics)

(defn record-workflow-started! []
  (inc-counter! :workflows-started))

(defn record-workflow-completed! []
  (inc-counter! :workflows-completed))

(defn record-workflow-failed! []
  (inc-counter! :workflows-failed))

(defn record-step-execution! [duration-ms]
  (inc-counter! :steps-executed)
  (record-histogram! :step-duration duration-ms))

(defn record-step-retry! []
  (inc-counter! :step-retries))

(defn update-active-executions! [count]
  (set-gauge! :active-executions count))
