(ns jepsen.ratis.client
  "Client implementation for Ratis Jepsen tests"
  (:require [clojure.tools.logging :refer [info warn error debug]]
            [clojure.string :as str]
            [jepsen.client :as client]
            [jepsen.util :refer [timeout]]
            [slingshot.slingshot :refer [try+]])
  (:import [org.apache.ratis.client RaftClient RaftClientConfigKeys]
           [org.apache.ratis.conf RaftProperties]
           [org.apache.ratis.protocol RaftGroup RaftGroupId RaftPeer RaftPeerId]
           [org.apache.ratis.examples.counter CounterCommand]
           [org.apache.ratis.thirdparty.com.google.protobuf ByteString]
           [java.net InetSocketAddress]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def ^:dynamic *operation-timeout-ms* 5000)

;; Helper functions for Ratis client integration

(defn build-raft-group
  "Build a RaftGroup from a list of node names"
  [nodes]
  (let [group-id (RaftGroupId/valueOf (UUID/fromString "02511d47-d67c-49a3-9011-abb3109a44c1"))
        peers (map (fn [node]
                    (-> (RaftPeer/newBuilder)
                        (.setId (RaftPeerId/valueOf (str node)))
                        (.setAddress (InetSocketAddress. (str node) 6000))
                        (.build))) 
                  nodes)]
    (RaftGroup/valueOf group-id (into-array RaftPeer peers))))

(defn build-raft-client
  "Build a RaftClient for the given nodes"
  [nodes]
  (let [properties (RaftProperties.)
        group (build-raft-group nodes)]
    ;; Use default properties for now - specific configurations can be added later
    (-> (RaftClient/newBuilder)
        (.setProperties properties)
        (.setRaftGroup group)
        (.build))))

(defn extract-counter-value
  "Extract counter value from Ratis reply"
  [reply]
  (when (.isSuccess reply)
    (let [message (.getMessage reply)]
      (when message
        (Long/parseLong (.toStringUtf8 message))))))

(defrecord RatisCounterClient [client nodes]
  client/Client
  
  (open! [this test node]
    (info "Opening Ratis client connection to cluster with nodes:" (:nodes test))
    (try+
      (let [raft-client (build-raft-client (:nodes test))]
        (info "Successfully created Ratis client")
        (assoc this :client raft-client :nodes (:nodes test)))
      (catch Exception e
        (error "Failed to create Ratis client:" (.getMessage e))
        (throw e))))
  
  (setup! [this test]
    (info "Setting up Ratis client")
    this)
  
  (invoke! [this test op]
    (debug "Invoking operation:" op)
    (if-not client
      (assoc op :type :fail :error "No client connection")
      (try+
        (case (:f op)
          :read
          (let [reply (.sendReadOnly (.async client) 
                                    (.getMessage CounterCommand/GET))]
            (try
              (let [response (.get reply *operation-timeout-ms* TimeUnit/MILLISECONDS)]
                (if (.isSuccess response)
                  (let [value (extract-counter-value response)]
                    (assoc op :type :ok :value (or value 0)))
                  (assoc op :type :fail :error (.toString response))))
              (catch java.util.concurrent.TimeoutException _
                (assoc op :type :info :error "timeout"))
              (catch Exception e
                (assoc op :type :fail :error (.getMessage e)))))
          
          :inc
          (let [reply (.send (.async client)
                            (.getMessage CounterCommand/INCREMENT))]
            (try
              (let [response (.get reply *operation-timeout-ms* TimeUnit/MILLISECONDS)]
                (if (.isSuccess response)
                  (assoc op :type :ok)
                  (assoc op :type :fail :error (.toString response))))
              (catch java.util.concurrent.TimeoutException _
                (assoc op :type :info :error "timeout"))
              (catch Exception e
                (assoc op :type :fail :error (.getMessage e)))))
          
          :get
          (let [reply (.sendReadOnly (.async client)
                                    (.getMessage CounterCommand/GET))]
            (try
              (let [response (.get reply *operation-timeout-ms* TimeUnit/MILLISECONDS)]
                (if (.isSuccess response)
                  (let [value (extract-counter-value response)]
                    (assoc op :type :ok :value (or value 0)))
                  (assoc op :type :fail :error (.toString response))))
              (catch java.util.concurrent.TimeoutException _
                (assoc op :type :info :error "timeout"))
              (catch Exception e
                (assoc op :type :fail :error (.getMessage e)))))
          
          (assoc op :type :fail :error (str "Unknown operation: " (:f op))))
        
        (catch Exception e
          (let [msg (.getMessage e)]
            (warn "Operation failed:" msg)
            (assoc op :type :fail :error msg))))))
  
  (teardown! [this test]
    (info "Tearing down Ratis client")
    this)
  
  (close! [this test]
    (info "Closing Ratis client")
    (when client
      (try
        (.close client)
        (catch Exception e
          (warn "Error closing client:" (.getMessage e)))))))

;; Keep MockRatisClient for backward compatibility during testing
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
  "Create a new Ratis counter client for testing"
  []
  (RatisCounterClient. nil nil))

(defn mock-counter-client
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