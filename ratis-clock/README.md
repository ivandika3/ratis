<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# Ratis Bounded Clock

`ratis-clock` is a standalone Java 8+ API with no production dependencies outside the JDK.
`ratis-clock-clockbound` is an optional provider using JNA and an operator-installed AWS ClockBound client.
Neither module changes Raft behavior or configures a server. LeaseGuard (RATIS-2782) and timestamp-bounded
stale reads (RATIS-2376) are separate consumers of this foundation (RATIS-2825).

## Contract

An available `ClockReading` has immutable, inclusive `TimeBounds` expressed as two nanosecond-precision
`Instant` values. There is a sampling instant between invocation and completion of `now()` at which
reference time lies within those bounds, **provided the provider's source, drift, and platform assumptions hold**.
This is a conditional containment guarantee, not a confidence percentile.

The interval does not necessarily contain reference time at return, at RPC receipt, or at subsequent use.
The library does not extrapolate a sample through pauses or network latency. Consumers must establish
their own protocol-specific timing rules. It makes no guarantee of increasing endpoints, increasing widths,
a maximum interval width, or continued availability. A later interval can be narrower with an earlier latest
endpoint. Use an elapsed-time clock separately when the protocol needs elapsed time.

Every reading, including an unavailable one, carries a declared time-reference identifier. Operators must
agree on identifiers that capture both the physical time scale and leap-second/smear policy. Identical labels
are assertions, not proof that clocks share a trustworthy source. Different references cannot be compared
without an independently justified conversion. `Instant` is the endpoint representation; it does not discover
or normalize a provider's leap policy.

Unavailable readings have no interval and one of `UNKNOWN`, `DISRUPTED`, or `PROVIDER_ERROR`, with a diagnostic
description. Consumers must not invent an interval around `System.currentTimeMillis()` as a substitute.

Clocks support concurrent callers. `close()` waits for active reads, releases resources, and is idempotent.
After closure, `now()` throws `IllegalStateException`. Close failures are reported as `IOException`; the instance
is still closed and must not be reused.

## Selection And Failure Policy

Add `org.apache.ratis:ratis-clock` and a chosen provider artifact to your application.
Discovering provider names does not open a clock or load the native ClockBound client:

```java
Set<String> names = BoundedClocks.getProviderNames();
```

Selection is explicit; missing or duplicate provider names fail. Configuration is copied and passed as an
unmodifiable map. Providers must validate their options. ClockBound rejects unknown keys, null values,
blank strings, and embedded NULs before loading native code.

Example (imports from `org.apache.ratis.clock` and `java.util`):

```java
Map<String, String> configuration = new HashMap<>();
// Example only: this identifier must match the deployed source's actual scale and leap policy.
configuration.put("time.reference", "utc-amazon-leap-smear");
configuration.put("library.path", "/opt/clockbound/lib/libclockbound.so");
try (BoundedClock clock = BoundedClocks.create("clockbound", configuration)) {
  ClockReading reading = clock.now();
  if (reading.isAvailable()) {
    TimeBounds bounds = reading.getBounds().get();
    // Pass bounds to a consumer that implements its own timing protocol.
  }
}
```

The application decides whether a requested provider's initialization failure should prevent startup or
disable a feature. Runtime failures produce unavailable observations. There is **no automatic fallback**
to another provider or to ordinary wall time. A deliberate future fallback must independently satisfy the
same contract and use a compatible time reference. These modules do not make the Ratis server depend on
ClockBound being installed.

Implement a new engine using `BoundedClockProvider` and register its public no-argument factory in
`META-INF/services/org.apache.ratis.clock.BoundedClockProvider`. Factories must be safe to discover
without opening native resources. Each `create()` returns an independently owned clock.
NTP, PTP, or SPTP connectivity alone is not a proof of bounded error; a provider must document how its
uncertainty and loss-of-synchronization handling satisfy the contract.

## ClockBound Provider

Add `org.apache.ratis:ratis-clock-clockbound`; Maven resolves JNA transitively.
For a manually assembled classpath, include the core, provider, and JNA jars.
The provider targets the **ClockBound 3.x LP64 C ABI on Linux x86_64 and aarch64**, qualified against
[3.0.0-rc.0, commit 371058f8f6a1786d4a4ab3eed9f5f2f16eea7777](https://github.com/aws/clock-bound/tree/371058f8f6a1786d4a4ab3eed9f5f2f16eea7777).
This is a release-candidate pin, not a claim of compatibility with every future 3.x release.
The legacy 2.x ABI is incompatible and rejected before calling its same-named functions. Symbol/word-size
checks cannot certify an arbitrary binary; supply a matching, trusted client library. No native ClockBound
binary is shipped by Ratis.

| Setting | Required | Default |
| --- | --- | --- |
| `time.reference` | Yes | None; operator declares time scale and leap policy |
| `library.path` | No | `clockbound` (JNA library search) |
| `shared.memory.path` | No | `/var/run/clockbound/shm1` |
| `vmclock.path` | No | `/dev/vmclock0` |

Only use configuration and native-library paths from trusted operators. Ratis does not install software,
start a daemon, change host time, set NTP sources, or elevate privileges. The operator must configure the
daemon's time sources, drift assumptions, shared-memory permissions, and VMClock/disruption support as
required by upstream ClockBound. Bounds depend on those assumptions, even when a sample says synchronized.
Free-running samples remain available because the native client expands their uncertainty and reports
UNKNOWN when its validity horizon expires. UNKNOWN and DISRUPTED samples never expose intervals.

Initialization opens a context and probes it. Missing libraries, unreadable/malformed shared memory, and
native call errors fail initialization. An operational source currently reporting UNKNOWN or DISRUPTED
can initialize but returns unavailable readings. Native contexts are owned per calling Java thread,
because the C API is not thread-safe; they are reclaimed for terminated callers when another context is
created, or when the clock closes. Prefer bounded executor pools over continually creating threads.

## Verification

Normal Maven tests require no ClockBound installation, C compiler, Rust, daemon, privileges, or time changes:

```sh
mvn -pl ratis-clock-clockbound -am test
```

The regular PR workflow additionally runs `dev-support/checks/clock-native.sh` on Linux x86_64 and aarch64
with Java 8 and 25. It requires `cc`, `rustup`, `git`, Maven, and network access to pinned source/dependencies.
It builds only the pinned client FFI, not a daemon, using a pinned Rust toolchain and Cargo lockfile.

There are two native test boundaries:
- A controlled C fixture, compiled against the pinned upstream header, checks structure layouts, errors,
  UTF-8 paths, caller context ownership, teardown, and a deterministic read/close race.
- The unmodified upstream client reads temporary synthetic ClockBound/VMClock protocol files. Tests cover
  projected bounds, uncertainty growth, generation updates, shrinking intervals, free-running/unknown/disrupted states,
  expiry, malformed data, missing files, recovery, and multiple callers.

The synthetic data is deliberately unrelated to real UTC and is not an accuracy test. No test writes to
the real ClockBound or VMClock files. Requested native tests fail if their libraries are missing; they do
not silently skip. Run the complete native path on a supported Linux host with:

```sh
dev-support/checks/clock-native.sh
```

For a locally built controlled fixture, run just its class using `-Pclock-native-tests`,
`-Dtest=TestClockBoundNative -Dsurefire.failIfNoSpecifiedTests=false`, and absolute
`-Dclockbound.fixture.library=...` / `-Dclockbound.legacy.library=...` paths.
Running the full native profile also requires `-Dclockbound.native.library=...`.
A live-daemon deployment test can supplement these checks; it is not required for ordinary CI.
The adjtimex provider remains a separate follow-up.

