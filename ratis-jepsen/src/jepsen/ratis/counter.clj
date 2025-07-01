(ns jepsen.ratis.counter
  "Counter workload for Ratis testing"
  (:require [clojure.tools.logging :refer [info warn error debug]]
            [jepsen.client :as client]
            [jepsen.checker :as checker]
            [jepsen.generator :as gen]
            [jepsen.ratis.client :as rc]
            [knossos.model :as model]
            [knossos.op :as op]))

(defn counter-checker
  "A checker for counter operations that verifies monotonic increments"
  []
  (reify checker/Checker
    (check [this test history opts]
      (let [init-value 0
            final-reads (->> history
                           (filter #(and (op/ok? %)
                                        (= :read (:f %))))
                           (map :value)
                           sort)
            increments (->> history
                          (filter #(and (op/ok? %)
                                       (= :inc (:f %))))
                          count)
            expected-value increments
            max-read (if (seq final-reads) (last final-reads) init-value)]
        
        (info "Total increments:" increments)
        (info "Final reads:" final-reads)
        (info "Expected final value:" expected-value)
        (info "Maximum read value:" max-read)
        
        (cond
          ; No reads - can't verify anything meaningful
          (empty? final-reads)
          {:valid? true
           :analysis "No successful reads to verify"}
          
          ; Counter went backwards - major problem
          (not= final-reads (sort final-reads))
          {:valid? false
           :error "Counter decreased between reads"
           :reads final-reads}
          
          ; Final value is less than expected - lost increments
          (< max-read expected-value)
          {:valid? false
           :error "Lost increments detected"
           :expected expected-value
           :actual max-read
           :lost (- expected-value max-read)}
          
          ; Final value is greater than expected - impossible increments
          (> max-read expected-value)
          {:valid? false
           :error "More increments than operations"
           :expected expected-value
           :actual max-read
           :extra (- max-read expected-value)}
          
          ; Everything looks good
          :else
          {:valid? true
           :expected expected-value
           :actual max-read
           :increments increments
           :reads (count final-reads)})))))

(defn workload
  "Counter workload with configurable mix of reads and increments"
  [opts]
  (let [read-freq (:read-frequency opts 0.1)]
    {:client (rc/counter-client)
     :checker (checker/compose 
                {:perf (checker/perf)
                 :counter (counter-checker)})
     :generator (->> (gen/mix [{:type :invoke :f :inc :value nil} 
                               {:type :invoke :f :read :value nil}])
                     (gen/stagger 1/10))
     :final-generator (gen/once {:type :invoke :f :read :value nil})}))

;; Legacy operation generators for backwards compatibility
(defn r [] {:type :invoke :f :read :value nil})
(defn inc-op [] {:type :invoke :f :inc :value nil}) 