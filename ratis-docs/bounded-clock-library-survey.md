# Bounded-clock library survey

Checked: 2026-11-11. Narrow primary-source survey of reusable Java/general-purpose clock libraries, not a correctness audit. Google TrueTime availability and time-appliance/SPTP research are outside this survey; brief adjacent findings supplied by the parent are identified below.

## What qualifies

The target is a replaceable provider returning a current-time interval, not merely a clock interface, offset, duration interval, or high-resolution timestamp. Keep three contracts separate:

- **Point estimate:** one corrected timestamp; precision, monotonicity, and sample filtering do not establish containment.
- **Statistical uncertainty:** an interval with a probability/confidence claim under a measurement model; not deterministic containment on every invocation.
- **Hard conditional bound:** worst-case containment assuming explicitly valid reference-time error, oscillator/drift limits, and freshness/disruption conditions. It is not unconditional protection against faulty sources or violated assumptions.

## Candidates

| Candidate | Public API and replaceability | Classification and fit |
| --- | --- | --- |
| **Time4J SNTP (Java)** | `SntpConnector` exposes point-time methods and `getLastReply()`; inherited `NetTimeConnector` exposes `currentTime()`, connection state, and offset. Configuration is replaceable, including through `ServiceLoader`. [Connector source][t4j-sntp], [base source][t4j-net]. | **Point estimate.** The connector averages SNTP offsets using the equal-path-delay assumption. No current-time containment interval or growing uncertainty contract was found in these APIs. General date/time interval support is not such a contract. |
| **Apache Commons Net NTP (Java)** | `TimeInfo` exposes `getOffset()`, `getDelay()`, packet, receive time, and processing comments. `NtpV3Packet` exposes root delay/dispersion. [TimeInfo API][net-time], [packet API][net-packet]. | **Offset estimate plus protocol measurements**, not a bounded-now provider. Root dispersion alone is not a complete local-time bound. Constructing, aging, and invalidating an interval would be additional application logic, not the inspected library's contract. |
| **Chronicle Core time (Java)** | `TimeProvider` is a replaceable functional interface with `currentTimeMillis()`, `currentTimeMicros()`, and `currentTimeNanos()`; documentation distinguishes normal wall clocks from monotonic timestamp implementations. [Interface source][chronicle]. | **Point timestamps.** Pluggability exists, but there are no interval endpoints, uncertainty semantics, or validity metadata in this interface. Nanosecond units and monotonicity do not supply an accuracy bound. |
| **Instacart TrueTime (Android/JVM)** | The project describes an SNTP client; current usage injects `TrueTimeImpl` and calls `now()`. Older first-party documentation describes returning a corrected `Date`, invalidating cached information after reboot, and periodic resynchronization for drift. [README][truetime], [algorithm wiki][truetime-how]. | **Point estimate**, despite the name. No earliest/latest containment API was established. Current code is Kotlin/coroutines and retains an Android `SystemClock` dependency according to the README; it is not an identified standalone server-Java interval SPI. |
| **AWS ClockBound (Rust/C)** | Rust `Client::now()` and C `clockbound_now()` return earliest/latest with clock status, backed by a daemon and shared memory. The project documents custom clients, but this is a specific clock engine/protocol, not a generic Java provider abstraction. [README][cb], [protocol][cb-protocol]. | **Hard conditional bound by documented intent.** The project describes worst-case accumulated reference-to-host error, not a confidence percentile. Validity depends on trusted time sources, drift assumptions, and permitted status/freshness; the protocol explicitly limits trust with a void-after timestamp and identifies disruption. Native integration or a custom client would still be needed for Java. |
| **Meta fbclock (C library + Go daemon)** | Parent-confirmed revision `c75bf842472e5db11bb950e5b74db8563de06e28`: `fbclock_gettime()` returns `earliest_ns`/`latest_ns`; options select NTP/PTP sources and V3 (default V2). Errors include stale/no data and excessive uncertainty. The header warns against assuming stable binary layout: build caller and library from the same version. [Pinned header][fb-header]. | **Actual multi-source interval library**, not merely PTP monitoring. The README's PTP WOU claim is statistical six-nines, not deterministic worst-case containment. Do not transfer that claim to the newer NTP path or assume all sources share identical guarantees. [Pinned README][fb-readme]. No Java interval provider was identified here. |
| **Huygens** | The authors describe a software synchronization system using purified probes, SVM estimation, and network-wide correction, evaluated on hardware testbeds. [Authors' NSDI presentation/abstract][huygens]. | **Estimation and empirical synchronization accuracy**, not evidence of a reusable interval API. This bounded search did not establish a public Java/general-purpose bounded-now library with explicit per-call containment semantics. Testbed accuracy is not a hard bound or itself a statistical interval contract. |

An additional naming collision is **JTT (Java TrueTime)**: its authors describe an embedded-control-system simulator, not a live uncertainty-bounded clock provider. [Authors' paper][jtt].

## Adjacent parent cross-checks

These are parent-supplied findings, not an additional independent implementation audit here:

- Current fbclock daemon configuration includes SPTP and HTTP/socket fetchers; V3 adds a chrony-derived NTP path and can operate NTP-only with `EnableDataV3`. This makes fbclock a meaningful multi-source candidate even though its README remains PTP-focused. [Pinned source tree][fb-tree].
- libptpmgmt **ClockManager** has chrony/PTP monitoring and a Java wrapper example, but monitoring data is not a bounded-now contract. [Upstream project][clkmgr].
- OCP **Precise-Time-API** contains a README and DOCX proposal, not a runtime library to adopt. [Proposal repository][ocp].

## Implication for Ratis

No inspected candidate supplies the complete combination of a reusable Java bounded-now SPI, interchangeable engines, and an explicit hard conditional containment contract. **Use a small Java SPI around existing engines**, rather than treating a point-time interface as sufficient. ClockBound and fbclock are credible native backends, but their guarantees must remain distinguishable.

The SPI contract should specify the reference timescale/leap-smear policy, which event within the call the endpoints bound, bound category and assumptions, and unavailable/stale/disrupted behavior. Preserve backend failures; do not silently fall back to a point timestamp or reinterpret a statistical window as a hard bound. Source selection in a native library is useful, but is not the same as a backend-independent provider contract. This is a recommendation only; no implementation is included.

## Search limits

Stopped after the named candidates, targeted `TrueTime Java library`, `TrueTime Java earliest interval`, and `Huygens clock synchronization github/API` searches, and inspection of the linked first-party APIs/source. No exhaustive Maven/fork/private-code audit was attempted. Some Javadoc/PDF fetches failed; Time4J source and the authors' Huygens conference abstract were used instead. Negative findings mean **not established in the inspected surfaces**, not proof that no qualifying library exists. Moving branch links describe the inspected source, not a promised released feature set.

[t4j-sntp]: https://github.com/MenoData/Time4J/blob/master/base/src/main/java/net/time4j/clock/SntpConnector.java
[t4j-net]: https://github.com/MenoData/Time4J/blob/master/base/src/main/java/net/time4j/clock/NetTimeConnector.java
[net-time]: https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ntp/TimeInfo.html
[net-packet]: https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ntp/NtpV3Packet.html
[chronicle]: https://github.com/OpenHFT/Chronicle-Core/blob/ea/src/main/java/net/openhft/chronicle/core/time/TimeProvider.java
[truetime]: https://github.com/instacart/truetime-android
[truetime-how]: https://github.com/instacart/truetime-android/wiki/How-TrueTime-works
[cb]: https://github.com/aws/clock-bound/blob/main/README.md
[cb-protocol]: https://github.com/aws/clock-bound/blob/main/docs/protocol.md
[fb-header]: https://github.com/meta/time/blob/c75bf842472e5db11bb950e5b74db8563de06e28/fbclock/fbclock.h
[fb-readme]: https://github.com/meta/time/blob/c75bf842472e5db11bb950e5b74db8563de06e28/fbclock/README.md
[fb-tree]: https://github.com/meta/time/tree/c75bf842472e5db11bb950e5b74db8563de06e28/fbclock
[huygens]: https://www.usenix.org/conference/nsdi18/presentation/geng
[jtt]: https://www.mdpi.com/1424-8220/10/9/8585
[clkmgr]: https://github.com/erezgeva/libptpmgmt
[ocp]: https://github.com/Time-Appliances-Project/Precise-Time-API
