(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/workflow-engine.jar")

(defn uberjar [_]
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:src-dirs ["src"]
                    :class-dir class-dir
                    :basis basis})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'workflow-engine.core})))
