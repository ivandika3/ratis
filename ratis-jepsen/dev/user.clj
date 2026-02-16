(ns user
  "Development utilities for Ratis Jepsen tests"
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [jepsen.ratis.core :as ratis]
            [jepsen.ratis.counter :as counter]
            [jepsen.ratis.client :as client]
            [jepsen.ratis.db :as db]))

(defn dev-test
  "Create a basic test for development"
  []
  (ratis/ratis-test {:workload :counter
                     :nodes ["localhost"]  ; Single node for dev
                     :time-limit 10
                     :rate 2
                     :nemesis nil}))

(defn simple-counter-test
  "Create a simple counter test"
  []
  {:name "dev-counter"
   :client (client/counter-client)
   :nodes ["localhost"]
   :time-limit 5})

(comment
  ;; Development workflow
  
  ;; Refresh the namespace after changes
  (refresh)
  
  ;; Create a test
  (def test (dev-test))
  
  ;; Examine test structure
  (pprint (keys test))
  
  ;; Look at the workload
  (pprint (:generator test))
  
  ;; Create a simple client (without running servers)
  (def c (client/counter-client))
  
  ;; Create operation examples
  (client/r nil nil)
  (client/inc-op nil nil)
  
  ) 