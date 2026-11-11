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
# RATIS-2376: timestamp bounded stale reads

Design agreed on 2026-10-21. Code reviewed at `5b52180ff0867fbf879832469051eaec2657953c` on branch `RATIS-2376`. This document records the agreed v1 scope, implemented in the working tree on this branch. Performance has not been benchmarked. See the [implementation and test plan](RATIS-2376-implementation-plan.md) and [related-systems research](RATIS-2376-related-systems.md).

## Agreed contract

A client specifies a nonnegative `maxStaleness` for each read and may supply `minIndex`. The selected follower captures request admission time once. A successful read observes a coherent committed prefix incorporating every successful write in this Raft group that completed before admission time minus `maxStaleness`, and includes the prefix through `minIndex` when supplied. It may incorporate newer writes. Incorporation includes later overwrites and deletions; an unchanged value can be old while the read is fresh.

This is a group-wide freshness guarantee checked using evidence held locally on the follower. It is conditional on the configured maximum clock error being valid. It does not mean that the follower measures only its own replication delay, or that it knows the leader's latest state.

The guarantee is anchored at serving-peer admission, not client invocation or receipt of the response. Capture the anchor once for the admitted operation and retain it through local asynchronous work and fallback. Admission is captured in `ReadRequestHandler.boundedStaleReadAsync`, before submission to the Ratis client executor, for both gRPC and Netty. Transport decoding and earlier server routing precede this boundary. A new network attempt is a new admission; internal rescheduling must not silently reset the existing anchor or deadline.

Zero staleness always performs validated linearizable execution. A nonzero bound can use a local fast path; if the follower cannot establish eligibility, it falls back to a validated linearizable read within the original operation deadline. If that cannot complete, the request fails. No successful response may silently weaken the requested consistency.

## Physical timestamps and clock configuration

Use a physical wall-clock timestamp assigned at each entry's original serialized leader append. Store only that timestamp in the log entry. Replication, persistence, replay, and application preserve it exactly. Never substitute receipt or application time. Missing timestamps on old entries mean unknown evidence.

Clock uncertainty `ε` is a Ratis configuration: a maximum absolute error of each participating server clock relative to real time. Administrators ensure the same bound across the group and keep actual clocks within it. No per-entry uncertainty, HLC, clock-service integration, or configuration-consensus protocol is required in v1. The bound must cover the clock used to stamp entries and the clock used to admit reads, including measurement precision and rounding. The configuration key is `raft.server.read.clock.uncertainty`; its default is unset, which disables the local timestamp path while retaining linearizable fallback. Timestamps and wire durations use milliseconds. Requested staleness rounds down and configured uncertainty rounds up to whole milliseconds; administrators must include clock measurement precision in the bound.

A hard guarantee depends on that operational assumption. Ratis cannot detect every clock violation from replicated timestamps. Administrators must disable timestamp-based serving when the bound cannot be maintained; a known invalid clock must not authorize local success. Reducing the configured uncertainty is safe only if the smaller value remains valid for historical timestamps still used as evidence. Merely synchronizing clocks now does not repair an older incorrectly stamped entry.

Physical timestamps need not increase across entries or leader changes. Raft term/index supplies log order. Do not force timestamps upward synthetically or interpret an HLC logical counter as elapsed time. A backward timestamp may reduce local availability without changing correctness.

## Follower workflow

1. The leader assigns an immutable physical timestamp while constructing an entry in the serialized original append path.
2. Raft replicates and commits the entry normally.
3. The follower advances its query-visible applied prefix only when every entry through that position has completed application successfully. An entry's timestamp becomes usable only after that prefix is visible.
4. At request admission, capture wall-clock time `R` and a monotonic operation deadline. For positive `maxStaleness`, check known timestamp `P` from that eligible prefix, configured uncertainty, and the requested index dependency.
5. If both checks pass, query a coherent committed state that includes the checked prefix locally. No leader communication is needed for this read.
6. Otherwise, obtain a validated ReadIndex from the leader, wait on the selected follower until its query-visible prefix covers `max(readIndex, minIndex)`, then execute the query on that follower. Share the remaining deadline across all stages.

The local acceptance predicate is:

```text
known(appliedTimestamp)
AND queryVisibleAppliedIndex >= minIndex
AND requestTime - appliedTimestamp + 2 * clockUncertainty <= maxStaleness
```

Use conservative, overflow-safe arithmetic. Timestamp absence must be distinguished from a valid numeric value. The prefix index and its timestamp evidence must be read consistently. The selected query view must include that prefix even if concurrent application advances state afterward. Snapshot replacement must invalidate or reject work tied to the previous state generation.

For example, if the observed difference is 70 ms and each clock has a maximum error of 5 ms, the conservative age is 80 ms. A 100 ms bound permits local execution; a 75 ms bound triggers fallback. This is an admission-time check, so a later query does not re-anchor its freshness requirement.

### Why the evidence applies to the Raft group

Let real original append time be `a` and real admission time be `r`. With both clocks within `ε`, `r - a <= R - P + 2ε`. Therefore the acceptance predicate establishes `a >= r - maxStaleness`.

If the timestamped entry eventually commits, Raft's committed-prefix safety places every successful write completed before its original append before that entry in the committed log. A coherent query-visible prefix containing that entry consequently incorporates every write completed before the freshness cutoff. A later leader cannot remove that committed prefix. Writes completed more recently may be absent within the permitted staleness window.

This is a correctness argument for the contract, not a model-checked implementation proof. An append timestamp does not establish a closed MVCC timestamp: an old proposal can commit later with its original timestamp. Exact historical reads require stronger rules and version retention.

## State-machine visibility

State machines explicitly opt in. The capability defaults to unsupported and promises that successful completion of an apply future makes the entry visible to queries, and that queries observe a coherent committed prefix under concurrent application. Ratis tracks completion of the entire prefix; the greatest completed index is insufficient when apply futures finish out of order. The default base state machine must not automatically opt every application into this contract.

A configuration or metadata entry with no asynchronous apply future cannot bypass an earlier unfinished transaction. Application exceptions must not be converted into successful visibility evidence. The new read path must not claim support for a state machine whose visibility contract is unknown, even if it can obtain a leader ReadIndex.

The existing [StateMachineUpdater.applyLog](../../ratis-server/src/main/java/org/apache/ratis/server/impl/StateMachineUpdater.java) increments its internal applied index after invoking application, before the future completes, and separately notifies completion for individual entries. Neither value alone proves the required contiguous prefix. [StateMachine](../../ratis-server-api/src/main/java/org/apache/ratis/statemachine/StateMachine.java) permits concurrent queries and asynchronous application; `supportsBoundedStaleReads()` makes the additional requirements explicit. The separate `AppliedLogPrefix` tracks contiguous successful completion for opted-in state machines and limits pending entries using the existing write element limit.

## Linearizable fallback and deadlines

Fallback preserves execution on the selected follower. It obtains a leader ReadIndex with valid leadership evidence, waits for the complete query-visible prefix through `max(readIndex, minIndex)`, and then queries locally. Zero staleness follows this same validated path directly. There is no v1 local-only failure policy or separate wait-for-new-timestamp policy.

Do not simply call the ordinary read handler and assume linearizability. [ReadRequestHandler](../../ratis-server/src/main/java/org/apache/ratis/server/impl/ReadRequestHandler.java) permits a weaker branch under `Read.Option.DEFAULT`. [LeaderStateImpl.getReadIndex](../../ratis-server/src/main/java/org/apache/ratis/server/impl/LeaderStateImpl.java) and [ReadIndexHeartbeats](../../ratis-server/src/main/java/org/apache/ratis/server/impl/ReadIndexHeartbeats.java) also have configuration, readiness, lease, and cached-acknowledgement paths that require auditing for this contract. A previously acknowledged index alone cannot prove a new operation's leadership validation. The implemented `getFreshReadIndex` samples the actual committed index and uses an independent quorum listener registered before new heartbeats are triggered. It bypasses leases, cached acknowledgements, and disabled-check shortcuts. The leader retains this commit-index floor for subsequent AppendEntries, including under `REPLIED_INDEX`, so followers can acknowledge it. Completion requires the leader to remain running with the captured configuration; a singleton shortcut is allowed only when the leader itself forms a quorum.

Use one deadline for the admitted operation, including queueing, leader communication, apply waiting, and query completion. Use monotonic time for elapsed-time budgets and wall time only for the freshness predicate. An expired or cancelled request must not later return success. Do not cancel a shared apply waiter on behalf of just one read. The budget is `raft.server.read.timeout` (default 10 seconds), capped by a shorter positive request `timeoutMs`. An independent completion timer covers executor queueing, RPC, application waiting, and query completion. Underlying RPC or query work may finish later, but it cannot return success for the expired operation. Each read owns its application waiter.

## Recovery, idle groups, and compatibility

On restart or snapshot installation, timestamp freshness starts unknown. Use linearizable fallback until a timestamped committed entry and its preceding prefix become query-visible. Replayed entries retain their original timestamps and may qualify. No snapshot format extension is required. During ordinary operation, log purging or taking a snapshot need not discard valid in-memory timestamp evidence for the still-visible prefix. Late callbacks from an earlier state generation cannot restore invalidated evidence. Public `pause()` invalidates the prefix as well. Public `resume()` does not establish an updater replay baseline, so bounded reads remain unavailable after that pair until snapshot reload or restart restores a valid baseline. This conservative limitation applies to both the local path and its fallback.

An idle group's last timestamp ages even if its follower has all committed data. V1 accepts that such reads may mostly use linearizable fallback. Periodic committed timestamp markers and timestamp/index side channels are deferred. Receiving an ordinary heartbeat from a purported leader is not sufficient evidence to advance freshness: a partitioned old leader may still communicate with minority followers.

Use a distinct wire request type. Adding optional bounds only to the existing stale-read type would allow old servers to ignore them and silently serve an unbounded stale read. The Java API exposes `sendStaleRead(message, minIndex, maxStaleness, server)` in both blocking and asynchronous APIs; existing index-only semantics remain unchanged. Update type factories, protobuf conversion, dispatch, diagnostics, and metrics together.

Unsupported peers must reject the new request without silently downgrading it. Old log entries without timestamps simply supply no new timestamp evidence. A new serving follower sends `requireFresh` on ReadIndex and accepts the result only when the reply both affirms support with `fresh` and reports success. Old leaders leave `fresh` false and are rejected. Netty now carries asynchronous ReadIndex requests and dispatches bounded reads asynchronously, allowing heartbeats to continue while a read waits. A focused handler test covers an old-style reply; a real mixed-version deployment has not been tested.

## V1 exclusions

- HLC and per-entry clock uncertainty.
- Periodic idle markers, independent timestamp/index side channels, and cached time-certified read barriers.
- Historical `AS OF` reads, MVCC retention, or a cross-group snapshot protocol.
- Automatic monotonic reads and read-result snapshot tokens. Caller-supplied `minIndex` remains supported, including an index returned by a successful write. The current reply `logIndex` is not a promised read snapshot token.
- Automatic clock synchronization, uncertainty discovery, or enforcement that all administrators configured the same value.

## Related systems and the supplied OM design

[RATIS-2376](https://issues.apache.org/jira/browse/RATIS-2376) motivates duration bounds because index lag does not translate into predictable time lag. The issue was Open with no comments when retrieved through its public API. Its motivating Ozone issue is [HDDS-13954](https://issues.apache.org/jira/browse/HDDS-13954).

The [related-systems note](RATIS-2376-related-systems.md) reviews all four supplied links and supporting primary sources. CockroachDB closed/resolved timestamps, Spanner timestamp bounds with TrueTime and MVCC, and TiDB/TiKV resolved-ts and safe-ts all distinguish timestamp evidence from local application progress. Their historical snapshot protocols are stronger than the current-prefix contract chosen here. Ratis can use a simpler argument for completed writes within one ordered group.

The supplied *Consistent Read from OM Followers.pdf* is source material, not implementation instructions. Approach 3 on pages 22–29 supports the timestamp-first direction, with these qualifications:

- **Page 23:** correct the inequality from `requestTimestamp <= appliedLogTimestamp - staleBound` to `requestTimestamp - appliedLogTimestamp + 2ε <= staleBound`. Specify original leader append time. Zero duration requires validated linearizable execution, not merely contacting a server that believes it is leader.
- **Pages 24–25:** `max(currentTimestamp, leaderTimestamp)` cannot authorize a read without valid completeness evidence and the required applied prefix. A committed marker must wait for all earlier application work. Both idle mechanisms are deferred from v1.
- **Pages 26–29:** HLC ordering does not establish local query visibility or bound physical clock error. A full resolved-ts protocol is unnecessary for the agreed completed-write contract; exact historical transaction snapshots would need stronger guarantees.
- **OM deletion retention:** retaining blocks for exactly the maximum admission-time staleness does not cover delayed query execution and subsequent block fetches. OM must account for those lifetimes or provide retention/pinning separately.

Periodic certified read barriers remain a possible future alternative: a validated barrier index paired with the original local invocation time could avoid cross-machine wall-clock comparison. Renewing a cached index without new validation cannot refresh that evidence. This alternative is outside the agreed v1 implementation.

## Acceptance

Every successful read must meet both the completed-write cutoff and the requested index dependency using the state actually visible to its query. The [implementation plan](RATIS-2376-implementation-plan.md) maps this invariant to protocol, application, recovery, fallback, compatibility, and deadline tests. Performance benefits remain to be measured against existing linearizable follower reads under busy and idle workloads.
