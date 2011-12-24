(ns clojure-analyzer.unreachable
  (:require [clojure-analyzer.compiler :as analyzer]))

(def imports (atom {}))

(defn find-maps-with-entry 
  "Recursively search a seq for maps with the map entry"
  ([ds k v] (find-maps-with-entry ds k v []))
  ([ds k v hits]
   (cond
     (nil? ds) hits
     (not (or (seq? ds)
              (vector? ds)
              (map? ds))) nil
     (or (seq? ds)
         (vector? ds)) (when (seq ds)
                         (concat hits
                                 (find-maps-with-entry (first ds) k v)
                                 (find-maps-with-entry (rest ds) k v)))
     (map? ds) (when (seq ds)
                 (if (= [k v] (find ds k))
                   [ds]
                   (concat hits
                           (find-maps-with-entry (vals (dissoc ds :env)) k v)))))))

(defn find-unreachable-clauses [ast]
  (doseq [{then :then else :else test-expr :test form :form} (find-maps-with-entry ast :op :if)]
    (if (and (= (:op test-expr) :constant))
      (cond
        (and (not form)
             (not= nil (:form then))) (println "Warning: unreachable then clause")
        (and form
             (not= nil (:form else))) (println "Warning: unreachable else clause" form)
        :else 
        (do
          (when-not (nil? (:form then))
            (find-unreachable-clauses then))
          (when-not (nil? (:form else))
            (find-unreachable-clauses else))))
      (find-unreachable-clauses test-expr))))

(do
  (find-unreachable-clauses (analyzer/analyze-namespace 'clojure.set))

  (find-unreachable-clauses (analyzer/analyze {:ns {:name 'myns}} 
                                              '(cond 
                                                 :else 2)))

  (find-unreachable-clauses (analyzer/analyze {:ns {:name 'myns}} 
                                              '(cond 
                                                 (fn-call 1 2 3) 1
                                                 :else 2
                                                 :another-else 3)))

  (find-unreachable-clauses (analyzer/analyze {:ns {:name 'myns}} 
                                              '(cond 
                                                 :constant 2
                                                 (fn-call 1 2 3) 1
                                                 :else 2
                                                 :another-else 3))))