# Jepsen Tests for Apache Ratis

This module contains [Jepsen](https://jepsen.io/) tests for Apache Ratis, designed to verify the correctness and safety of the Raft consensus implementation under various fault conditions.

## Overview

Jepsen is a framework for testing distributed systems by introducing controlled faults and verifying that the system maintains its consistency guarantees. These tests help ensure that Apache Ratis correctly implements the Raft consensus algorithm and maintains data safety even during network partitions, process crashes, and other failure scenarios.

## Prerequisites

1. **JVM**: Java 11 or higher
2. **Leiningen**: Clojure build tool ([installation guide](https://leiningen.org/))
3. **Test Nodes**: 5 Debian/Ubuntu nodes accessible via SSH
4. **SSH Access**: Passwordless SSH from control node to all test nodes

## Test Environment Setup

### Option 1: Local Testing with Docker (Development)

```bash
# Start local Docker environment for basic testing
docker run -it --name jepsen-control \
  -v $(pwd):/opt/ratis-jepsen \
  -w /opt/ratis-jepsen \
  clojure:openjdk-11-lein bash
```

### Option 2: Multi-node Setup (Recommended)

Set up 5 test nodes (n1, n2, n3, n4, n5) accessible from the control node:

```bash
# Ensure passwordless SSH access
ssh-keygen -t rsa -N ""
for node in n1 n2 n3 n4 n5; do
  ssh-copy-id root@$node
done
```

## Building Apache Ratis

First, build the required Ratis components:

```bash
# From the ratis-community root directory
mvn clean package -DskipTests -pl ratis-examples,ratis-client,ratis-server,ratis-grpc
```

## Running Tests

### Basic Counter Test

```bash
cd ratis-jepsen
lein run test --workload counter --nodes n1,n2,n3,n4,n5 --time-limit 60
```

### List Append Test (Coming in Phase 2)

```bash
lein run test --workload append --nodes n1,n2,n3,n4,n5 --time-limit 300 --nemesis partition
```

## Test Workloads

- **counter**: Simple increment/decrement operations testing basic consistency
- **append**: List append operations with Elle checker for strict serializability (Phase 2)

## Test Results

Test results are stored in `store/` directory with:
- Operation history
- Consistency analysis
- Performance metrics  
- Error logs and debugging information

## Development

### Running Tests in Development Mode

```bash
lein repl
```

```clojure
(require '[jepsen.ratis.core :as ratis])
(ratis/dev-test)
```

### Project Structure

```
ratis-jepsen/
├── src/jepsen/ratis/
│   ├── core.clj      # Main test entry point
│   ├── db.clj        # Database setup/teardown
│   ├── client.clj    # Ratis client implementation
│   └── counter.clj   # Counter workload
└── test/jepsen/ratis/
    └── core_test.clj # Unit tests
```

## Current Status

- ✅ Phase 1: Basic project setup and counter workload
- 🚧 Phase 2: List append workload with Elle checker
- 📋 Phase 3: Advanced fault injection and CI integration

## Contributing

1. Ensure all tests pass before submitting changes
2. Add tests for new workloads or features
3. Follow existing code style and patterns
4. Update documentation for new functionality 