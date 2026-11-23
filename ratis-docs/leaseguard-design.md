# LeaseGuard implementation comparison and Ratis design

Research date: 2026-10-21. This is a source review and proposed design, not an implementation or a completed correctness audit.

Follow-up: [Public adoption survey, 2026-10-27](leaseguard-adoption-survey.md), including additional implementations, unmerged attempts, and the public ScyllaDB activation PR.

## Recommendation

Implement an optional LeaseGuard mode in stages. Start with local timers and bounded drift, including the commit gate, current-term lease reads, renewal, and recovery rules. Add deferred commit writes by revising write admission separately from read readiness. Treat bounded-uncertainty clocks and inherited reads as a later extension with an explicit state-machine contract.

Correction to the earlier discussion: bounded-uncertainty wall clocks are required for inherited reads, but core LeaseGuard and deferred commits can use local timers with bounded drift. The paper explicitly describes this alternative in [Section 5.3](https://arxiv.org/html/2512.15659v2#S5.SS3). Ratis' `System.nanoTime()` provides a local time source, but does not by itself specify the drift and suspend behavior needed for that alternative.

## Sources inspected

| Source | Revision and scope | What is actually present |
| --- | --- | --- |
| Ratis | `5b52180ff`, branch `LeaseGuard` | Existing optional heartbeat-based read lease and ReadIndex. |
| Local `logcabin-leaseguard` | Working branch `master`, `ee6c55a`; actual prototype inspected through cached `origin/leaseguard`, `c4813ab2893d34ab3874ef7b47f7cf897cf6338b` | Full interval-clock prototype, deferred commits, inherited reads, snapshots, and experiments. |
| Local `hashicorp-raft` | `main`, `a5b469295ddc9924541477eafe34bf59a8571063`; inspected available branches/history, including `origin/liveness-on-leader-disk-stall` | No LeaseGuard implementation found. Quorum-contact step-down timer, `VerifyLeader`, and `Barrier`. |
| Local `scylladb/raft` | `master`, `5e0d3eb88f8e6b7a88623b8e26816258c68a2c66`; scoped `raft/` working-tree status clean | Interval-clock core LeaseGuard, deferred commits, current-term lease read barriers, demand-driven renewal, and elapsed-time fallback. No inherited reads. |
| [RaftLeaderLeases/Python](https://github.com/muratdem/RaftLeaderLeases/tree/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python) | `c773c123bdbbf9aeb18031feddd6b291317faf5e` | Deterministic simulation, including interval clocks, deferred commits, inherited reads, and linearizability checks. |

External local repositories were read without switching branches or modifying files. LogCabin references below are pinned remote links because the working files on its `master` branch do not contain the reviewed implementation. HashiCorp conclusions cover locally available refs, not unseen remote branches. Paper reference: [LeaseGuard v2](https://arxiv.org/html/2512.15659v2).

## LogCabin prototype

The useful reference is [`origin/leaseguard`](https://github.com/mongodb-labs/logcabin/tree/c4813ab2893d34ab3874ef7b47f7cf897cf6338b), whose README describes it as a prototype.

- Entries carry `local_time_earliest` and `local_time_latest`, distinct from the existing logical `cluster_time`. `setLocalTime` stamps the originating entry. See [Raft.proto](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Protocol/Raft.proto#L128) and [RaftConsensus.cc:1182](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L1182).
- `TimeBounds::localNow()` calls AWS ClockBound, checks synchronized status, and terminates on clock errors. This is an actual external clock-bound dependency, not a fixed uncertainty added to ordinary system time. See [Time.cc:303](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Core/Time.cc#L303).
- `leaseGuardTimeBounds()` derives the prior lease's expiration from the last previous-term entry's upper timestamp and derives the read expiration from the committed entry's lower timestamp. It falls back to snapshot timestamps when the entry has been compacted. See [RaftConsensus.cc:3341](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L3341).
- `advanceCommitIndex()` checks prior-lease expiration before its ordinary quorum/current-term checks. A separate timed thread retries commit advancement when time passes, even without a new acknowledgement. See [commit gate:2327](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L2327) and [retry thread:2152](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L2152).
- Deferred commit permits appending and replication while the gate remains closed. Success remains tied to commitment; changing acknowledgement timing alone would not be sufficient. See [replicate2:1669](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L1669) and [replicateEntry2:2999](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L2999).
- The renewal thread appends a timestamped no-op approximately every `Delta/2`. This creates replicated storage work, unlike renewing a heartbeat lease. See [RaftConsensus.cc:2188](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L2188).
- Reads are checked in the state machine under its mutex against the last applied entry's timestamp and term. Inherited reads additionally check affected tree paths. Consensus supplies the limbo entries on election. See [StateMachine.cc:125](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/StateMachine.cc#L125), [setLimboRegion:394](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/StateMachine.cc#L394), and [becomeLeader:2705](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L2705).
- Snapshot completion retains the boundary entry's time bounds, so log purging does not erase lease evidence. See [RaftConsensus.cc:1930](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L1930).

### Prototype details requiring further review

These are static observations in the pinned prototype, not reproduced end-to-end failures:

1. The election-time limbo loop uses `i < log->getLastLogIndex()` before appending the new-term no-op. It excludes the final inherited entry even though the limbo region must include that entry. A sole uncertain write at the tail therefore does not reach this collection. See [RaftConsensus.cc:2730](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/RaftConsensus.cc#L2730).
2. `setLimboRegion` calls `limboPaths.empty()` rather than `clear()`. Existing paths are retained; this can conservatively reject additional reads across elections. See [StateMachine.cc:398](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/StateMachine.cc#L398).
3. Limbo filtering uses exact path membership. Directory listings, ancestor deletion, range operations, and conditional reads need a richer dependency relation than exact-key equality. See [query:135](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/StateMachine.cc#L135) and [setLimboRegion:410](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Server/StateMachine.cc#L410).

These observations favor porting the protocol rules and writing Ratis-specific tests, rather than translating the prototype verbatim.

## HashiCorp Raft

HashiCorp's lease serves a different purpose. Its leadership-transfer comment explicitly distinguishes its timer from a lease used to authorize read-only queries. `checkLeaderLease()` counts recently contacted voters and steps down without a quorum. See [raft.go:1016](/Users/ivan.andika/Documents/Storage/hashicorp-raft/raft.go:1016) and [raft.go:1037](/Users/ivan.andika/Documents/Storage/hashicorp-raft/raft.go:1037).

`VerifyLeader()` requests heartbeat-based quorum verification. `Barrier()` submits a replicated barrier that waits behind preceding FSM work. These are useful comparison points for quorum verification and application ordering, but neither implements LeaseGuard's read predicate. See [api.go:902](/Users/ivan.andika/Documents/Storage/hashicorp-raft/api.go:902) and [api.go:928](/Users/ivan.andika/Documents/Storage/hashicorp-raft/api.go:928).

The commitment calculation uses replicated positions and a current-term start index, without checking prior-entry age. `AppendedAt` is explicitly documented as operational information that must not be used for protocol coordination. See [commitment.go:88](/Users/ivan.andika/Documents/Storage/hashicorp-raft/commitment.go:88) and [log.go:96](/Users/ivan.andika/Documents/Storage/hashicorp-raft/log.go:96).

The available `origin/liveness-on-leader-disk-stall` branch suppresses heartbeats following replication stalls. That addresses failure detection rather than implementing log-derived read leases or deferred commitment. Its branch-specific code was inspected with `git show`; it is not the checked-out `replication.go`.

## Python simulation

The simulator is the easiest reference for scheduling and test scenarios, but omits production storage and transport concerns.

- `Write.ts` holds the originating interval; `ClockBase.is_past` and `is_future_or_equal` implement the asymmetric clock comparisons. See [lease_guard.py:42](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard.py#L42) and [clock helpers:172](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard.py#L172).
- `update_secondary_position()` can record majority replication without advancing commitment; `commit_index_updater()` later retries based on elapsed time. See [lease_guard.py:532](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard.py#L532) and [updater:404](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard.py#L404).
- Inherited reads compare the last committed write for a key with the last prior-term write for that key and wait if they differ. This works with its particular append-only keyed workload. See [read:651](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard.py#L651).
- Tests cover stale leaders, lagging commit knowledge, limbo keys, and successive elections. These scenarios are more valuable to reuse than the simulator's implementation details. See [lease_guard_test.py](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard_test.py).

There are meaningful abstraction differences. `_update_commit_index()` explicitly permits a secondary's commit position to exceed its replicated log, following MongoDB rather than Ratis. `_primary_update_commit_index()` uses a median over simulated positions rather than Ratis' flush-aware, configuration-aware calculation. See [lease_guard.py:517](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard.py#L517).

There is also a mode-specific limitation: when `inherit_lease_enabled` is false, `has_lease(for_writes=False)` only waits for prior-term expiration and does not check current committed-entry freshness. Copying that branch would allow a steady leader's read authorization to outlive its committed-entry lease. The inherited-read branch does check freshness. See [lease_guard.py:575](https://github.com/muratdem/RaftLeaderLeases/blob/c773c123bdbbf9aeb18031feddd6b291317faf5e/Python/lease_guard.py#L575). This was established by source inspection, not a runtime reproduction.

## ScyllaDB Raft

The inspected local checkout implements core LeaseGuard and deferred commits directly in its Raft finite state machine. Of the sources reviewed, it is the closest structural reference for Ratis: it accelerates the existing read barrier, preserves the application barrier, and does not require the application to understand limbo entries. These observations apply to this local revision; they do not establish release or deployment status.

### Protocol and integration

- The opt-in server configuration supplies a lease duration and a `bounded_clock` reference, passed into the FSM. See [server.hh:83](/Users/ivan.andika/Documents/Storage/scylladb/raft/server.hh:83) and [server.cc:569](/Users/ivan.andika/Documents/Storage/scylladb/raft/server.cc:569).
- `bounded_clock.hh` separates serialized physical intervals from process-local elapsed time. The physical clock has fixed signed 64-bit nanosecond representation, preventing toolchain-dependent wire units. `interval_now()` may return no usable reading; the elapsed clock must use an NTP-undisciplined source such as `CLOCK_MONOTONIC_RAW`. See [bounded_clock.hh:18](/Users/ivan.andika/Documents/Storage/scylladb/raft/bounded_clock.hh:18) and [bounded_clock.hh:118](/Users/ivan.andika/Documents/Storage/scylladb/raft/bounded_clock.hh:118).
- Entries carry optional `lease_time`, stamped when the leader appends them. The configuration appended when leaving joint consensus is stamped too. See [raft.hh:237](/Users/ivan.andika/Documents/Storage/scylladb/raft/raft.hh:237), [fsm.cc:200](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:200), and [fsm.cc:629](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:629).
- On election, the FSM captures the inherited tail and clock anchors before appending its own dummy entry. `maybe_commit()` retains its existing quorum and current-term rules, then gates commitment on prior-lease expiration. `tick_leader()` retries without requiring new follower messages. See [fsm.cc:279](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:279), [fsm.cc:573](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:573), and [fsm.cc:891](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:891).
- Ordinary entry admission appends after acquiring a memory permit; it does not require the new-term dummy entry to have committed. This permits replication during the deferred-commit period. Application and completion still use the existing wait paths. See [server.cc:885](/Users/ivan.andika/Documents/Storage/scylladb/raft/server.cc:885).
- `start_read_barrier()` first requires `term(commitIndex) == currentTerm`. A fresh interval on the committed entry then resolves the existing read ID locally and suppresses the quorum broadcast. Expired or unavailable clock evidence takes the ordinary quorum path. The server still calls `wait_for_apply(read_idx)`. See [fsm.cc:1708](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:1708) and [server.cc:2077](/Users/ivan.andika/Documents/Storage/scylladb/raft/server.cc:2077).

The current-term check is decisive: this implementation does not provide inherited reads. A newly elected leader with only prior-term committed state waits rather than authorizing query-specific limbo reads. Its generic read-barrier interface therefore needs no application dependency API.

### Activation and storage

This is implemented library support, not enabled database behavior in the inspected checkout. Neither the group0 constructor nor the strong-consistency tablet-group constructor sets the optional LeaseGuard configuration. No concrete production bounded-clock backend was found; the reference to `service::bounded_clock_adjtimex` in a comment does not correspond to an implemented provider here. The identified enabled callers use simulated clocks. See [raft_group0.cc:237](/Users/ivan.andika/Documents/Storage/scylladb/service/raft/raft_group0.cc:237), [groups_manager.cc:241](/Users/ivan.andika/Documents/Storage/scylladb/service/strong_consistency/groups_manager.cc:241), and [randomized_nemesis_test.cc:1875](/Users/ivan.andika/Documents/Storage/scylladb/test/raft/randomized_nemesis_test.cc:1875).

The shared log-entry IDL carries optional lease timestamps with a `2026.3` version annotation. Commitlog storage writes full entries, while system-table storage writes the entry body separately from term/index and reconstructs entries without lease timestamps. Missing timestamps are therefore an intentional recovery case. See [raft_storage.idl.hh:44](/Users/ivan.andika/Documents/Storage/scylladb/idl/raft_storage.idl.hh:44), [raft_commitlog.cc:46](/Users/ivan.andika/Documents/Storage/scylladb/service/strong_consistency/raft_commitlog.cc:46), and [raft_sys_table_storage.cc:104](/Users/ivan.andika/Documents/Storage/scylladb/service/raft/raft_sys_table_storage.cc:104).

Wire compatibility does not by itself provide safe cluster-wide activation. No LeaseGuard-specific mixed-mode or mixed-duration activation gate was found. Every potential leader still needs compatible commit-deferral behavior before any node can safely enable local lease reads.

### Clock failure and snapshots

`prev_term_lease_expired()` first permits commitment after `Delta + Delta/8` of elapsed time since election. This fallback relies on the stated rate assumption for the elapsed clock; the 12.5% margin is this implementation's choice, not an intrinsic LeaseGuard constant. Otherwise it uses the inherited tail's interval, or an election-time interval when the entry is missing or unstamped. See [fsm.cc:681](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:681).

This makes a clock outage degrade local read latency without indefinitely blocking writes: local lease reads require valid interval evidence, but deferred commitment can eventually proceed using elapsed time. The code does not manufacture a zero-width wall-clock interval when synchronization is lost.

Snapshot descriptors do not retain the lease interval. When the prior tail has been folded into a snapshot, the new leader conservatively waits from election. A committed entry at or below the snapshot boundary cannot authorize a lease read; a later stamped no-op can restore that optimization. See [fsm.cc:725](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:725) and [fsm.cc:741](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:741). This is a concrete alternative to extending every Ratis snapshot format with clock metadata.

Clock health is also reported as an advisory `clock_ok` bit. Following persistent local clock loss, the leader can transfer to a fully caught-up voting replica with a usable clock. The wait is approximately `2 * Delta` plus per-group jitter of up to `Delta`, avoiding simultaneous transfers of many groups. This policy improves lease-read availability; it is separate from the commit/read safety predicates. See [fsm.cc:768](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:768), [fsm.cc:1140](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:1140), and [fsm.cc:1636](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:1636).

### Renewal and verification

Renewal is driven by read activity. A read records demand; a later tick appends a dummy entry if the committed interval is past half the duration or unavailable, the previous-term wait has ended, the log is fully committed, and leadership is not being transferred. Idle groups append no renewal no-ops, and a stalled group cannot accumulate an unbounded sequence of them. See [fsm.cc:930](/Users/ivan.andika/Documents/Storage/scylladb/raft/fsm.cc:930).

The unit tests exercise interval boundaries, deferred commits, clock loss, local reads versus quorum fallback, renewal, idle groups, and stepdown behavior. Randomized tests enable leases with simulated clock skew, partitions, restarts, and snapshotting while checking linearizability. However, reconfiguration is explicitly disabled in lease-enabled randomized runs, and clock failures are disabled in the read-only-tail run. The introductory test comment saying the lease run includes reconfiguration is broader than the actual generator configuration. See [fsm_test.cc:2920](/Users/ivan.andika/Documents/Storage/scylladb/test/raft/fsm_test.cc:2920), [randomized_nemesis_test.cc:3568](/Users/ivan.andika/Documents/Storage/scylladb/test/raft/randomized_nemesis_test.cc:3568), and [randomized_nemesis_test.cc:4035](/Users/ivan.andika/Documents/Storage/scylladb/test/raft/randomized_nemesis_test.cc:4035).

The randomized storage keeps entry pointers, so its restart scenarios do not test actual serialization. Separate commitlog replay tests check timestamp presence/absence and exact nanoseconds through both persisted and replication encodings. See [randomized_nemesis_test.cc:989](/Users/ivan.andika/Documents/Storage/scylladb/test/raft/randomized_nemesis_test.cc:989) and [commitlog_raft_replay_test.cc:1984](/Users/ivan.andika/Documents/Storage/scylladb/test/boost/commitlog_raft_replay_test.cc:1984). Tests were inspected but not run: this checkout has no configured build or test binaries, and its required Seastar submodule is empty.

### Implications for Ratis

Prefer this read-barrier integration over moving lease logic into every state machine. Reuse its demand-driven renewal and conservative recovery approach, and make clock failure an explicit state with defined fallback behavior. Its interval-plus-elapsed-time design is an alternative to the timer-first implementation proposed here when a trustworthy interval provider is available. It does not eliminate that provider requirement for local lease reads: its elapsed clock is only used to discharge deferred commits.

For Ratis, model and test clock-loss recovery, snapshot-only elections, pending read barriers, leadership transfer, and joint consensus together. In particular, the Scylla test exclusions should become explicit Ratis test requirements rather than being treated as already verified behavior.

## Ratis integration points

| Concern | Existing code | Proposed change |
| --- | --- | --- |
| Lease authorization | [LeaderLease.java:36](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/impl/LeaderLease.java:36) | Make heartbeat and log-derived lease modes explicit. Keep lease duration separate from election timeout in LeaseGuard mode. |
| Leader commit | [LeaderStateImpl.java:1027](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/impl/LeaderStateImpl.java:1027) | Gate leader commit advancement before calling `ServerState.updateCommitIndex`. Preserve flush, current-term, and joint-quorum checks. |
| Timed progress | [LeaderStateImpl.java:803](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/impl/LeaderStateImpl.java:803) | Schedule a term-scoped `UPDATE_COMMIT` event at prior-lease expiration; the existing idle loop does not automatically retry commitment. |
| ReadIndex lease shortcut | [LeaderStateImpl.java:1193](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/impl/LeaderStateImpl.java:1193) | Authorize the shortcut using fresh committed log evidence. Keep quorum ReadIndex as a fallback. |
| Query application barrier | [ReadRequestHandler.java:139](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/impl/ReadRequestHandler.java:139) | Preserve `waitToAdvance` and read-after-write handling for both read entry points. |
| Write admission | [RaftServerImpl.java:827](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/impl/RaftServerImpl.java:827) | Permit eligible writes to be appended before startup application when deferred commits are enabled. Do not mark all operations leader-ready. |
| Startup entry | [LeaderStateImpl.java:296](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/impl/LeaderStateImpl.java:296) | Capture inherited log boundary before appending startup entry; keep read readiness tied to safe application. |
| Entry construction | [RaftLogBase.java:173](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/raftlog/RaftLogBase.java:173) | Record local creation evidence, or stamp intervals in the originating append path for the full variant. Include configuration and internal entries. |
| Idle renewal | [RaftLogBase.java:221](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server/src/main/java/org/apache/ratis/server/raftlog/RaftLogBase.java:221) | Add a real internal no-op path. Existing metadata appends suppress unchanged commit indexes and cannot simply serve as periodic renewals. |
| Snapshots | [SnapshotInfo.java:30](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server-api/src/main/java/org/apache/ratis/statemachine/SnapshotInfo.java:30) | Preserve boundary lease evidence or impose a conservative wait when reconstructing unknown local age. SnapshotInfo currently exposes term/index/files, not clock bounds. |
| Inherited reads | [StateMachine.java:520](/Users/ivan.andika/.codex/worktrees/04e5/ratis-community/ratis-server-api/src/main/java/org/apache/ratis/statemachine/StateMachine.java:520) | Optional application contract to prove that an opaque query is unaffected by limbo entries. Default implementations cannot authorize this optimization. |

### Core behavior

The following is protocol pseudocode for the interval-clock variant, not proposed public Java API names:

```text
on election:
    record the inherited log tail before appending a current-term entry
    retain a conservative expiration bound for all prior-term lease evidence
    append/replicate the startup entry

on majority replication or expiration timer:
    if now.earliest <= priorLeaseExpiration:
        retain replication progress, but do not advance leader commitIndex
        arrange another timed check
    else:
        run Ratis' existing flush/quorum/current-term commit calculation

on a current-term leader read:
    capture the read barrier and committed-entry lease evidence consistently
    if ready and now.latest < committedEntry.time.earliest + Delta:
        use the lease to authorize the ReadIndex shortcut
    else:
        use quorum ReadIndex, or renew through a committed no-op
    wait until the state machine reaches the required read barrier
    execute the query
```

The prior-lease expiration must account for uncommitted inherited entries too: the previous leader might have committed an entry without this node learning its commit index. Cache a conservative maximum expiration, or prove the timestamp ordering needed to use only the last prior entry. A current-term entry becoming old must disable lease reads, not prevent future writes from committing and renewing the lease.

The full variant would add an optional interval field to the outer `LogEntryProto`, so configuration and internal entries can carry the same evidence. It must retain that metadata through transaction construction, state-machine-data separation, serialization, recovery, snapshot creation, and both snapshot installation workflows. Followers preserve the origin's interval; they do not overwrite it with their own wall time.

### Local timer variant

A first implementation can avoid replicated wall-clock intervals. Record a local timer when the leader creates an entry and when a follower first accepts it. With a defined drift allowance `epsilon`, shorten local read authorization to `Delta - epsilon` and conservatively delay new-term commits until inherited evidence has aged beyond `Delta + epsilon`.

This requires follower-side tracking that survives role transitions, not just a replacement inside the leader-only `LeaderLease` class. Batch reception, duplicate entries, truncation, snapshot installation, and log purging need explicit rules. On restart, persisted `nanoTime` values are unusable; reconstruct unknown inherited evidence with a conservative full waiting interval. Never use a timer learned as a follower to grant an inherited read.

The proposed recovery rules and timer lifecycle still need modeling and tests; the reference implementations inspected above use intervals and do not establish these production details for Ratis. Choose and document bounds that account for the actual timer platform, including suspend and virtualized-clock behavior.

### Deferred commit writes

Ratis currently rejects ordinary writes while the startup configuration entry is not yet applied. With LeaseGuard's new-term commit gate, leaving that admission rule unchanged removes the deferred-commit benefit.

Allow an elected, initialized leader to admit ordinary writes while the startup entry is waiting, with existing pending-request limits and cancellation behavior. Replication can proceed. Application, successful write replies, committed watches, and leader-ready notifications must continue to follow the gated commit/application state. Audit message streams, data streams, retry-cache handling, and state-machine leader callbacks before extending early admission to them.

Keep configuration-management and administrative operations on their established readiness path initially. The majority match index alone must never become a successful client write reply.

### Inherited reads

Full inherited reads need interval clocks plus an optional state-machine capability. On election, the uncertain range is inclusive: `(knownCommitIndex, inheritedLastIndex]`. The state machine must account for every possibly visible command in that range, including ranges, conditions, and application-wide effects. A simple set of literal keys is insufficient for a general Ratis application.

The capability should receive an immutable, term-scoped description of the uncertain entries and participate in query validation under the application's concurrency rules. It should conservatively decline unsupported queries. Validation, application progress, role changes, and asynchronous query execution must have a defined ordering; a stale validation result cannot authorize an unrelated later query.

Keep this out of generic follower `readIndexAsync` replies until there is a query-aware protocol: an inherited read's safety depends on the query, while a bare read index cannot convey which keys were proven unaffected. Ordinary follower reads can continue using the established current-term ReadIndex path.

### Configuration and leadership transfer

Use an explicit lease mode rather than changing the meaning of the existing disabled-by-default boolean. Validate lease duration, clock mode/drift allowance, renewal interval, and application support. Initially use the default `COMMIT_INDEX` read barrier; review `APPLIED_INDEX` and `REPLIED_INDEX` separately before claiming support.

Every node that can become leader must honor LeaseGuard's commit restriction before any node serves lease reads. A protobuf-compatible rolling upgrade alone does not establish this: an old leader binary can ignore the gate. Plan a group-wide activation protocol, or initially require a uniform, offline-configured group. Duration changes and disabling the feature must also preserve previously granted leases until they expire.

Retain joint-consensus quorum calculations. Disabling the old leader's local read lease during transfer does not erase lease evidence already replicated to other nodes. A new leader should still wait unless a separately specified and tested end-lease protocol permits earlier commitment.

## Suggested delivery and verification

1. Add the explicit mode, local timer lifecycle, commit gate, expiration scheduling, current-term read shortcut, renewal, and recovery behavior. Keep deferred admission off for this first complete protocol slice.
2. Add deferred write admission with unchanged commit/application acknowledgement semantics and bounded pending requests.
3. Add interval-clock support, persisted entry/snapshot bounds, and clock-failure behavior.
4. Add opt-in inherited reads with a small example state machine and a precisely scoped query contract.

Required tests should exercise an isolated old leader while a new leader replicates but cannot commit; expiration without new acknowledgements; fresh heartbeats with no log progress; an idle workload that needs renewal; clock boundary/drift/error conditions; repeated elections; restart and snapshot-only recovery; configuration changes; and leadership transfer. An inherited-read suite must include a sole uncertain tail entry, conflicting and nonconflicting queries, ranges and conditions, and queries delayed across expiration or role changes.

Run the existing linearizable-read suites and transport variants, then benchmark against both current ReadIndex and Ratis' existing lease mode. LogCabin's improvement over quorum reads does not predict an equivalent improvement over Ratis' already-local heartbeat-lease reads. Measure no-op storage/replication cost per group, read tail latency, failover write acknowledgement latency, and resource use across many idle groups.

No implementation tests or external benchmarks were run for this research. The proposed Ratis design remains to be implemented and verified.
