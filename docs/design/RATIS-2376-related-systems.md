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
# RATIS-2376: related systems

Research date: 2026-10-21. This is a comparison of official sources, with implications for Ratis labeled as design inferences. It supplements the [Ratis evaluation](RATIS-2376-timestamp-bounded-stale-reads.md).

## What the APIs promise

| System | Timestamp selection | Scope and behavior |
| --- | --- | --- |
| CockroachDB | `with_min_timestamp(t)` chooses a timestamp at least `t`; `with_max_staleness(d)` expresses the equivalent moving lower bound. | Current stable documentation restricts bounded reads to a single statement reading one row through a covering index. `nearest_only` requests an error when a nearby replica cannot serve the bound. |
| Spanner | `min_read_timestamp` sets an absolute lower bound; `max_staleness` sets a duration relative to server time. | Bounded modes are restricted to single-use read-only transactions. The selected timestamp can be returned to the caller. |
| TiDB | `AS OF TIMESTAMP TIDB_BOUNDED_STALENESS(t1, t2)` selects a suitable timestamp in an interval, preferring fresh data. | Historical data can also be read at an exact timestamp, including through read-only transactions. An exact timestamp and a bounded interval are different requests. |

Sources: [CockroachDB follower reads](https://docs.cockroachlabs.com/docs/stable/follower-reads), [Spanner RPC API](https://docs.cloud.google.com/spanner/docs/reference/rpc/google.spanner.v1#google.spanner.v1.TransactionOptions.ReadOnly), [TiDB AS OF TIMESTAMP](https://docs.pingcap.com/tidb/stable/as-of-timestamp/).

## CockroachDB: closure is a promise about future writes

The supplied [2021 bounded-staleness article](https://www.cockroachlabs.com/blog/bounded-staleness-reads/) introduces an RFC, rather than documenting all current functionality. Its motivation is avoiding the need to guess an exact timestamp that a lagging follower can serve. The [current documentation](https://docs.cockroachlabs.com/docs/stable/follower-reads) establishes the implemented API and restrictions in the table above. The broader multi-range negotiation in the [RFC](https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20210519_bounded_staleness_reads.md) should therefore not be read as unrestricted current SQL support.

The supplied [2023 follower-read explanation](https://www.cockroachlabs.com/blog/follower-reads-stale-data/) describes updates containing a closed timestamp and a log position. A follower can use that timestamp only after applying through that position. Closure also prevents subsequent writes at or below the timestamp; leases preserve that promise across leaseholder changes. Updates travel through Raft commands and a separate transport that advances idle ranges without periodic log writes.

A closed timestamp does not alone eliminate unresolved transaction intents. A resolved timestamp additionally accounts for outstanding locks, enabling a read to select a timestamp below conflicting work. Thus a closed timestamp is stronger than “this is the timestamp on my latest applied write,” while a resolved timestamp addresses another layer of transactional completeness. These distinctions come from the same [implementation explanation](https://www.cockroachlabs.com/blog/follower-reads-stale-data/).

## Spanner: a consistent historical snapshot, with explicit limits

[Timestamp bounds](https://docs.cloud.google.com/spanner/docs/timestamp-bounds) distinguish exact staleness from bounded staleness. Bounded mode chooses a recent timestamp that can be served at a nearby replica. A read observes a consistent transaction snapshot; two separate bounded reads need not pick the same timestamp. The page explicitly warns that bounded reads can still wait for concurrent transactions, despite trying to avoid that wait. This is not a universal guarantee of nonblocking execution.

Historical reads depend on retained versions. The same [timestamp-bound documentation](https://docs.cloud.google.com/spanner/docs/timestamp-bounds) says reads before the available history fail; timestamp expiry can also affect an ongoing read. The documented version-retention default is one hour, configurable up to one week.

[TrueTime and MVCC](https://docs.cloud.google.com/spanner/docs/true-time-external-consistency) give transaction timestamps their ordering meaning and supply immutable versions for snapshot reads. The [read/write implementation description](https://docs.cloud.google.com/spanner/docs/whitepapers/life-of-reads-and-writes) separately explains the replica's need to know it has applied enough data, and how periodically propagated safe timestamps can avoid a leader RPC for old enough reads. Time synchronization and replication completeness solve different problems.

The [RPC contract](https://docs.cloud.google.com/spanner/docs/reference/rpc/google.spanner.v1#google.spanner.v1.TransactionOptions.ReadOnly) is particularly useful for API design: maximum staleness guarantees visibility of writes committed sufficiently long before the read, and server-side timestamp selection tolerates a skewed client clock. An absolute minimum timestamp supports dependencies on an earlier known commit.

## TiDB/TiKV: safe-ts is gated by local application

The supplied [safe-ts troubleshooting document](https://docs.pingcap.com/tidb/stable/troubleshoot-stale-read/) gives a direct Raft-oriented model:

1. The Region leader tracks transaction locks to maintain resolved-ts.
2. It distributes resolved-ts together with the minimum required apply index using CheckLeader RPCs.
3. Each replica promotes that information to its safe-ts only when its own applied index permits it.
4. A read at or below the replica's safe-ts can use local data.

The leader's resolved-ts cannot simply become a follower's safe-ts: the follower might not have applied the relevant entries. Long transactions, delayed information propagation, and slow application all impede progress. A `DataIsNotReady` error reports the encountered safe-ts. These are separate failure causes worth exposing in diagnostics. [Source](https://docs.pingcap.com/tidb/stable/troubleshoot-stale-read/).

TiDB's timestamp source is a [TSO](https://docs.pingcap.com/tidb/stable/tso/) with physical and logical components, rather than arbitrary independently assigned timestamps. Its [historical-read guide](https://docs.pingcap.com/tidb/stable/as-of-timestamp/) also requires NTP for TiDB and PD when translating wall time to historical timestamps. The [usage guide](https://docs.pingcap.com/tidb/stable/stale-read/) describes extra waiting when a requested timestamp is ahead of resolved-ts and the cost of increasing timestamp-advancement frequency: more CPU and inter-node traffic.

## Design inferences for Ratis

These are implications of the mechanisms above, not claims that Ratis already implements them:

- A timestamp bound needs evidence of completeness. Timestamp metadata on individual writes, recent heartbeats, and small apparent replication lag do not independently prove all required writes are visible.
- Propagate a timestamp together with the log position that validates it, and gate serving on state-machine application. Advancing the timestamp through idle periods requires an explicit protocol.
- Define how leadership changes preserve the selected timestamp promise. A closed historical snapshot must not acquire new writes below its declared timestamp. The agreed Ratis current-prefix contract instead preserves committed-prefix evidence; it does not require monotonically increasing physical timestamps across leaders.
- Separate a lower freshness bound on the current applied state from an exact historical snapshot. The latter also requires state-machine version retention and an API to read those versions.
- Specify which clock interprets a duration, when the bound is fixed, and whether the guarantee is relative to read invocation or response. A client-generated absolute timestamp introduces different clock assumptions from a server-evaluated duration.
- Make local failure, waiting for application, and routing to another replica explicit policies. Partitions only allow bounded reads while the replica's certified progress still meets the moving freshness bound.
- One Raft group avoids database-wide timestamp negotiation. Independent groups still need an application-level transaction and snapshot protocol for a shared historical view.
