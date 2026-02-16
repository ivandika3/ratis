(ns jepsen.ratis.core
  "Main test orchestration for Ratis Jepsen tests"
  (:require [clojure.tools.logging :refer [info warn error]]
            [clojure.string :as str]
            [jepsen.core :as jepsen]
            [jepsen.tests :as tests]
            [jepsen.os.debian :as debian]
            [jepsen.cli :as cli]
            [jepsen.checker :as checker]
            [jepsen.nemesis :as nemesis]
            [jepsen.generator :as gen]
            [jepsen.util :as util]
            [jepsen.ratis.db :as db]
            [jepsen.ratis.counter :as counter]))

(def workloads
  "Available workloads for testing"
  {:counter counter/workload})

(defn ratis-test
  "Construct a Ratis test map"
  [opts]
  (let [workload-fn (get workloads (:workload opts))
        _ (when-not workload-fn
            (throw (IllegalArgumentException. 
                     (str "Unknown workload: " (:workload opts)
                          ". Available: " (keys workloads)))))
        workload (workload-fn opts)
        
        ; Simple nemesis - just network partitions for now
        nemesis (case (:nemesis opts)
                  nil            nemesis/noop
                  :none          nemesis/noop
                  :partition     (nemesis/partition-random-halves)
                  :partition-one (nemesis/partition-random-node)
                  (throw (IllegalArgumentException. 
                           (str "Unknown nemesis: " (:nemesis opts)))))]
    
    (merge tests/noop-test
           opts
           {:name      (str "ratis-" (name (:workload opts)))
            :os        debian/os
            :db        (db/db (:version opts "3.1.0-SNAPSHOT"))
            :client    (:client workload)
            :nemesis   nemesis
            :checker   (:checker workload)
            :generator (gen/phases
                         ; Main test phase
                         (->> (:generator workload)
                              (gen/stagger (/ (:rate opts 10)))
                              (gen/nemesis 
                                (if (= nemesis nemesis/noop)
                                  nil  ; No nemesis operations
                                  (cycle [(gen/sleep (:nemesis-interval opts 10))
                                         {:type :info :f :start}
                                         (gen/sleep (:nemesis-interval opts 10))
                                         {:type :info :f :stop}])))
                              (gen/time-limit (:time-limit opts 60)))
                         
                         ; Recovery phase
                         (when-not (= nemesis nemesis/noop)
                           (gen/log "Healing cluster")
                           (gen/nemesis (gen/once {:type :info :f :stop}))
                           (gen/log "Waiting for recovery")
                           (gen/sleep 10))
                         
                         ; Final read
                         (when (:final-generator workload)
                           (gen/log "Final consistency check")
                           (gen/clients (:final-generator workload))))})))

(def cli-opts
  "Command line options for Ratis tests"
  [["-w" "--workload NAME" "Workload to run"
    :default :counter
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]
   
   ["-v" "--version STRING" "Ratis version to test"
    :default "3.1.0-SNAPSHOT"]
   
   ["-r" "--rate NUM" "Approximate request rate, in hz"
    :default 10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be positive"]]
   
   ["-t" "--time-limit NUM" "How long to run the test, in seconds"
    :default 60
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be positive"]]
   
   [nil "--nemesis NAME" "Nemesis to use"
    :default nil
    :parse-fn keyword
    :validate [#{nil :none :partition :partition-one} 
               "Must be none, partition, or partition-one"]]
   
   [nil "--nemesis-interval NUM" "Nemesis operation interval in seconds"
    :default 10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be positive"]]
   
   [nil "--read-frequency NUM" "Frequency of read operations (0.0-1.0)"
    :default 0.1
    :parse-fn read-string
    :validate [#(and (number? %) (<= 0 % 1)) "Must be between 0 and 1"]]])

(defn dev-test
  "A test for local development and debugging"
  []
  (ratis-test {:workload :counter
               :nodes ["n1" "n2" "n3"]  ; Smaller cluster for dev
               :time-limit 30
               :rate 5
               :nemesis nil}))

(defn -main
  "Command line entry point"
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn ratis-test
                                        :opt-spec cli-opts})
                   (cli/serve-cmd))
            args)) 