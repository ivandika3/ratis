<!---
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# AWS ClockBound as a LeaseGuard clock provider

Researched 2026-11-11. Scope: official AWS documentation and `aws/clock-bound` source; feasibility only. No integration, deployment, or runtime validation was performed.

## Assessment

**Yes, ClockBound is a plausible optional bounded-clock provider for Ratis LeaseGuard on supported Linux deployments.** Its client returns an uncertainty interval and clock status, matching the interval-clock prerequisite. It is not a drop-in Java dependency: AWS documents Rust and C clients, not an official Java client. A native bridge, explicit failure policy, and deployment qualification remain necessary. This is a feasibility assessment, not a finding that a Ratis integration is safe or production-ready. [AWS overview](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/compare-timestamps-with-clockbound.html), [tagged README](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/README.md), [client contract](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/src/client_impl.rs).

## Version and release status

- The newest tag observed is **`3.0.0-rc.0`**, commit `371058f8f6a1786d4a4ab3eed9f5f2f16eea7777`; its changelog entry is dated **2026-11-02**. This is a release candidate, not final 3.0.0. Earlier entries include `3.0.0-beta.1` (2026-10-12) and `3.0.0-beta.0` (2026-08-24). [Tags API](https://api.github.com/repos/aws/clock-bound/tags?per_page=100), [tagged changelog](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/CHANGELOG.md).
- GitHub's Releases API/page currently lists only `3.0.0-alpha.1` and `3.0.0-alpha.0`, both explicitly prereleases. Thus the latest source tag and changelog are newer than the published GitHub Release assets; do not interpret the README's RPM-install instructions as evidence that rc.0 RPMs are published there. [Releases API](https://api.github.com/repos/aws/clock-bound/releases?per_page=30), [release page](https://github.com/aws/clock-bound/releases).
- The changelog records non-prerelease **2.0.3** dated 2025-08-13. That historical release line must be distinguished from the 3.x prereleases; no final 3.0.0 was established by the checked sources. The 2.x discussion below uses the official `main-2.0` branch pinned at `75b754b234c7001021300a3ee10876e98cdfbc71`. [Changelog](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/CHANGELOG.md), [2.x source](https://github.com/aws/clock-bound/tree/75b754b234c7001021300a3ee10876e98cdfbc71).
- Pin daemon, native library, header, and protocol versions together. In particular, rc.0 fixes the shared-memory writer to use the documented stable `repr(C)` layout; earlier layouts could depend on compiler settings/version. This is concrete prerelease compatibility risk, not just a naming caveat. [rc.0 fixes](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/CHANGELOG.md).

## API, status, and guarantee

For 3.0.0-rc.0, Rust exposes `Client::new()` and `Client::now(&mut self) -> Result<TimeWindow, Error>`. `TimeWindow` contains `earliest`, `latest`, and `clock_status`. Its documentation guarantees that true time lies between the endpoints when status is `Synchronized` or `FreeRunning`. Interval width is twice the clock error bound; it is not a point estimate or an assumed fixed skew. [Client implementation and TimeWindow documentation](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/src/client_impl.rs).

| Status | Native value | Source contract and implication |
| --- | --- | --- |
| `Unknown` | `CLOCKBOUND_STA_UNKNOWN` / 0 | Time is not trustworthy; no usable bounded-time sample. |
| `Synchronized` | `CLOCKBOUND_STA_SYNCHRONIZED` / 1 | Synchronized to a reference; interval is valid. |
| `FreeRunning` | `CLOCKBOUND_STA_FREE_RUNNING` / 2 | Synchronization has been lost, but interval remains valid while the client considers it trustworthy; uncertainty grows. |
| `Disrupted` | `CLOCKBOUND_STA_DISRUPTED` / 3 | Clock disruption means time is not trustworthy; reject the sample. |

These meanings are specified by the [C header](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound-ffi/include/clockbound.h), [TimeWindow contract](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/src/client_impl.rs), and [protocol](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/protocol.md). Successful API execution alone does not imply valid time: status must also be checked. Accepting only `Synchronized` would be a stricter availability policy, not the complete upstream validity contract.

The 3.x C API offers `clockbound_open(err)`, `clockbound_open_with(clockbound_shm_path, vmclock_shm_path, err)`, `clockbound_now(ctx, res, err)`, and `clockbound_close(ctx, err)`. `clockbound_now_result` holds two `struct timespec` endpoints and a status. Open returns a context or NULL; now/close return NULL on success or an error pointer on failure. Errors include syscall, uninitialized/malformed segment, unsupported segment version, and causality breach. **Contexts are explicitly not thread-safe: each thread must open its own context.** [Exact header](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound-ffi/include/clockbound.h).

The guarantee is conditional on a consistent trusted reference and the clock/error/disruption model. The repository describes the NTP bound as `abs(local offset) + root dispersion + root delay / 2`, with uncertainty increasing between updates. It is not an unconditional fixed-microsecond guarantee, and the checked sources do not establish a service SLA for LeaseGuard safety. [Bound description](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/README.md), [read-time growth and expiry](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/protocol.md).

**Documentation inconsistency:** the rc.0 C header's `clockbound_now` prose still describes reading the system clock. The 3.x changelog, protocol, and client implementation instead describe client timestamps derived from daemon-provided clock parameters and hardware counters, independent of `CLOCK_REALTIME`. Use the versioned implementation/protocol for this distinction. [Header](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound-ffi/include/clockbound.h), [3.x changes](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/CHANGELOG.md), [protocol](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/protocol.md).

## Deployment: 2.x versus 3.x

| Concern | 2.x | 3.x prerelease |
| --- | --- | --- |
| Clock synchronization | Requires `chronyd`; ClockBound queries chrony and bounds the OS clock. | ClockBound itself synchronizes/ disciplines the system clock using a feed-forward algorithm; chrony is no longer required. |
| NTP | Configure chrony to use the trusted source. Match chrony's `maxclockerror` to ClockBound's `--max-drift-rate`; upstream warns that chrony's default 1 ppm is optimistic for a bound and suggests 50 ppm for most configurations, subject to hardware validation. | Automatically discovers local/public Amazon Time Sync sources; beta.0 adds optional TOML configuration and additional NTP sources. rc.0 adds IPv6-only local-source support and AWS-partition-aware public endpoints. |
| PHC | When chrony uses PHC, supply PHC reference ID/interface so ClockBound incorporates PHC error. | Detects ENA PHC and synchronizes directly when available. |
| Client/protocol | OS-clock-based client; shared-memory v2 at `/var/run/clockbound/shm0`. | Counter-based client; v3 at `/var/run/clockbound/shm1`. Daemon also writes compatibility `shm0`; support for 2.x clients is deprecated. |

Sources: [pinned 2.x daemon instructions](https://github.com/aws/clock-bound/blob/75b754b234c7001021300a3ee10876e98cdfbc71/clock-bound-d/README.md), [3.x daemon instructions](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/clockbound-daemon.md), [versioned changelog](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/CHANGELOG.md), [both protocols](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/protocol.md). “2.x/3.x” here refers to ClockBound versions, not NTP protocol versions.

Deployment constraints:

- **Linux host service, not an AWS SDK network call.** AWS explicitly limits ClockBound support to Linux instances. The repository documents privileged daemon operation, systemd, and x86_64/aarch64 RPMs. A containerized JVM would need access to the host's relevant shared-memory files and VMClock device; that is a deployment inference, not a verified container integration. [AWS support statement](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/compare-timestamps-with-clockbound.html), [daemon installation](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/clockbound-daemon.md).
- **NTP does not require PHC hardware.** Every EC2 instance can access local NTP at `169.254.169.123`; `fd00:ec2::123` is available on Nitro. Current AWS documentation describes enhanced timing through `precision-time` placement groups on listed Gen7-and-later families, in all commercial Regions. Linux PHC additionally requires supported hardware and ENA setup; instance-family membership alone is not sufficient evidence of a working device. The maintained AWS support matrix should be checked for the intended fleet. [Current EC2 configuration/support matrix](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configure-ec2-ntp.html).
- **PHC error must not be omitted.** AWS warns that chrony otherwise assumes zero PHC source error; Nitro exposes the actual PHC error through ENA sysfs `phc_error_bound`, in nanoseconds. Do not substitute bare chrony PHC statistics for ClockBound's complete interval. [AWS PHC error-bound instructions](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/compare-timestamps-with-clockbound.html).
- **VMClock is a separate disruption mechanism, not the PHC.** Upstream lists Amazon Linux 2 kernel `5.10.223-211.872.amzn2`, Amazon Linux 2023 kernel `6.1.102-108.177.amzn2023`, and upstream Linux 6.13 or later, subject to hypervisor support and readable `/dev/vmclock0`. The kernel version alone does not prove device availability. 2.x requires VMClock by default; disabling disruption support is an explicit escape hatch for environments not expected to disrupt. That must not silently become a LeaseGuard safety assumption. [3.x prerequisites](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/clockbound-daemon.md), [2.x prerequisites](https://github.com/aws/clock-bound/blob/75b754b234c7001021300a3ee10876e98cdfbc71/clock-bound-d/README.md).
- **Timescale consistency needs review.** AWS NTP uses leap smearing whereas direct PHC does not. Nodes cannot assume these are interchangeable during leap handling merely because both are AWS sources. [AWS leap-smearing consideration](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configure-ec2-ntp.html).

## Java feasibility and integration boundaries

**No official Java client was identified in the checked AWS documentation/repository.** The official README names Rust and C clients and permits custom clients via the shared-memory protocol. That does not imply a supported Java binding or an AWS SDK for Java ClockBound API. [Official client inventory](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/README.md).

**Recommendation: bridge the version-pinned native C library**, using JNI/JNA or, only if the chosen Java baseline permits it, JDK 22+ FFM. These are prospective integration choices, not upstream-provided Java support. Preserve native context ownership, deterministic cleanup, endpoint precision, errors, and status. Avoid a hand-written Java mmap reader initially: v3 requires `rdtsc`/`cntvct_el0`, fixed-point period conversion, both drift and relative-period-error growth, `void_after` expiry, generation/seqlock consistency, and VMClock disruption checks. Reading only the stored endpoints/bound would be insufficient. [Protocol requirements](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/docs/protocol.md), [client disruption checks](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound/src/client_impl.rs), [native ownership contract](https://github.com/aws/clock-bound/blob/3.0.0-rc.0/clock-bound-ffi/include/clockbound.h).

There is a relevant implementation precedent: the LeaseGuard LogCabin prototype's `TimeBounds::localNow()` opens an older ClockBound API, returns its earliest/latest bounds, and panics unless status is synchronized. This illustrates the interval-provider role, not compatibility with the current API or production readiness. Its stricter status policy should not be mistaken for upstream's rejection of `FreeRunning`. [Pinned LogCabin implementation](https://github.com/mongodb-labs/logcabin/blob/c4813ab2893d34ab3874ef7b47f7cf897cf6338b/Core/Time.cc#L303).

The following are recommendations for the independent Ratis integration review, not ClockBound guarantees:

1. Keep the provider optional. Reject API errors, `Unknown`, `Disrupted`, invalid endpoints, and bounds too wide for the configured lease policy; never replace them with zero uncertainty or `System.currentTimeMillis()`.
2. Stop authorizing new lease reads on clock loss. Retain existing prior-lease fences: clock failure cannot prove those leases expired, and ordinary quorum commitment must not bypass a fence owed to an earlier leader.
3. Keep a separate monotonic timer for scheduling/retries. A bounded-time observation describes its sample instant, not an indefinitely fresh authorization across JVM pauses or asynchronous query execution.
4. Qualify the exact daemon/library versions, architecture, VMClock availability, time-source/timescale configuration, and host/container permissions. Exercise daemon loss/restart, stale memory/expiry, disruption/recovery, clock steps, growing free-running uncertainty, and long process pauses before relying on the provider.

**Bottom line:** technically feasible as an optional native-backed interval provider, with meaningful deployment and failure-handling work. The investigated 3.x implementation is still prerelease; no Ratis integration or correctness claim follows from this research.
