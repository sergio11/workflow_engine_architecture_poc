(require 'clojure.test)

(def test-namespaces
  ['workflow-engine.persistence.db-test
   'workflow-engine.persistence.workflow-repo-it
   'workflow-engine.persistence.execution-repo-it
   'workflow-engine.execution.engine-test
   'workflow-engine.events.store-it])

(doseq [ns test-namespaces]
  (require ns))

(let [results (mapv clojure.test/run-tests test-namespaces)
      total-fail (apply + (map :fail results))
      total-error (apply + (map :error results))]
  (println "\n=== TOTAL ===")
  (println "Failures:" total-fail "Errors:" total-error)
  (System/exit (if (and (zero? total-fail) (zero? total-error)) 0 1)))
