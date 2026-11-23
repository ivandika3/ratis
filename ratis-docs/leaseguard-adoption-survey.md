# Public LeaseGuard adoption survey

Checked: 2026-10-27. This records implementation and proposal evidence, not a correctness audit or proof of production deployment.

## Scope and method

The starting inventory was the [raft.github.io implementation list](https://raft.github.io/#implementations). Its [JSON registry at revision de64ee6](https://github.com/raft/raft.github.io/blob/de64ee6f303cba3a0b00d68ef44bcca2e0e8e194/implementations.json) contains 160 entries. It is an implementation directory, not a registry of LeaseGuard adoption, and several additional relevant projects are not listed.

Searches covered public GitHub code, issues, PRs, PR comments, project documentation, and the authors' reference repository. Queries included `LeaseGuard`, `2512.15659`, and `Raft Leases Done Right`, followed by inspection of matching code and discussion. Repository-specific searches covered major libraries and resolved renamed repository URLs. This was not an exhaustive source audit of every one of the 160 entries.

Conventional heartbeat/election leases, generic resource guards named `LeaseGuard`, and paper citations without implementation evidence were kept separate. Search absence cannot exclude private work, unindexed branches/forks, deleted material, or an implementation using a different name.

## Findings

| Project | Evidence and status | Relationship to LeaseGuard |
| --- | --- | --- |
| LogCabin | Authors' prototype exists on the [mongodb-labs/logcabin `leaseguard` branch](https://github.com/mongodb-labs/logcabin/tree/c4813ab2893d34ab3874ef7b47f7cf897cf6338b). | Reference implementation with interval clocks, deferred commits, and inherited reads. The registry points to the original LogCabin repository, not this research fork. |
| ScyllaDB / Seastar Raft | Core [PR #30697](https://github.com/scylladb/scylladb/pull/30697) merged on 2026-08-28. Activation/backends [PR #30777](https://github.com/scylladb/scylladb/pull/30777) remains open. | Core LeaseGuard and deferred commits are merged. The separate SC-table integration is experimental and disabled by default according to its PR. |
| Sailing, Rust | Explicit `ReadOnlyOption::LeaseGuard` exists in [main-branch configuration code](https://github.com/al8n/sailing/blob/b131cd5cb81d771b7a4d3d3970156b1ceedd424a/sailing-proto/src/config/mod.rs). The [README](https://github.com/al8n/sailing/blob/b131cd5cb81d771b7a4d3d3970156b1ceedd424a/README.md) labels the project pre-release. | Implemented commit-anchored leases using local timers, commit-wait handling, renewal, and optional inherited-read support with wall-clock uncertainty. |
| NATS JetStream | [(2.14) Linearizable read leases, PR #7748](https://github.com/nats-io/nats-server/pull/7748), opened 2026-01-20, closed unmerged on 2026-09-18. | Explicit paper-inspired implementation attempt using local timers and committed log entries, with no inherited reads or deferred-write optimization. |
| OpenRaft | [Feature request #1651](https://github.com/databendlabs/openraft/issues/1651), opened 2026-01-16, remains open and unassigned. | Explicit proposal. No implementing PR or indexed implementation was found. Existing LeaseRead is a different mechanism. |
| Hydro | [Raft read lease, PR #3217](https://github.com/hydro-project/hydro/pull/3217), opened 2026-10-09, closed unmerged on 2026-10-10. | Exploratory prototype and discussion explicitly extending toward LeaseGuard's multi-leader read optimization; bounded-clock support remained an acknowledged issue. |

The first three projects listed in the table have identifiable implementation code. NATS and Hydro are implementation attempts, and OpenRaft is a proposal. These classifications do not imply that the code is correct, released, enabled by default, or deployed in production.

## Additional implementation: Sailing

Sailing's main branch was inspected at `b131cd5cb81d771b7a4d3d3970156b1ceedd424a`. It is not in the inspected raft.github.io registry.

Its [configuration enum and validation](https://github.com/al8n/sailing/blob/b131cd5cb81d771b7a4d3d3970156b1ceedd424a/sailing-proto/src/config/mod.rs) distinguish `Safe`, conventional `LeaseBased`, and `LeaseGuard`. LeaseGuard requires a duration and clock-drift bound. An additional bounded-clock uncertainty setting enables its inherited-read tier.

The [read implementation](https://github.com/al8n/sailing/blob/b131cd5cb81d771b7a4d3d3970156b1ceedd424a/sailing-proto/src/endpoint/read_index.rs) checks the committed anchor's term, timestamp, and lease-bearing metadata for local-timer reads. It also exposes `failover_read_window()` for inherited reads. That API supplies the committed index and inclusive limbo upper boundary; the application must establish that its query is unaffected by the uncertain entries before serving it.

The [replication implementation](https://github.com/al8n/sailing/blob/b131cd5cb81d771b7a4d3d3970156b1ceedd424a/sailing-proto/src/endpoint/replication.rs) contains commit-wait handling. [Read-mode migration](https://github.com/al8n/sailing/blob/b131cd5cb81d771b7a4d3d3970156b1ceedd424a/sailing-proto/src/endpoint/read_mode.rs) uses a replicated entry and changes mode at application time. This makes it a useful additional reference for Ratis' mode selection, activation, and optional application contract, while still requiring its own correctness review.

## NATS JetStream attempt

[PR #7748](https://github.com/nats-io/nats-server/pull/7748) explicitly cites the paper and its local-timer alternative. Its author describes coverage for selected JetStream API reads such as MsgGet, excluding DirectGet at that stage. The PR explicitly omits inherited lease reads and deferred writes; it should be classified as a partial, paper-inspired attempt rather than assumed to implement every LeaseGuard rule.

The [patch](https://github.com/nats-io/nats-server/pull/7748/files) adds `LeaderLease()`, records the originating append time locally, refreshes the lease when an entry commits, and adds no-op entries for renewal. Its lease timeout is tied to the minimum election timeout.

The PR remained a draft and was [automatically closed for inactivity](https://github.com/nats-io/nats-server/pull/7748#issuecomment-5732176949). Closure is not evidence of a technical rejection. [ADR discussion #390](https://github.com/nats-io/nats-architecture-and-design/pull/390) remains open. Follow-up searches found no other matching NATS PR, and indexed default-branch code search found no `LeaderLease` symbol. No merged or released adoption was established.

## OpenRaft proposal

[Issue #1651](https://github.com/databendlabs/openraft/issues/1651) proposes log-based leases, deferred commits, and inherited reads. The issue was briefly closed, then reopened after the distinction from existing LeaseRead was clarified.

The [maintainer's response](https://github.com/databendlabs/openraft/issues/1651#issuecomment-3760829120) explains that existing leases depend on heartbeat quorum acknowledgement time. It raises questions about coexistence and workload-dependent benefits and reopens the proposal for discussion.

The linked [LeaseRead PR #1362](https://github.com/databendlabs/openraft/pull/1362) merged on 2025-05-13 and uses `last_quorum_acked_time`; it is not a LeaseGuard implementation. The inspected issue timeline did not link a subsequent implementing PR or commit. The proposal's suggested implementation details should not themselves be treated as a validated protocol specification.

## Hydro exploration

[PR #3217](https://github.com/hydro-project/hydro/pull/3217) starts as an exploratory implementation of the original Raft read-lease idea. Its discussion then explicitly considers LeaseGuard's log-derived leases and query flow.

On October 10, the author [reported implementing the multi-leader read optimization](https://github.com/hydro-project/hydro/pull/3217#issuecomment-6093826744), then [acknowledged the need for bounded-uncertainty clocks and interval handling](https://github.com/hydro-project/hydro/pull/3217#issuecomment-6103223819). A later maintainer comment says clocks need stronger Hydro support and that no design was known to that commenter.

The inspected draft patch uses heartbeat-related lease state and scalar `SystemTime` timestamps. It was closed without merging. This is clear evidence of an attempt and discussion, not evidence of a completed LeaseGuard implementation. A follow-up repository issue/PR search found no other LeaseGuard hit.

## ScyllaDB public status beyond the local review

The earlier [Ratis design note](leaseguard-design.md) inspected a local ScyllaDB checkout and found core support without activation or a concrete production clock backend. The public PR history explains this split:

- [Merged core PR #30697](https://github.com/scylladb/scylladb/pull/30697) explicitly implements the paper as an optional Raft feature with an abstract clock and test implementation.
- [Open PR #30777](https://github.com/scylladb/scylladb/pull/30777), last updated on October 26 when checked, adds SC-table wiring, configuration, and ClockBound/adjtimex providers. It describes the feature as experimental and off by default, with ClockBound intended for production and adjtimex for testing/convenience.

An open activation PR does not establish production availability. This is also why the absence of a provider in the earlier local checkout should not be interpreted as absence of development work on one.

## Projects searched without further adoption evidence

Repository-specific GitHub code queries were `repo:OWNER/REPO LeaseGuard` and `repo:OWNER/REPO 2512.15659`. Issue/PR queries additionally searched titles, bodies, and comments. The successful code searches over the following repositories returned no matches for either term. Issue/PR searches returned only the OpenRaft proposal already described above.

| Project scope | Result |
| --- | --- |
| `etcd-io/raft`, `etcd-io/etcd` | No explicit public LeaseGuard adoption/proposal evidence found. |
| `tikv/raft-rs`, `tikv/tikv` | No explicit public LeaseGuard adoption/proposal evidence found. |
| `baidu/braft` | No explicit public LeaseGuard adoption/proposal evidence found. |
| `sofastack/sofa-jraft` | No explicit public LeaseGuard adoption/proposal evidence found. |
| `databendlabs/openraft` | Open proposal only; no indexed code matches. |
| `eBay/NuRaft` | No explicit public LeaseGuard adoption/proposal evidence found. |
| `lni/dragonboat` | No explicit public LeaseGuard adoption/proposal evidence found. |

For reproducibility, example issue searches are [etcd Raft / LeaseGuard](https://github.com/search?q=repo%3Aetcd-io%2Fraft+%22LeaseGuard%22+in%3Atitle%2Cbody%2Ccomments&type=issues) and [raft-rs / paper ID](https://github.com/search?q=repo%3Atikv%2Fraft-rs+%222512.15659%22+in%3Atitle%2Cbody%2Ccomments&type=issues). The registry's `brpc/braft`, `alipay/sofa-jraft`, and `datafuselabs/openraft` URLs resolve to the canonical names used above.

Supplemental issue/PR searches covering MongoDB, Redpanda, Apache Ratis, MicroRaft, RabbitMQ Ra, DotNext, and cowsql found no LeaseGuard hits. This is a narrower search result than an implementation audit. MongoDB researchers' authorship of the paper is not itself evidence of MongoDB server deployment.

Existing [raft-rs lease reads](https://github.com/tikv/raft-rs/blob/master/src/read_only.rs), [braft leases](https://github.com/baidu/braft/blob/master/src/braft/lease.h), and [etcd LeaseRead discussion #166](https://github.com/etcd-io/raft/issues/166) should not be relabeled LeaseGuard without protocol-specific evidence.

## Related material excluded from implementation counts

- [VibeDB's historical read-path investigation](https://github.com/thesyncim/vibedb/blob/2fcf23e9d7601ca8dce913b381e965d76c6e05f9/docs/history/read-path-redesign.md) considers LeaseGuard but explicitly does not claim implemented support. Its [current read-authority design](https://github.com/thesyncim/vibedb/blob/2fcf23e9d7601ca8dce913b381e965d76c6e05f9/docs/design/reads-and-time.md) uses quorum promises that block elections, so it is not counted as LeaseGuard adoption.
- [Citadel Protocol PR #272](https://github.com/Avarok-Cybersecurity/Citadel-Protocol/pull/272) cites LeaseGuard as related literature, but implements leaderless CASPaxos group locks, not a Raft LeaseGuard port.
- [The authors' Python/TLA+ repository](https://github.com/muratdem/RaftLeaderLeases) is the research artifact already covered in the earlier note, not a separate production Raft implementation.

## Implications for Ratis

ScyllaDB remains the closest reference for integrating a lease shortcut into an existing read barrier. Sailing adds a concrete local-timer implementation and an application-facing inherited-read API to examine. NATS supplies another local-timer implementation attempt, although it remained unmerged and is not evidence that its particular timing or compatibility assumptions are sufficient.

The OpenRaft discussion is useful when evaluating whether LeaseGuard improves Ratis over its existing heartbeat lease. Benchmark against that existing local-read path as well as quorum ReadIndex; the paper's comparison with quorum reads alone does not establish the incremental benefit for Ratis.

No external builds, tests, or benchmarks were run for this adoption survey. No source repositories or external discussions were modified.
