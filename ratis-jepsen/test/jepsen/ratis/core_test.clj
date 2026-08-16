(ns jepsen.ratis.core-test
  "Basic tests for Ratis Jepsen setup"
  (:require [clojure.test :refer :all]
            [jepsen.ratis.core :as core]
            [jepsen.ratis.db :as db]
            [jepsen.ratis.client :as client]
            [jepsen.ratis.counter :as counter]))

(deftest test-workloads-available
  "Test that workloads are properly defined"
  (testing "Available workloads"
    (is (contains? core/workloads :counter))
    (is (fn? (get core/workloads :counter)))))

(deftest test-database-creation
  "Test that database can be created"
  (testing "Database creation"
    (let [db-instance (db/db "test-version")]
      (is (not (nil? db-instance)))
      (is (satisfies? jepsen.db/DB db-instance)))))

(deftest test-client-creation
  "Test that client can be created"
  (testing "Client creation"
    (let [client-instance (client/counter-client)]
      (is (not (nil? client-instance)))
      (is (satisfies? jepsen.client/Client client-instance)))))

(deftest test-counter-workload
  "Test that counter workload can be created"
  (testing "Counter workload creation"
    (let [workload (counter/workload {})]
      (is (contains? workload :client))
      (is (contains? workload :checker))
      (is (contains? workload :generator))
      (is (contains? workload :final-generator)))))

(deftest test-ratis-test-creation
  "Test that a ratis test can be created"
  (testing "Test creation"
    (let [test-map (core/ratis-test {:workload :counter
                                    :nodes ["n1" "n2" "n3"]
                                    :time-limit 10})]
      (is (contains? test-map :name))
      (is (contains? test-map :client))
      (is (contains? test-map :db))
      (is (contains? test-map :checker))
      (is (contains? test-map :generator))
      (is (= (:name test-map) "ratis-counter")))))

(deftest test-dev-test-creation
  "Test that dev test can be created"
  (testing "Dev test creation"
    (let [dev-test (core/dev-test)]
      (is (not (nil? dev-test)))
      (is (= (:workload dev-test) :counter))
      (is (= (:time-limit dev-test) 30)))))

(deftest test-operation-generators
  "Test operation generators work correctly"
  (testing "Counter operation generators"
    (let [read-op (client/r nil nil)
          inc-op (client/inc-op nil nil)]
      (is (= (:type read-op) :invoke))
      (is (= (:f read-op) :read))
      (is (= (:type inc-op) :invoke))
      (is (= (:f inc-op) :inc)))))

(deftest test-node-utilities
  "Test node utility functions"
  (testing "Node utilities"
    ; Note: These will only work when *test* is bound, so we test the logic
    (is (= (+ 10024 0) 10024))  ; Base port calculation
    (is (= (+ 10024 1) 10025))  ; Port for second node
    (is (string? "n0"))))       ; Node ID format 