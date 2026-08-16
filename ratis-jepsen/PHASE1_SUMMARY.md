# Apache Ratis Jepsen Testing - Phase 1 Summary

## Overview

Phase 1 successfully established a complete Jepsen testing framework foundation for Apache Ratis. This implementation provides a solid base for testing Raft consensus algorithm correctness under various fault conditions, with mock implementations that can be seamlessly replaced with real Ratis integration in Phase 2.

## Architecture Overview

```
ratis-jepsen/
├── src/jepsen/ratis/
│   ├── core.clj      # Main test orchestration and CLI
│   ├── db.clj        # Database lifecycle management (mock)
│   ├── client.clj    # Client operations handling (mock)
│   └── counter.clj   # Counter workload implementation
├── test/jepsen/ratis/
│   └── core_test.clj # Comprehensive unit tests
├── dev/
│   └── user.clj      # Development utilities
├── resources/
│   └── logback.xml   # Logging configuration
├── project.clj       # Leiningen project configuration
├── demo.clj          # Working demonstration script
├── README.md         # User documentation
├── STATUS.md         # Implementation status
└── .gitignore        # Git ignore patterns
```

## Core Components Implemented

### 1. Test Orchestration (`core.clj`)

**Purpose:** Main entry point and test configuration management

**Key Functions:**
- `ratis-test`: Constructs complete test configuration
- `dev-test`: Development-friendly test configuration
- `-main`: Command-line interface entry point

**Configuration Options:**
- `:workload` - Test workload type (currently `:counter`)
- `:nodes` - List of test nodes
- `:time-limit` - Test duration in seconds
- `:rate` - Operations per second
- `:nemesis` - Fault injection type (`:none`, `:partition`, `:partition-one`)
- `:nemesis-interval` - Fault injection frequency
- `:read-frequency` - Ratio of read to write operations

**CLI Usage:**
```bash
lein run test --workload counter --nodes n1,n2,n3,n4,n5 --time-limit 60
```

### 2. Database Management (`db.clj`)

**Purpose:** Manages Ratis server lifecycle across test nodes

**Current Implementation (Mock):**
- `MockRatisDB` record implementing `jepsen.db/DB` protocol
- Simulates setup/teardown with realistic delays
- Provides logging for debugging

**Key Functions:**
- `setup!`: Initialize Ratis server on a node
- `teardown!`: Clean shutdown of Ratis server
- `log-files`: Return paths to server log files

**Phase 2 Integration Points:**
```clojure
;; Replace mock with real implementations:
(defn install-java! [])           ; Install Java on test nodes
(defn setup-directories! [])      ; Create necessary directories
(defn copy-ratis-binary! [])      ; Deploy Ratis JAR files
(defn create-config! [test node]) ; Generate Ratis configuration
(defn start-ratis! [test node])   ; Start actual Ratis server
(defn stop-ratis! [node])         ; Stop Ratis server
```

### 3. Client Operations (`client.clj`)

**Purpose:** Handles interactions with Ratis cluster

**Current Implementation (Mock):**
- `MockRatisClient` with in-memory counter
- Implements `jepsen.client/Client` protocol
- Supports `:inc`, `:read`, and `:get` operations

**Key Functions:**
- `open!`: Establish connection to Ratis cluster
- `invoke!`: Execute operations against the cluster
- `close!`: Clean up client connections

**Phase 2 Integration Points:**
```clojure
;; Replace with real Ratis client:
(defn build-raft-group [nodes])     ; Create RaftGroup configuration
(defn build-raft-client [nodes])    ; Initialize RaftClient
(defn extract-counter-value [reply]) ; Parse Ratis responses
```

**Required Java Interop:**
```clojure
(:import [org.apache.ratis.client RaftClient]
         [org.apache.ratis.conf RaftProperties]
         [org.apache.ratis.protocol RaftGroup RaftGroupId RaftPeer]
         [org.apache.ratis.examples.counter CounterCommand])
```

### 4. Counter Workload (`counter.clj`)

**Purpose:** Implements counter consistency testing

**Operations:**
- `:inc` - Increment counter (write operation)
- `:read` - Read current counter value (read operation)

**Consistency Checking:**
- Monotonic increment verification
- Lost increment detection
- Read consistency validation

**Key Functions:**
- `counter-checker`: Validates operation history
- `workload`: Configures test workload with generators and checkers

**Validation Logic:**
```clojure
;; Checks performed:
1. Counter never decreases between reads
2. Final value >= number of successful increments
3. Final value <= number of attempted increments
4. No impossible state transitions
```

## Test Infrastructure

### Unit Tests (`core_test.clj`)

**Coverage:** 8 tests, 26 assertions, 0 failures

**Test Categories:**
1. **Component Creation Tests**
   - Workload availability
   - Database instantiation
   - Client creation
   - Test configuration

2. **Integration Tests**
   - End-to-end test creation
   - Operation generators
   - Protocol compliance

3. **Utility Tests**
   - Node utilities
   - Operation generators

### Development Tools

**REPL Utilities (`dev/user.clj`):**
- Quick test creation functions
- Interactive development helpers
- Namespace refresh utilities

**Demo Script (`demo.clj`):**
- End-to-end functionality demonstration
- Component testing examples
- Validation of all major features

## Configuration Management

### Project Configuration (`project.clj`)

**Dependencies:**
- `jepsen "0.3.9"` - Core testing framework
- `clojure "1.11.1"` - Language runtime
- `cheshire "5.11.0"` - JSON processing

**Profiles:**
- `:dev` - Development dependencies and source paths
- `:uberjar` - Production build configuration

### Logging (`logback.xml`)

**Log Levels Configured:**
- Jepsen components: INFO/DEBUG
- Ratis components: WARN (reduces noise)
- Third-party libraries: WARN

**Output:**
- Console output with timestamps
- File output to `jepsen.log`

## Mock Implementation Details

### MockRatisClient Behavior

```clojure
;; State management
:counter (atom 0)  ; In-memory counter state

;; Operation handling
:inc  -> (swap! counter inc)           ; Atomic increment
:read -> @counter                      ; Read current value
:get  -> @counter                      ; Alias for read
```

**Error Simulation:**
- Connection failures
- Timeout handling
- Operation-specific errors

### MockRatisDB Behavior

```clojure
;; Lifecycle simulation
setup!    -> (Thread/sleep 1000)  ; Simulate setup time
teardown! -> (Thread/sleep 500)   ; Simulate cleanup time
```

**Logging Integration:**
- Setup/teardown events logged
- Node-specific log messages
- Error conditions handled

## Validation Results

### Test Execution

```bash
$ lein test
Ran 8 tests containing 26 assertions.
0 failures, 0 errors.
```

### Demo Execution

```bash
$ lein run -m clojure.main demo.clj
=== Apache Ratis Jepsen Test Demo ===

1. Creating a test configuration...
Test name: ratis-counter
Nodes: [n1 n2 n3]

2. Testing client operations...
Testing increment operation: {:type :ok, :f :inc, :value nil}
Testing read operation: {:type :ok, :f :read, :value 1}
Testing another increment: {:type :ok, :f :inc, :value nil}
Testing final read: {:type :ok, :f :read, :value 2}

3. Testing database operations...
Setting up database... ✅
Tearing down database... ✅

4. Testing counter checker...
Checker result: {:valid? true, :expected 2, :actual 2, :increments 2, :reads 1}
```

## Phase 2 Integration Roadmap

### 1. Real Ratis Client Integration

**Replace MockRatisClient with:**
```clojure
(defrecord RatisCounterClient [client nodes]
  client/Client
  (open! [this test node]
    (let [raft-client (build-raft-client (:nodes test))]
      (assoc this :client raft-client)))
  
  (invoke! [_ test op]
    (case (:f op)
      :inc (let [reply (.send (.io client) (.getMessage CounterCommand/INCREMENT))]
             (if (.isSuccess reply)
               (assoc op :type :ok)
               (assoc op :type :fail :error (.toString reply))))
      :read (let [reply (.sendReadOnly (.io client) (.getMessage CounterCommand/GET))]
              (if (.isSuccess reply)
                (assoc op :type :ok :value (extract-counter-value reply))
                (assoc op :type :fail :error (.toString reply)))))))
```

### 2. Real Database Management

**Replace MockRatisDB with:**
```clojure
(defrecord RatisDB [version]
  db/DB
  (setup! [_ test node]
    (install-java!)
    (setup-directories!)
    (copy-ratis-binary!)
    (create-config! test node)
    (start-ratis! test node))
  
  (teardown! [_ test node]
    (stop-ratis! node)))
```

### 3. Advanced Workloads

**List Append Workload:**
```clojure
(defn append-workload [opts]
  {:client (append-client)
   :checker (elle/checker)
   :generator (gen/mix [{:type :invoke :f :append :value (rand-int 100)}
                        {:type :invoke :f :read :value nil}])})
```

### 4. Network Partition Testing

**Nemesis Integration:**
```clojure
;; Already configured in core.clj:
:nemesis (case (:nemesis opts)
           :partition     (nemesis/partition-random-halves)
           :partition-one (nemesis/partition-random-node))
```

## Dependencies for Phase 2

### Maven Dependencies

```clojure
;; Add to project.clj dependencies:
[org.apache.ratis/ratis-client "3.1.3"]
[org.apache.ratis/ratis-common "3.1.3"]
[org.apache.ratis/ratis-grpc "3.1.3"]
[org.apache.ratis/ratis-server-api "3.1.3"]
[org.apache.ratis/ratis-proto "3.1.3"]
```

### Built Ratis JAR

```bash
# Required for server deployment:
../ratis-examples/target/ratis-examples-3.1.3.jar
```

## Key Design Decisions

### 1. Mock-First Approach
- Enables rapid development and testing
- Validates Jepsen protocol compliance
- Provides working foundation for real integration

### 2. Modular Architecture
- Clear separation of concerns
- Easy to replace individual components
- Testable in isolation

### 3. Configuration-Driven
- Flexible test parameters
- Easy to extend with new options
- Command-line friendly

### 4. Comprehensive Testing
- Unit tests for all components
- Integration testing via demo
- Protocol compliance validation

## Success Criteria Met

✅ **Framework Foundation:** Complete Jepsen integration  
✅ **Test Infrastructure:** Working unit tests and demo  
✅ **Documentation:** Comprehensive guides and examples  
✅ **Modularity:** Clean component separation  
✅ **Extensibility:** Ready for Phase 2 integration  
✅ **Validation:** All tests pass, demo works  

## Next Phase Priorities

1. **Replace Mock Client** - Integrate real Ratis client with Java interop
2. **Replace Mock Database** - Implement actual server lifecycle management
3. **Add List Append Workload** - More sophisticated consistency testing
4. **Enable Network Partitions** - Test Raft consensus under faults
5. **CI Integration** - Automated testing in GitHub Actions

This Phase 1 implementation provides a robust foundation for comprehensive Jepsen testing of Apache Ratis, with clear pathways for Phase 2 enhancement. 