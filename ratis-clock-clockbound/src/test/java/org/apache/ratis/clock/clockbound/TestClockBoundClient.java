/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.clock.clockbound;

import com.sun.jna.Native;
import org.apache.ratis.clock.BoundedClock;
import org.apache.ratis.clock.BoundedClocks;
import org.apache.ratis.clock.ClockReading;
import org.apache.ratis.clock.TimeBounds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the pinned, unmodified upstream client, not a replacement implementation of its C ABI. */
@Tag("clock-native")
@Timeout(30)
class TestClockBoundClient {
  @TempDir
  private Path directory;

  private static String requiredLibrary(String property) {
    final String path = System.getProperty(property);
    assertNotNull(path, "Native suite requires -D" + property + "=<built library>");
    assertFalse(path.isEmpty(), "Native suite requires -D" + property + "=<built library>");
    assertFalse(path.startsWith("${"), "Native library property was not supplied");
    assertTrue(Files.isRegularFile(java.nio.file.Paths.get(path)), "Missing native library: " + path);
    return path;
  }

  private static TestClockBoundNative.Control control() {
    return Native.load(requiredLibrary("clockbound.fixture.library"), TestClockBoundNative.Control.class);
  }

  @Test
  void projectsTheIntervalAtASamplingInstantAndSupportsOtherCallers() throws Exception {
    final TestClockBoundNative.Control control = control();
    final Segment segment = new Segment(control.fixtureCounter());
    segment.write(1, 123_456, 2_000, 10, 0);
    try (BoundedClock clock = segment.open()) {
      final long before = control.fixtureCounter();
      final ClockReading reading = clock.now();
      final long after = control.fixtureCounter();
      assertTrue(reading.isAvailable(), reading.toString());
      assertEquals("synthetic-reference", reading.getTimeReference());
      final TimeBounds bounds = reading.getBounds().get();
      assertEquals(Duration.ofNanos(246_912), bounds.getWidth());
      // The synthetic period is exactly 2^-30 seconds/tick; tolerate native floating-point rounding.
      final Instant center = bounds.getEarliest().plusNanos(123_456);
      assertFalse(center.isBefore(segment.project(before).minusNanos(2)));
      assertFalse(center.isAfter(segment.project(after).plusNanos(2)));
      final FutureTask<ClockReading> otherRead = new FutureTask<>(clock::now);
      final Thread caller = new Thread(otherRead);
      caller.start();
      assertTrue(otherRead.get(10, TimeUnit.SECONDS).isAvailable());
      caller.join();
    }
  }

  @Test
  void freeRunningUncertaintyGrowsWithDriftAndPeriodError() throws Exception {
    final TestClockBoundNative.Control control = control();
    final long anchor = control.fixtureCounter() - 1_000_000;
    final Segment segment = new Segment(anchor);
    // A deliberately large relative period error (1/2) and oscillator drift (1/10).
    segment.write(2, 10, 2_000, 10, 100_000_000, Long.MIN_VALUE);
    try (BoundedClock clock = segment.open()) {
      final long before = control.fixtureCounter();
      final ClockReading reading = clock.now();
      final long after = control.fixtureCounter();
      assertTrue(reading.isAvailable(), reading.toString());
      final long width = reading.getBounds().get().getWidth().toNanos();
      final double growthPerTick = (100_000_000.0 + 500_000_000.0) / (1L << 30);
      final long minimum = 2 * (10 + (long) ((before - anchor) * growthPerTick));
      final long maximum = 2 * (10 + (long) ((after - anchor) * growthPerTick));
      assertTrue(width > 20, "Uncertainty must grow beyond the initial bound");
      assertTrue(width >= minimum - 4, "Width must include oscillator and period uncertainty");
      assertTrue(width <= maximum + 4, "Width must reflect the sampled counter, not an arbitrary bound");
    }
  }

  @Test
  void observesGenerationChangesDisruptionExpiryAndRecovery() throws Exception {
    final Segment segment = new Segment(control().fixtureCounter());
    segment.write(1, 60_000_000_000L, 2_000, 10, 0);
    final BoundedClock clock = segment.open();
    try {
      final Instant previousLatest = clock.now().getBounds().get().getLatest();
      segment.write(2, 10, 2_000, 10, 0);
      final ClockReading narrower = clock.now();
      assertTrue(narrower.isAvailable(), narrower.toString());
      assertTrue(narrower.getBounds().get().getLatest().isBefore(previousLatest),
          "Do not clamp interval endpoints to previous readings");
      segment.write(0, 10, 2_000, 10, 0);
      assertEquals(ClockReading.UnavailableReason.UNKNOWN, clock.now().getUnavailableReason().get());
      segment.write(1, 10, 2_000, 11, 0);
      assertEquals(ClockReading.UnavailableReason.DISRUPTED, clock.now().getUnavailableReason().get());
      segment.write(1, 10, 999, 10, 0);
      assertEquals(ClockReading.UnavailableReason.UNKNOWN, clock.now().getUnavailableReason().get());
      segment.write(1, 10, 2_000, 10, 0);
      assertTrue(clock.now().isAvailable());
    } finally {
      clock.close();
    }
    clock.close();
    assertThrows(IllegalStateException.class, clock::now);
  }

  @Test
  void nativeReadErrorsAreUnavailableAndCanRecover() throws Exception {
    final Segment segment = new Segment(control().fixtureCounter());
    segment.write(1, 10, 2_000, 10, 0);
    try (BoundedClock clock = segment.open()) {
      segment.write(1, 10, 2_000, 10, 1_000_000_000);
      final ClockReading malformed = clock.now();
      assertEquals(ClockReading.UnavailableReason.PROVIDER_ERROR, malformed.getUnavailableReason().get());
      assertTrue(malformed.getDescription().contains("max_drift_ppb"));
      segment.write(1, 10, 2_000, 10, 0);
      assertTrue(clock.now().isAvailable());
    }
  }

  @Test
  void missingAndMalformedSharedMemoryFailInitialization() throws Exception {
    final Segment segment = new Segment(control().fixtureCounter());
    assertThrows(IOException.class, segment::open);
    Files.write(segment.clockbound, new byte[4096]);
    assertThrows(IOException.class, segment::open);
    segment.write(1, 10, 2_000, 10, 0);
    Files.delete(segment.vmclock);
    assertThrows(IOException.class, segment::open);
    segment.write(3, 10, 2_000, 10, 0);
    try (BoundedClock clock = segment.open()) {
      assertEquals(ClockReading.UnavailableReason.DISRUPTED, clock.now().getUnavailableReason().get());
    }
  }

  private final class Segment {
    private final Path clockbound = directory.resolve("clockbound");
    private final Path vmclock = directory.resolve("vmclock");
    private final long counter;
    private int generation;

    private Segment(long counter) {
      this.counter = counter;
    }

    private Instant project(long sample) {
      return Instant.ofEpochSecond(1_000).plusNanos((long) ((sample - counter) * (1_000_000_000.0 / (1L << 30))));
    }

    private BoundedClock open() throws IOException {
      final Map<String, String> configuration = new HashMap<>();
      configuration.put("time.reference", "synthetic-reference");
      configuration.put("library.path", requiredLibrary("clockbound.native.library"));
      configuration.put("shared.memory.path", clockbound.toString());
      configuration.put("vmclock.path", vmclock.toString());
      return BoundedClocks.create("clockbound", configuration);
    }

    private void write(int status, long bound, long voidAfter, long marker, int drift) throws IOException {
      write(status, bound, voidAfter, marker, drift, 0);
    }

    private void write(int status, long bound, long voidAfter, long marker, int drift, long periodError)
        throws IOException {
      generation += 2;
      // ClockBound SHM3 protocol at the pinned revision. No writer runs concurrently with a read here.
      final ByteBuffer cb = ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder());
      cb.putInt(0, 0x414D5A4E).putInt(4, 0x43420200).putInt(8, 4096);
      cb.putShort(12, (short) 0x0303).putShort(14, (short) generation);
      cb.putLong(16, counter).putLong(24, 1_000).putLong(32, 0);
      cb.putLong(40, voidAfter).putLong(48, 0);
      cb.putLong(56, Long.MIN_VALUE).putLong(64, periodError).putLong(72, bound);
      cb.putLong(80, 10).putInt(88, drift).putInt(92, status);
      cb.put(96, (byte) 1).put(97, (byte) 29).put(98, (byte) 0);

      final ByteBuffer vm = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
      vm.putInt(0, 0x4B4C4356).putInt(4, 104).putShort(8, (short) 1);
      vm.put(10, (byte) 1).putInt(12, generation).putLong(16, marker).put(34, (byte) 2);
      overwrite(vmclock, vm);
      overwrite(clockbound, cb);
    }

    private void overwrite(Path path, ByteBuffer bytes) throws IOException {
      // Never truncate files mapped by the native client (which could SIGBUS the JVM).
      try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
        while (bytes.hasRemaining()) {
          file.write(bytes);
        }
        file.force(true);
      }
    }
  }
}

