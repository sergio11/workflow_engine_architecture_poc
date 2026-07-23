(require 'clojure.test)

(def test-namespaces
  ['workflow-engine.workflow.model-test
   'workflow-engine.workflow.dsl-test
   'workflow-engine.workflow.validator-test
   'workflow-engine.execution.state-machine-test
   'workflow-engine.execution.context-test])

(doseq [ns test-namespaces]
  (require ns))

(let [results (mapv clojure.test/run-tests test-namespaces)
      total-fail (apply + (map :fail results))
      total-error (apply + (map :error results))]
  (println "\n=== TOTAL ===")
  (println "Failures:" total-fail "Errors:" total-error)
  (System/exit (if (and (zero? total-fail) (zero? total-error)) 0 1)))
