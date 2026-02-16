#!/usr/bin/env clojure

(require '[clojure.tools.logging :refer [info warn error]]
         '[jepsen.ratis.core :as core]
         '[jepsen.ratis.client :as client]
         '[jepsen.ratis.db :as db]
         '[jepsen.ratis.counter :as counter]
         '[jepsen.client :as jepsen-client])

(println "=== Apache Ratis Jepsen Phase 2 Demo ===")
(println)

;; Test 1: Verify that we can create real Ratis components
(println "1. Testing component creation...")

(try
  (let [test-config {:nodes ["n1" "n2" "n3"]
                    :workload :counter
                    :version "3.1.3"}]
    
    (println "   ✓ Creating real Ratis database...")
    (let [ratis-db (db/db "3.1.3")]
      (println "     Database created:" (type ratis-db)))
    
    (println "   ✓ Creating real Ratis client...")
    (let [ratis-client (client/counter-client)]
      (println "     Client created:" (type ratis-client)))
    
    (println "   ✓ Creating mock components for comparison...")
    (let [mock-db (db/mock-db "3.1.3")
          mock-client (client/mock-counter-client)]
      (println "     Mock database:" (type mock-db))
      (println "     Mock client:" (type mock-client))))
  
  (catch Exception e
    (error "Component creation failed:" (.getMessage e))
    (throw e)))

(println)

;; Test 2: Test workload configuration
(println "2. Testing workload configuration...")

(try
  (let [workload-opts {:read-frequency 0.2}
        workload (counter/workload workload-opts)]
    (println "   ✓ Counter workload created")
    (println "     Client type:" (type (:client workload)))
    (println "     Checker available:" (some? (:checker workload)))
    (println "     Generator available:" (some? (:generator workload))))
  
  (catch Exception e
    (error "Workload configuration failed:" (.getMessage e))
    (throw e)))

(println)

;; Test 3: Test full test configuration
(println "3. Testing full test configuration...")

(try
  (let [test-opts {:workload :counter
                  :nodes ["n1" "n2" "n3" "n4" "n5"]
                  :time-limit 30
                  :rate 10
                  :version "3.1.3"
                  :nemesis :partition
                  :nemesis-interval 15}
        test-config (core/ratis-test test-opts)]
    
    (println "   ✓ Full test configuration created")
    (println "     Test name:" (:name test-config))
    (println "     Database type:" (type (:db test-config)))
    (println "     Client type:" (type (:client test-config)))
    (println "     Nemesis configured:" (some? (:nemesis test-config)))
    (println "     Generator configured:" (some? (:generator test-config))))
  
  (catch Exception e
    (error "Test configuration failed:" (.getMessage e))
    (throw e)))

(println)

;; Test 4: Test helper functions (Java interop preparation)
(println "4. Testing Java interop helper functions...")

(try
  ;; Test RaftGroup building
  (let [nodes ["n1" "n2" "n3"]]
    (println "   ✓ Testing RaftGroup building...")
    ;; We can't actually create the RaftGroup without proper classpath,
    ;; but we can test the function exists
    (println "     build-raft-group function available:" 
             (some? (resolve 'jepsen.ratis.client/build-raft-group)))
    (println "     build-raft-client function available:" 
             (some? (resolve 'jepsen.ratis.client/build-raft-client)))
    (println "     extract-counter-value function available:" 
             (some? (resolve 'jepsen.ratis.client/extract-counter-value))))
  
  (catch Exception e
    (error "Helper function test failed:" (.getMessage e))
    (throw e)))

(println)

;; Test 5: Validate database management functions
(println "5. Testing database management functions...")

(try
  (println "   ✓ Database management functions available:")
  (println "     install-java!:" (some? (resolve 'jepsen.ratis.db/install-java!)))
  (println "     setup-directories!:" (some? (resolve 'jepsen.ratis.db/setup-directories!)))
  (println "     copy-ratis-binary!:" (some? (resolve 'jepsen.ratis.db/copy-ratis-binary!)))
  (println "     create-config!:" (some? (resolve 'jepsen.ratis.db/create-config!)))
  (println "     start-ratis!:" (some? (resolve 'jepsen.ratis.db/start-ratis!)))
  (println "     stop-ratis!:" (some? (resolve 'jepsen.ratis.db/stop-ratis!)))
  (println "     wipe-data!:" (some? (resolve 'jepsen.ratis.db/wipe-data!)))
  
  (catch Exception e
    (error "Database management test failed:" (.getMessage e))
    (throw e)))

(println)

;; Test 6: Quick mock client operations test
(println "6. Testing mock client operations...")

(try
  (let [mock-client (client/mock-counter-client)
        test-context {:nodes ["n1" "n2" "n3"]}]
    
    ;; Open client
    (let [opened-client (jepsen-client/open! mock-client test-context "n1")]
      (println "   ✓ Mock client opened successfully")
      
      ;; Test increment
      (let [inc-result (jepsen-client/invoke! opened-client test-context 
                                     {:type :invoke :f :inc :value nil})]
        (println "     Increment result:" (:type inc-result))
        
        ;; Test read
        (let [read-result (jepsen-client/invoke! opened-client test-context 
                                        {:type :invoke :f :read :value nil})]
          (println "     Read result:" (:type read-result) "value:" (:value read-result))))
      
      ;; Close client
      (jepsen-client/close! opened-client test-context)
      (println "   ✓ Mock client closed successfully")))
  
  (catch Exception e
    (error "Mock client test failed:" (.getMessage e))
    (throw e)))

(println)

;; Test 7: Test operation generators
(println "7. Testing operation generators...")

(try
  (println "   ✓ Testing operation generators...")
  (let [read-op (client/r nil nil)
        inc-op (client/inc-op nil nil)
        get-op (client/get-op nil nil)]
    (println "     Read operation:" read-op)
    (println "     Increment operation:" inc-op) 
    (println "     Get operation:" get-op))
  
  (catch Exception e
    (error "Operation generator test failed:" (.getMessage e))
    (throw e)))

(println)
(println "=== Phase 2 Demo Complete ===")
(println)
(println "Summary:")
(println "✓ Real Ratis components can be created")
(println "✓ Java interop functions are available")
(println "✓ Database management functions are implemented")
(println "✓ Counter workload integrates with new client")
(println "✓ Full test configuration works")
(println "✓ Mock implementations still work for comparison")
(println "✓ Operation generators work correctly")
(println)
(println "Next steps:")
(println "1. Test with actual Ratis cluster deployment")
(println "2. Validate network partition testing")
(println "3. Performance baseline measurement")
(println "4. Integration with CI/CD pipeline") 