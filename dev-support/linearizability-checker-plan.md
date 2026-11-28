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

# Introducing a linearizability checker in Ratis

Research date: 27 November 2026. Local source baseline: `f21e15b020e4800f3e192c0cbc19be56d6f2e896` (`3.3.0-SNAPSHOT`). This document records the original proposed design. Implementation and operating instructions are now in [linearizability-checker/README.md](linearizability-checker/README.md); consult current test output for verification status. External links describe the sources inspected on this date; implementation should pin dependency versions and source revisions. No checker was benchmarked or executed against Ratis during this investigation.

## Recommendation

Start with **Porcupine as an offline checker**, driven by histories collected around the existing Java client APIs in `ratis-test`. Keep the checker in a dedicated, opt-in test job initially. Use the existing counter workload and mini-cluster before adding more state machines or external cluster orchestration.

If keeping every test executable within Java/Maven is a firm requirement, evaluate **adapting OpenSearch's Java checker** instead. **Knossos** is the other serious generic history checker, particularly useful for Jepsen interoperability or independently checking a small corpus. The choice should follow a small common-history experiment, rather than upstream performance claims.

These are engineering recommendations based on the capabilities and integration costs below. A checker validates a recorded finite execution against its model; passing histories do not prove all executions correct. Ratis supplies arbitrary application state machines, so there is no single application-independent model that can interpret every `Message` payload. [Ratis overview](../README.md)

## Available open source options

| Candidate | Runtime / license | Capabilities and integration | Assessment for Ratis |
| --- | --- | --- | --- |
| [Porcupine](https://github.com/anishathalye/porcupine) | Go / MIT | Pure executable sequential model; timestamped operations or ordered events; optional partitioning; nondeterministic models; timed verdicts and HTML visualization. A small Ratis-owned command must wrap the library. | Preferred initial backend if a separate Go build in the checker job is acceptable. |
| [Knossos](https://github.com/jepsen-io/knossos) | Clojure on JVM / EPL-1.0 | Generic sequential models; EDN invocation/completion histories; explicit uncertain outcomes; bounded analysis and SVG diagnostics. Embed Clojure or use a subprocess. | Credible alternative. JVM execution does not make it a dependency-free Java integration. |
| [OpenSearch LinearizabilityChecker](https://github.com/opensearch-project/OpenSearch/blob/main/test/framework/src/main/java/org/opensearch/cluster/coordination/LinearizabilityChecker.java) | Java / Apache-2.0 | Invocation/response histories, sequential model, optional keyed partitioning, text diagnostics. Existing dependencies include OpenSearch utilities, Lucene and Log4j. | Strongest Java-only source-adaptation candidate found; Ratis would maintain the adapted checker and its validation. |
| [JetBrains Lincheck](https://github.com/JetBrains/lincheck) | JVM / MPL-2.0 | Generates and executes concurrent JVM scenarios with stress and controlled-interleaving modes. Its data-structure testing checks linearizability. | Useful for isolated concurrent Ratis components. Not the first choice for importing arbitrary distributed client histories. |
| [Elle](https://github.com/jepsen-io/elle) | Clojure on JVM / EPL-2.0 option | Transactional anomaly detection using supported observable operations and dependency relationships. | Revisit for transactional application workloads. Not a generic replacement for a sequential-specification checker. |

Porcupine's [model API](https://github.com/anishathalye/porcupine/blob/master/model.go), [checker API](https://github.com/anishathalye/porcupine/blob/master/porcupine.go), and [license](https://github.com/anishathalye/porcupine/blob/v1.3.1/LICENSE.md) support the first row. Knossos's [history contract](https://github.com/jepsen-io/knossos/blob/master/README.md), [competition analyzer](https://github.com/jepsen-io/knossos/blob/master/src/knossos/competition.clj), and [build declaration](https://github.com/jepsen-io/knossos/blob/master/project.clj) support the second. The other rows link directly to their source or project documentation. Lincheck is not assumed to expose a supported arbitrary-history importer merely because it runs on the JVM. The older [Devexperts project](https://github.com/devexperts/lin-check) points to its successor and is not a separate preferred option.

Maintenance evidence is reasonably recent: the [Porcupine package page](https://pkg.go.dev/github.com/anishathalye/porcupine) exposes v1.3.1 published on 21 September 2026, [Clojars](https://clojars.org/knossos) lists Knossos 0.3.16 published on 17 July 2026, and [Lincheck releases](https://github.com/JetBrains/lincheck/releases) lists 3.7. These observations are not a guarantee that each is the newest available release; verify and pin the selected version. OpenSearch's checker is present in its current source tree, but a separately maintained standalone checker artifact was not established.

The [C++ research implementation of P-compositionality](https://github.com/ahorn/linearizability-checker) is a useful algorithm and history-corpus reference. It offers no Java build advantage over Porcupine, and suitability as a maintained dependency was not established. Other ports and research tools are outside the initial shortlist.

Jepsen is a broader workload, fault, collection, and analysis framework. Its [linearizable checker uses Knossos](https://github.com/jepsen-io/jepsen/blob/main/doc/tutorial/04-checker.md); adopting Knossos does not require adopting the full Jepsen harness. Full Jepsen integration can follow when testing real process deployments becomes the objective.

For an existing Raft-based testing precedent, etcd separates [traffic, faults, history collection and validation](https://github.com/etcd-io/etcd/blob/main/tests/robustness/README.md), depends on [Porcupine in its test module](https://github.com/etcd-io/etcd/blob/main/tests/go.mod), and explicitly models [uncertain persistence](https://github.com/etcd-io/etcd/blob/main/tests/robustness/model/non_deterministic.go). Reuse the separation of responsibilities; Ratis does not need etcd's application-specific transaction model.

MIT and Apache-2.0 are ASF Category A licenses. EPL-1.0 and MPL-2.0 have Category B distribution conditions. This favors the Porcupine and OpenSearch paths for packaging simplicity, without ruling out the JVM alternatives. Check the actual dependency graph and what is included in source/binary distributions, retain required notices, and do not assume Maven test scope alone answers distribution questions. [ASF third-party license policy](https://www.apache.org/legal/resolved.html)

## Existing Ratis integration points

| Existing source | What can be reused / boundary |
| --- | --- |
| [LinearizableReadTests](../ratis-server/src/test/java/org/apache/ratis/LinearizableReadTests.java) | Three-node cluster setup, linearizable-read configuration, leader/follower reads, slow operations and targeted assertions. Existing tests remain valuable; add whole-history validation alongside them. |
| [ReadOnlyRequestTests.CounterStateMachine](../ratis-server/src/test/java/org/apache/ratis/ReadOnlyRequestTests.java) | Test-only `increment` and `query`, with numeric responses and initial state zero. Suitable for the first no-restart workload. It does not implement snapshot persistence/loading. |
| [gRPC test variants](../ratis-test/src/test/java/org/apache/ratis/grpc/TestLinearizableReadWithGrpc.java) | Concrete transport binding and existing variants for commit/applied/replied read indices and leader leases. Start with gRPC and expand configurations deliberately. |
| [MiniRaftCluster](../ratis-server/src/test/java/org/apache/ratis/server/impl/MiniRaftCluster.java), [RaftTestUtil](../ratis-server/src/test/java/org/apache/ratis/RaftTestUtil.java) | Leader changes, request blocking/delays, server stop/restart and configuration changes. `killServer()` calls `close()`: it is not an abrupt process crash. Fault support must be checked for the selected transport. |
| [AsyncApi](../ratis-client/src/main/java/org/apache/ratis/client/api/AsyncApi.java), [BlockingApi](../ratis-client/src/main/java/org/apache/ratis/client/api/BlockingApi.java) | Record invocation immediately before the public call and completion at the client-observed response. Both APIs can be covered without production instrumentation. |
| [OrderedAsync](../ratis-client/src/main/java/org/apache/ratis/client/impl/OrderedAsync.java), [BlockingImpl](../ratis-client/src/main/java/org/apache/ratis/client/impl/BlockingImpl.java) | A call ID is allocated before retrying. One logical public call should remain one modeled operation across internal attempts. |
| [Read configuration](../ratis-server-api/src/main/java/org/apache/ratis/server/RaftServerConfigKeys.java) | Explicitly enable `Read.Option.LINEARIZABLE`; the default directly queries the state machine and may undermine linearizability. Read index types are `COMMIT_INDEX`, `APPLIED_INDEX`, `REPLIED_INDEX`; leader leases are separately configurable. |
| [Example CounterStateMachine](../ratis-examples/src/main/java/org/apache/ratis/examples/counter/server/CounterStateMachine.java) | Existing snapshot save/load and restart-oriented behavior to evaluate for later persistence tests. Its binary response encoding differs from the test counter's string encoding. |
| [ratis-test dependencies](../ratis-test/pom.xml), [CI](../.github/workflows/ci.yaml), [unit runner](checks/unit.sh) | The integration module already consumes transport/server test jars. CI splits test profiles and compiles on Java 8 as well as newer JDKs; avoid introducing a newer Java requirement into ordinary tests. |

The checked source tree contains targeted linearizable-read assertions but no references to Porcupine, Knossos or Lincheck, and no general recorded-history checker was found. This is a finding about this checkout, not every external Ratis test project.

## Proposed integration

```text
JUnit workload + existing MiniRaftCluster fault controls
    -> recorder around RaftClient public calls
    -> versioned history file + run metadata
    -> standalone checker command + counter model
    -> valid / invalid / inconclusive + diagnostics
    -> JUnit assertion and retained CI artifacts
```

Keep the first recorder and workload under `ratis-test/src/test/java/org/apache/ratis/`. Put a small Go module and command under a proposed `dev-support/linearizability-checker/` directory, with the Porcupine version pinned. These are proposed paths, not existing components. Avoid a new production module, JNI bridge or changes to client/server APIs for the first integration.

Use one command invocation per captured history, not one subprocess per operation. Supply the checker executable through an explicit test property. A dedicated Maven profile/CI job should build it once and run the designated JUnit tests. When that profile is enabled, a missing executable or malformed result must fail the job clearly. Ordinary builds should not silently claim to have run a checker that was unavailable.

The command should consume a small versioned JSON history and emit a structured verdict, checker/model version and diagnostics paths. Retain histories and reports beneath a proposed `ratis-test/target/linearizability/` directory and explicitly upload them on failure or inconclusive results; existing CI archiving needs to be checked when wiring the job. Include a command to recheck an existing file without rerunning Ratis. Keep histories portable, without initially building a generic plugin framework for many checker engines.

### Initial sequential model and workload

Use the existing test counter with state `n = 0`:

| Operation | Legal response | Next state |
| --- | --- | --- |
| `increment` | Exactly `n + 1` | `n + 1` |
| `query` | Exactly `n` | `n` |

The checker must find a single legal order that also respects real-time precedence: if A completes before B is invoked, A comes first. An increment returning 1 followed by a newly invoked query returning 0 is invalid. A query overlapping that increment can legally return 0 or 1, depending on placement in the order. The sequential-specification contract and operation intervals are defined in [Porcupine's model source](https://github.com/anishathalye/porcupine/blob/master/model.go).

A concrete first experiment is a three-node gRPC cluster, three or four independent client workers, and roughly 100 mixed operations followed by final reads. Start with one outstanding operation per worker; multiple workers still provide concurrency. These are proposed starting bounds to calibrate, not measured capacities. Assert nonempty write/read coverage and keep availability/progress checks separate from the safety verdict, so a run in which everything times out cannot count as successful coverage.

Begin with `COMMIT_INDEX`, lease disabled, reads directed both to the leader and followers. Add all three read-index modes and lease variants after recorder/model validation. Later add pipelined asynchronous requests and a register workload with unique write values or CAS to exercise noncommutative transitions. A counter does not cover every possible application-state-machine error.

### History correctness requirements

1. **Record the public operation boundary.** Include local queueing, redirects and internal retries in the same interval. For async calls, attach recording to the completion path of the future exposed to the workload, before allowing the worker's next dependent operation. Sampling `isDone()` later or joining futures in submission order can erase real-time relationships.
2. **Use one observation clock initially.** All mini-cluster clients run in one JVM. Use `System.nanoTime()` relative to one run origin and/or recorder event sequence numbers. Preserve publication order; a concurrent queue's insertion order is not automatically the order in which event numbers were allocated. Do not merge `nanoTime()` values from different JVMs. Preserve integer precision when serializing to the Go tool.
3. **Separate identities.** Record a unique history operation ID, worker ID, Ratis client ID, and call ID when available from existing APIs, plus the requested peer and actual responder when available. Simultaneously outstanding async calls need distinct operation identities even if they share one RaftClient. Do not add production accessors just to expose metadata; the recorder's IDs suffice for pairing.
4. **Preserve raw outcomes.** Store input, decoded output, invocation/completion events, success/error details, and classification as successful, definitely without effect, or uncertain. Also retain fault events, seed, peer/group configuration, read-index/lease settings, retry policy and source revision. Server terms/log indices are diagnostic evidence, not the order the checker must accept.
5. **Treat timeout/cancellation as uncertainty unless no effect is established.** A failed transport call can have committed. Do not drop such writes or model every exception as no effect. Retain their possible execution through the observation boundary, because execution may occur after the caller times out. Keep the actual timeout event as diagnostic data. Bound retries according to the intended Ratis client contract; do not assume indefinite exactly-once execution beyond supported retry-cache behavior.
6. **Adapt uncertain histories explicitly.** Porcupine expects paired events/operations. One possible adapter extends an unresolved operation to a synthetic end after all observed events and uses `NondeterministicModel.ToModel()` to allow no effect or one application. This is a proposed encoding that must pass the corpus below, not built-in timeout semantics. Never use the caller timeout as a fictitious latest execution time. Drain known completions before finalizing; do not record two completions for one operation.
7. **Respect checker-specific assumptions.** Knossos uses one outstanding operation per logical process and distinguishes `:fail` from `:info`. Give overlapping calls separate process identities and rotate identity after an uncertain timeout. Preserve actual observed precedence when converting formats. [Knossos history contract](https://github.com/jepsen-io/knossos/blob/master/README.md)
8. **Validate inputs and verdicts.** Reject duplicate IDs, missing required fields, impossible intervals, invalid pairing and malformed output as harness errors. Expose valid, invalid and inconclusive distinctly; a checker time budget exhausted is not a pass or evidence of a Ratis violation. Porcupine's timed APIs provide this distinction. OpenSearch's current early-termination callback returns `false`, so an adaptation must track cancellation separately from a genuine invalid verdict. [Porcupine API](https://github.com/anishathalye/porcupine/blob/master/porcupine.go), [OpenSearch source](https://github.com/opensearch-project/OpenSearch/blob/main/test/framework/src/main/java/org/opensearch/cluster/coordination/LinearizabilityChecker.java)

Do not check intentionally stale or explicitly non-linearizable reads against this model. Do not divide a single counter's history by client, peer, time window or Raft term: doing so can lose the relationships under test. Partitioning is appropriate only for genuinely independent objects, with cross-object operations handled by a model that preserves their dependencies. Preserve complete histories for reanalysis rather than truncating them to fit the checker budget.

### Diagnostics and resource limits

Save the original history before analysis, the normalized checker input, seed/configuration, checker version, verdict, elapsed check time, stdout/stderr and an HTML visualization when available. Label maximal partial linearizations as diagnostic output; do not promise a smallest counterexample. A seed reproduces workload choices, but does not reproduce JVM/network scheduling. Rechecking a saved history reproduces the checker input.

Bound worker count, total operations, unresolved writes, checker time and process memory. Start with short histories and increase only after measurement. Ambiguous operations increase search costs; many independent long-lived pending writes can overwhelm a checker. Unknown results should remain visible and fail a designated gating job as inconclusive, with artifacts for investigation. Do not repeatedly rerun until a valid verdict appears. [Porcupine checker implementation](https://github.com/anishathalye/porcupine/blob/master/checker.go)

## Incremental delivery plan

| Step | Proposed work | Completion evidence |
| --- | --- | --- |
| 1. Validate selection | Define the history schema and corpus; write the minimal Porcupine adapter. Compare a small corpus with Knossos or the Java candidate before committing to an engine. | Expected verdicts agree for supported semantics; malformed input and checker cancellation are distinguishable; runtime/memory measured on representative histories. |
| 2. Add first Ratis coverage | Add the Java recorder and counter workload in `ratis-test`, using gRPC, three nodes and explicit linearizable reads. Add the optional checker test profile and offline recheck command. | Real no-fault histories pass; deliberately corrupted outputs fail; no production dependency/API change; failure artifacts are usable. |
| 3. Exercise read paths and failures | Cover commit/applied/replied indices, lease modes, follower reads, controlled leader isolation/election, delayed application and delayed replies. Add async pipelining after recorder ordering tests. | Concurrent histories validate under each supported configuration, with explicit progress/coverage checks and bounded execution. Existing targeted read tests continue to pass. |
| 4. Add persistence and reconfiguration | Reuse or adapt an existing persistence-capable state machine for graceful restart, snapshot creation/installation, catch-up and membership changes. Preserve one history across transitions. | Acknowledged state survives the tested recovery paths; snapshots and reconfiguration do not reset the model or lose history. |
| 5. Expand environment and CI | Add larger seeded jobs, more transports, then a process-based harness for abrupt crashes/pauses or external deployments if desired. | Resource budgets and actionable failures justify promoting a small bounded subset to presubmit CI. Abrupt-crash claims come from actual process termination, not `close()`. |

The in-memory test counter is sufficient for initial concurrency/read tests, but snapshot/restart tests need a recovery contract and a state machine that implements it. Evaluate the existing example counter first, or an existing test state machine with the required semantics, before creating another. Do not add production constructors or methods solely for tests. [Test counter](../ratis-server/src/test/java/org/apache/ratis/ReadOnlyRequestTests.java), [example counter](../ratis-examples/src/main/java/org/apache/ratis/examples/counter/server/CounterStateMachine.java)

## Selection corpus and decision criteria

Before trusting results against Ratis, validate these small hand-understandable histories:

- Sequential increment/read and legal overlapping operations: valid.
- Query returning the old value after an increment has already completed: invalid.
- Completed increments returning a duplicate counter value or an impossible jump without other possible writes: invalid.
- Two reads that require contradictory placements of overlapping writes: invalid, even when each individual read looks plausible.
- A timed-out increment observed by a later successful query: potentially valid; the write must remain available to the model.
- A timed-out increment that never takes effect: potentially valid; uncertainty must not force an application.
- A timed-out increment that takes effect after the timeout event: potentially valid, demonstrating why its interval cannot end at timeout.
- One logical operation with multiple internal RPC retries: recorded once; a separately invoked application retry is a separate operation unless explicitly deduplicated by the API contract.
- An initial state mismatch, malformed history, missing executable or broken checker output: harness error.
- A reliably exercised checker cancellation/deadline: inconclusive, never valid or a reported Ratis consistency bug. Do not assume an arbitrarily tiny time budget always causes cancellation.

Use the exact same model semantics and original precedence constraints for any cross-check. A Java adaptation also needs tests for state equality/caching, unmatched invocations and cancellation. Known good and bad upstream history corpora can supplement these cases with their licenses retained.

Record selection measurements: integration/build requirements, history length and concurrency, unresolved operation count, checker duration, peak memory, cancellation behavior and usefulness of diagnostics. Prefer Porcupine if the separate tool remains cheap and diagnostics are useful. Prefer the Java adaptation if Java-only execution is more valuable than avoiding ownership of checker code. Knossos remains attractive when its uncertainty semantics and Jepsen format fit the longer-term testing direction. These decisions do not require a production Ratis dependency on any checker.
