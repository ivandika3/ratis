<!---
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements. See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# RATIS-2376: implementation and validation

Implemented on branch `RATIS-2376` against `5b52180ff0867fbf879832469051eaec2657953c`, 2026-10-21. The [design](RATIS-2376-timestamp-bounded-stale-reads.md) defines the contract; the [related-systems note](RATIS-2376-related-systems.md) explains its relationship to timestamp-based database reads.

## API and operation

Both blocking and asynchronous clients expose:

```java
client.io().sendStaleRead(message, minIndex, maxStaleness, followerId);
client.async().sendStaleRead(message, minIndex, maxStaleness, followerId);
```

`minIndex` may be zero when no index dependency is required. `maxStaleness` is a nonnegative `TimeDuration`, rounded down to milliseconds; zero requires linearizable execution. The existing index-only API retains its behavior. A distinct protobuf request kind prevents old serving peers from interpreting the duration-bearing request as an unbounded stale read.

The application must explicitly implement `StateMachine.supportsBoundedStaleReads()` and honor its successful-apply visibility and coherent-query contract. Administrators configure `RaftServerConfigKeys.Read.setClockUncertainty(properties, uncertainty)` consistently across the group, including clock measurement precision. The key `raft.server.read.clock.uncertainty` is unset by default. Without it, the new API uses linearizable fallback; it still requires state-machine opt-in.

One budget begins before the Ratis client executor queue: `raft.server.read.timeout` (default 10 seconds), capped by a shorter positive request timeout. It covers leader communication, apply waiting, and query completion. Wall time is used only for the admission-time freshness predicate; elapsed time is monotonic.

## Implementation

| Area | Implementation |
| --- | --- |
| Protocol and clients | Optional `appendTimestampMs` on log entries; distinct bounded stale request carrying duration and index; both client API overloads; serialization and request classification |
| Original append | `TransactionContextImpl` stamps its retained authoritative entry; `RaftLogBase` stamps configuration and metadata entries. Replication and replay preserve original timestamps. Custom transaction contexts must stamp their original initialization or leave the field absent. |
| Query visibility | `AppliedLogPrefix` advances only through contiguous successful application, handles synchronous metadata behind pending transactions, and associates timestamp evidence with that prefix. Pending application is limited by the existing write element limit. |
| Local check | Known timestamp, sufficient index, configured uncertainty, and overflow-safe `R - P + 2ε <= maxStaleness`. Requested duration rounds down; uncertainty rounds up. |
| Fallback | `getFreshReadIndex` samples the committed index and independently validates a quorum, bypassing ordinary read options, leases, and cached acknowledgements. The selected follower waits through `max(readIndex, minIndex)` and queries locally. |
| Quorum handling | Listener registered before heartbeat triggering; independent call-ID thresholds per read; appender replacement resets its acknowledgement tracking; captured configuration and current leadership checked on completion. A singleton shortcut requires the leader itself to form a quorum. |
| Commit propagation | A fresh-read commit-index floor is retained for the leader term and advertised in AppendEntries, including under `REPLIED_INDEX`, so required metadata can commit on followers. |
| Compatibility | ReadIndex sends `requireFresh`; followers require both affirmative `fresh` support and a successful reply. An older leader's reply is rejected. |
| Netty | Asynchronous ReadIndex transport and bounded-read dispatch avoid blocking heartbeat processing; unique transport call IDs, RPC timeouts, outstanding-request cleanup, and proxy recovery support retries. |
| Recovery | Generation checks reject old callbacks and queries across state replacement. Restart/snapshot reload begins with unknown timestamp evidence, while replay can restore original evidence. Ordinary snapshot creation and log purge preserve in-memory evidence. |
| Metrics | Existing request timers/failure classification recognize the new kind. `boundedStaleReadLocalCount` and `boundedStaleReadFallbackCount` count path selections, including reads that may subsequently fail. |

Public `pause()` invalidates the query-visible prefix. Public `resume()` does not establish an updater replay baseline, so both local bounded reads and their fallback remain unavailable until snapshot reload or restart. This is a conservative availability limitation; a paused/resumed state cannot reuse earlier freshness evidence.

## Executed verification

The final focused reactor run passed **38 tests**: 10 server tests and 28 integration/client/configuration/log tests. Coverage includes:

- Timestamp arithmetic boundaries, overflow, absent versus epoch-zero timestamps, backward timestamps, replay evidence, failed application, and generation invalidation.
- Actual updater behavior with metadata behind an unfinished asynchronous transaction.
- New quorum acknowledgements for successive reads at the same index, singleton replacement quorum rules, and safe rejection of an old-style ReadIndex reply.
- Executor queue time, index waiting and delayed query completion under the original request budget.
- Configured and unconfigured uncertainty, explicit state-machine opt-in, and all three ReadIndex modes with trailing committed metadata.
- Isolated-follower local reads and failure of zero-bound reads without a quorum, over both gRPC and Netty.
- Public pause/resume invalidation and Netty reconnection after a closed proxy.
- Client serialization, explicit uncertainty configuration, log persistence of mixed timestamped/legacy entries, existing gRPC read tests, and an existing Netty stale-read regression.

The `REPLIED_INDEX` metadata case and Netty reconnect case reproduced their respective failures before the implementation fixes and passed afterward. The new updater test initially lacked a snapshot-handler mock; correcting that fixture allowed the visibility assertion to execute successfully.

Command:

```sh
mvn -o -pl ratis-test -am test \
  -Dtest=TestBoundedStaleReadWithGrpc,TestBoundedStaleReadWithNetty,TestClientProtoUtils#testBoundedStaleReadRoundTrip,TestRaftLogReadWrite#testReadWriteLog,TestBoundedReadRequestHandler,TestAppliedLogPrefix,TestFreshReadIndexHeartbeats,TestStateMachineUpdater,TestRaftServerConfigKeys#testClockUncertaintyRequiresExplicitConfiguration,TestRaftConfiguration#testSingletonReplacementRequiresRemoteReadAcknowledgement,TestReadOnlyRequestWithGrpc,TestRaftAsyncWithNetty#testStaleReadAsync \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip -Drat.skip=true \
  -Ddevelocity.cache.local.enabled=false -Ddevelocity.cache.remote.enabled=false
```

Checkstyle passed for `ratis-common`, `ratis-client`, `ratis-server-api`, `ratis-server`, and `ratis-netty`. An independent read-only review covered the production changes and untracked files, including quorum validation, state replacement, and Netty recovery; all reported correctness findings were addressed.

## Validation limits

This is focused validation, not a complete repository test run or a distributed correctness proof. A real mixed-version deployment and end-to-end snapshot-install/restart matrix have not been exercised; the focused tests cover old-style reply rejection, generation/replay handling, pause/resume, and timestamp persistence. HLC, periodic idle markers, MVCC/historical reads, and automatic monotonic-read tokens remain outside v1.

Performance benchmarks have not been run. Before production adoption, measure local/fallback rates and latency under busy and idle groups, different network delays, apply delays, bounds, and uncertainty. Include timestamp storage overhead, completion-tracking memory, and write throughput. Idle groups can legitimately use fallback frequently because v1 emits no periodic timestamp markers.
