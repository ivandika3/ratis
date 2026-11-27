<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Ratis robustness testing

The robustness suite records concurrent public `RaftClient` calls and checks their complete history against a sequential counter model using [Porcupine](https://github.com/anishathalye/porcupine), pinned in `go.mod`. It combines workload generation, cluster disruptions, offline validation and retained reports. All Java helpers are test-only; ordinary Ratis builds do not require Go.

## Run

From the repository root, with Java, Maven and Go 1.20 or newer available:

```bash
dev-support/checks/robustness.sh
```

The command runs the Go adapter tests with the race detector, builds the checker, then runs the Java `robustness-tests` Maven profile. It fails if the executable is missing, a history is invalid, checking is inconclusive, required traffic does not complete, or the harness fails. It preserves evidence under `target/robustness`, including on a failed Maven run.

Choose a workload seed or increase operations per worker:

```bash
dev-support/checks/robustness.sh -Dratis.robustness.seed=42 -Dratis.robustness.operations=100
```

Additional arguments are passed to Maven, including `-s FILE` for a custom Maven settings file. `MAVEN` selects an executable instead of the repository Maven wrapper. `OUTPUT_DIR` selects the report root. Seeds reproduce workload choices; operating-system and network scheduling remain nondeterministic.

To run the Java profile directly after building the checker:

```bash
go -C dev-support/linearizability-checker build -o "$PWD/target/robustness/linearizability-checker" .
mvn -pl ratis-test -am -Probustness-tests test
```

`ratis.robustness.checker` overrides the executable, `ratis.robustness.reports` overrides the histories directory, and `ratis.robustness.checker.timeout.ms` selects a checker budget between 1 ms and 10 minutes (default 10 seconds). The Java adapter defaults the Go subprocess's `GOMEMLIMIT` to a 256 MiB garbage-collector memory target; an existing environment setting takes precedence. This is a soft target, alongside the input and time limits. The profile selects only robustness and adapter tests, also when building upstream modules with `-am`. Normal test profiles do not enable cluster robustness tests. The dedicated CI check runs on branch/tag pushes using the recorded GitHub run ID as the workload seed; it is separate from pull-request tests.

## Coverage

The gRPC mini-cluster suite runs concurrent increments and leader/follower reads across all combinations of `COMMIT_INDEX`, `APPLIED_INDEX`, `REPLIED_INDEX`, and enabled/disabled leader leases. Fault cases cover leader and follower isolation, quorum loss, leader stop/restart, local snapshot recovery, remote snapshot installation after log purge, and membership growth from three to five voters. It requires successful reads and writes while a quorum survives and after recovery. Quorum-loss tests instead require an uncertain/failed write before restoring communication. Mini-cluster `killServer()` is a graceful close.

The process suite uses three child JVMs with independent durable storage and keeps the recorder in the parent. It terminates a leader forcibly, recovers it, then terminates and recovers all servers. It covers log recovery and snapshot recovery, verifying the loaded snapshot index in each recovered child. On Linux/macOS it also suspends a leader with `SIGSTOP`, commits a write on a replacement leader, and reads the resumed former leader with leases enabled and disabled. Cleanup always terminates the child JVMs. The Java sources remain compatible with the project's Java 8 baseline.

The current application model is a counter, initially zero: `increment` returns the new value and `query` returns the current value. Ratis application messages are opaque, so other application contracts need their own model and workload. This suite checks observed finite executions, not every possible execution or every possible user state machine. It does not assert availability during loss of quorum, simulate disk corruption, or implement etcd's application-specific transaction/watch API checks.

## Reports and offline replay

Each run has its own directory with source revision, seed, client identities, call IDs when available, requested/actual peers, read configuration and fault events. The checker saves:

- `history.json`: original observations, also the input for replay.
- `normalized.json`: the actual operation intervals supplied to Porcupine.
- `result.json` and `verdict.txt`: verdict and checking statistics.
- `history.html`: interactive history/partial-linearization visualization.
- `checker-stdout.json` and `checker-stderr.log`: subprocess output.

Process runs also retain server logs and storage directories. A checker process exceeding its checking/reporting allowance is killed and marked inconclusive in `verdict.txt` and `checker-timeout.txt`. A harness failure records `harnessFailure` in history metadata; analysis may not have run, and must not be inferred to have passed.

Recheck a saved history without starting Ratis:

```bash
target/robustness/linearizability-checker \
  -history target/robustness/histories/RUN/history.json \
  -output target/robustness/recheck -timeout 10s
```

Exit codes are `0` valid, `1` invalid, `2` inconclusive, and `3` malformed input/tool/report error. Open the generated `history.html` in a browser. Partial linearizations are diagnostic paths, not a promised smallest counterexample. Java validates the structured result and its agreement with the exit code before accepting it.

## History contract

Version 1 histories contain `version: 1`, `model: "counter"`, `initial: 0`, a nonempty `operations` array, optional string-valued `metadata`, and optional `faults`. Each operation contains:

| Field | Meaning |
| --- | --- |
| `id` | Positive unique logical operation ID. |
| `client` | Nonnegative display/worker identity; defaults to zero if omitted. |
| `clientId`, `callId`, `peer`, `server` | Optional diagnostic identities/routing information. |
| `function` | `increment` or `query`. |
| `call`, `return` | Globally unique observation indices from one recorder; return must follow call. Zero return denotes pending. |
| `outcome` | `ok`, definitely without effect `fail`, or `unknown`. |
| `value` | Required only for `ok`; nonnegative integer result. |
| `error` | Optional diagnostic detail. |

A fault has a unique positive `index`, nonempty `action`, and `detail`. Observation indices order events across all clients and faults; they need not be contiguous. The file is limited to 16 MiB and 10,000 operations. Unknown fields, impossible intervals and duplicate IDs/indices are rejected.

The recorder logs invocation before calling the public API, and completion before exposing the returned future's result to the workload. Internal RPC retries remain one operation. If an application opens a new client or issues another public call after failure, that is a new operation with its own identity. Cancellation is recorded as unknown and cannot be overwritten by a late reply. Malformed successful payloads are harness errors.

A timeout does not establish that a write failed to commit or stopped executing. Normalization extends unknown operations through the observation boundary and the model permits either no effect or one application. The actual timeout event remains in the original history. Do not drop uncertain writes, truncate their execution interval at timeout, or reinterpret checker timeout as success. Do not merge ordinal indices or `System.nanoTime()` values from unrelated recorders/JVMs. Do not partition this single counter by client, peer, term or arbitrary time window.

## Development

```bash
cd dev-support/linearizability-checker
go test -race ./...
```

The adapter corpus includes legal overlaps, stale reads, contradictory read ordering, duplicate increment results, pending/uncertain writes, execution after caller timeout, definitely failed writes, malformed inputs, reporting failures and checker cancellation. Java tests cover completion ordering, pending calls, cancellation with late replies, metadata escaping, malformed successful payloads, tool errors, invalid histories and replay.

The design/research background is in [the integration plan](../linearizability-checker-plan.md). Porcupine is MIT-licensed; the Ratis adapter is Apache-2.0. The runner includes Ratis, Porcupine and Go license notices with its generated checker/report artifacts. Retain those notices when redistributing the binary.
