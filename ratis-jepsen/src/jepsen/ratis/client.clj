(ns jepsen.ratis.client
  "Client implementation for Ratis Jepsen tests"
  (:require [clojure.tools.logging :refer [info warn error debug]]
            [clojure.string :as str]
            [jepsen.client :as client]
            [jepsen.util :refer [timeout]]
            [slingshot.slingshot :refer [try+]]))

(def ^:dynamic *operation-timeout-ms* 5000)

(defrecord MockRatisClient [counter]
  client/Client
  
  (open! [this test node]
    (info "Opening mock Ratis client connection to" node)
    (assoc this :counter (atom 0)))
  
  (setup! [this test]
    (info "Setting up mock client")
    this)
  
  (invoke! [this test op]
    (debug "Invoking operation:" op)
    (if-not counter
      (assoc op :type :fail :error "No client connection")
      (try+
        (case (:f op)
          :read
          (let [value @counter]
            (assoc op :type :ok :value value))
          
          :inc
          (do
            (swap! counter inc)
            (assoc op :type :ok))
          
          :get
          (let [value @counter]
            (assoc op :type :ok :value value))
          
          (assoc op :type :fail :error (str "Unknown operation: " (:f op))))
        
        (catch Exception e
          (let [msg (.getMessage e)]
            (warn "Operation failed:" msg)
            (assoc op :type :fail :error msg))))))
  
  (teardown! [this test]
    (info "Tearing down mock client")
    this)
  
  (close! [_ test]
    (info "Closing mock Ratis client")))

(defn counter-client
  "Create a new mock counter client for testing"
  []
  (MockRatisClient. nil))

;; Operation generators for counter workload
(defn r
  "Generate a read operation"
  [_ _]
  {:type :invoke :f :read :value nil})

(defn inc-op
  "Generate an increment operation"
  [_ _]
  {:type :invoke :f :inc :value nil})

(defn get-op
  "Generate a get operation"
  [_ _]
  {:type :invoke :f :get :value nil}) 