#!/usr/bin/env lein exec

(require '[jepsen.ratis.core :as ratis]
         '[jepsen.ratis.client :as client]
         '[jepsen.ratis.counter :as counter]
         '[jepsen.ratis.db :as db]
         '[clojure.pprint :refer [pprint]])

(println "=== Apache Ratis Jepsen Test Demo ===")
(println)

(println "1. Creating a test configuration...")
(def test-config {:workload :counter
                  :nodes ["n1" "n2" "n3"]
                  :time-limit 10
                  :rate 5
                  :nemesis nil})

(def test-map (ratis/ratis-test test-config))
(println "Test name:" (:name test-map))
(println "Nodes:" (:nodes test-map))
(println)

(println "2. Testing client operations...")
(def client-instance (client/counter-client))
(def opened-client (.open! client-instance {:nodes ["n1"]} "n1"))

(println "Testing increment operation:")
(def inc-result (.invoke! opened-client {} {:type :invoke :f :inc :value nil}))
(pprint inc-result)

(println "Testing read operation:")
(def read-result (.invoke! opened-client {} {:type :invoke :f :read :value nil}))
(pprint read-result)

(println "Testing another increment:")
(def inc-result2 (.invoke! opened-client {} {:type :invoke :f :inc :value nil}))
(pprint inc-result2)

(println "Testing final read:")
(def read-result2 (.invoke! opened-client {} {:type :invoke :f :read :value nil}))
(pprint read-result2)

(println)
(println "3. Testing database operations...")
(def db-instance (db/db "test-version"))
(println "Setting up database...")
(.setup! db-instance {:nodes ["n1"]} "n1")
(println "Tearing down database...")
(.teardown! db-instance {:nodes ["n1"]} "n1")

(println)
(println "4. Testing counter checker...")
(def history [{:type :invoke :f :inc :value nil}
              {:type :ok :f :inc :value nil}
              {:type :invoke :f :inc :value nil}
              {:type :ok :f :inc :value nil}
              {:type :invoke :f :read :value nil}
              {:type :ok :f :read :value 2}])

(def checker-instance (counter/counter-checker))
(def check-result (.check checker-instance {} history {}))
(println "Checker result:")
(pprint check-result)

(println)
(println "=== Demo Complete ===")
(println "The basic Jepsen framework for Apache Ratis is working!")
(println "Next steps:")
(println "- Replace mock client with real Ratis client")
(println "- Replace mock database with real Ratis server management")
(println "- Add more sophisticated workloads")
(println "- Add network partition testing") 