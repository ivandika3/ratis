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

import com.sun.jna.Library;
import com.sun.jna.Native;
import org.apache.ratis.clock.BoundedClock;
import org.apache.ratis.clock.BoundedClocks;
import org.apache.ratis.clock.ClockReading;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("clock-native")
@Timeout(30)
class TestClockBoundNative {
  interface Control extends Library {
    void fixtureMode(int mode);
    int fixtureOpenCount();
    int fixtureCloseCount();
    int fixtureActiveCount();
    void fixtureRelease();
    int fixtureResultSize();
    int fixtureErrorSize();
    long fixtureCounter();
  }

  private static String library() {
    final String path = System.getProperty("clockbound.fixture.library");
    assertNotNull(path, "Native suite requires -Dclockbound.fixture.library=<built fixture>");
    assertFalse(path.isEmpty(), "Native suite requires -Dclockbound.fixture.library=<built fixture>");
    assertFalse(path.startsWith("${"), "Native fixture property was not supplied");
    return path;
  }

  private static Map<String, String> configuration() {
    final Map<String, String> configuration = new HashMap<>();
    configuration.put("time.reference", "fixture-reference");
    configuration.put("library.path", library());
    return configuration;
  }

  private static Control control() {
    return Native.load(library(), Control.class);
  }

  @Test
  void realNativeLayoutsAndStatusMapping() throws Exception {
    final Control control = control();
    control.fixtureMode(0);
    assertEquals(control.fixtureResultSize(), new ClockBoundNative.Result().size());
    assertEquals(control.fixtureErrorSize(), new ClockBoundNative.Error().size());
    try (BoundedClock clock = BoundedClocks.create("clockbound", configuration())) {
      assertEquals(Instant.ofEpochSecond(-1, 999_999_999), clock.now().getBounds().get().getEarliest());
      assertEquals(Instant.ofEpochSecond(0, 17), clock.now().getBounds().get().getLatest());
      control.fixtureMode(2);
      assertTrue(clock.now().isAvailable());
      control.fixtureMode(1);
      assertEquals(ClockReading.UnavailableReason.UNKNOWN, clock.now().getUnavailableReason().get());
      control.fixtureMode(3);
      assertEquals(ClockReading.UnavailableReason.DISRUPTED, clock.now().getUnavailableReason().get());
      control.fixtureMode(4);
      final ClockReading failed = clock.now();
      assertEquals(ClockReading.UnavailableReason.PROVIDER_ERROR, failed.getUnavailableReason().get());
      assertTrue(failed.getDescription().contains("fixture failure"));
      control.fixtureMode(5);
      assertFalse(clock.now().isAvailable());
      control.fixtureMode(0);
      assertTrue(clock.now().isAvailable());
    } finally {
      control.fixtureMode(0);
    }
  }

  @Test
  void failedStartupClosesItsContextAndUnsynchronizedStartupIsAllowed() throws Exception {
    final Control control = control();
    control.fixtureMode(4);
    final int opens = control.fixtureOpenCount();
    final int closes = control.fixtureCloseCount();
    assertThrows(IOException.class, () -> BoundedClocks.create("clockbound", configuration()));
    assertEquals(opens + 1, control.fixtureOpenCount());
    assertEquals(closes + 1, control.fixtureCloseCount());
    control.fixtureMode(1);
    try (BoundedClock clock = BoundedClocks.create("clockbound", configuration())) {
      assertFalse(clock.now().isAvailable());
    } finally {
      control.fixtureMode(0);
    }
  }

  @Test
  void concurrentReadersOwnTheirContextsAndReleaseThemOnClose() throws Exception {
    final Control control = control();
    control.fixtureMode(0);
    final int opens = control.fixtureOpenCount();
    final int closes = control.fixtureCloseCount();
    final BoundedClock clock = BoundedClocks.create("clockbound", configuration());
    final ExecutorService executor = Executors.newFixedThreadPool(4);
    final CountDownLatch ready = new CountDownLatch(4);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      final List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, TimeUnit.SECONDS));
          for (int j = 0; j < 100; j++) {
            assertTrue(clock.now().isAvailable(), "Native fixture rejects cross-thread context use");
          }
          return null;
        }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      clock.close();
      clock.close();
    }
    assertEquals(control.fixtureOpenCount() - opens, control.fixtureCloseCount() - closes);
    assertThrows(IllegalStateException.class, clock::now);
  }

  @Test
  void closeWaitsForAnInFlightNativeRead() throws Exception {
    final Control control = control();
    control.fixtureMode(0);
    final BoundedClock clock = BoundedClocks.create("clockbound", configuration());
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch initialized = new CountDownLatch(1);
    final CountDownLatch enter = new CountDownLatch(1);
    final FutureTask<Void> close = new FutureTask<>(() -> {
      clock.close();
      return null;
    });
    final Thread closer = new Thread(close);
    try {
      final Future<ClockReading> read = executor.submit(() -> {
        assertTrue(clock.now().isAvailable());
        initialized.countDown();
        assertTrue(enter.await(10, TimeUnit.SECONDS));
        return clock.now();
      });
      assertTrue(initialized.await(10, TimeUnit.SECONDS));
      control.fixtureMode(6);
      enter.countDown();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (control.fixtureActiveCount() == 0 && System.nanoTime() < deadline) {
        Thread.yield();
      }
      assertEquals(1, control.fixtureActiveCount());
      final int closes = control.fixtureCloseCount();
      closer.start();
      final long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (closer.getState() != Thread.State.WAITING && !close.isDone()
          && System.nanoTime() < closeDeadline) {
        Thread.yield();
      }
      assertEquals(Thread.State.WAITING, closer.getState(), "close must wait on the lifecycle lock");
      assertFalse(close.isDone());
      assertEquals(closes, control.fixtureCloseCount());
      // The native fixture records an error if any context is closed during this blocked read.
      control.fixtureRelease();
      assertTrue(read.get(10, TimeUnit.SECONDS).isAvailable());
      close.get(10, TimeUnit.SECONDS);
      closer.join(10_000);
      assertFalse(closer.isAlive());
      assertTrue(control.fixtureCloseCount() > closes);
      assertThrows(IllegalStateException.class, clock::now);
    } finally {
      control.fixtureRelease();
      control.fixtureMode(0);
      enter.countDown();
      executor.shutdownNow();
      executor.awaitTermination(10, TimeUnit.SECONDS);
      closer.join(10_000);
      clock.close();
    }
  }

  @Test
  void terminatedCallerContextsAreReclaimedBeforeShutdown() throws Exception {
    final Control control = control();
    control.fixtureMode(0);
    try (BoundedClock clock = BoundedClocks.create("clockbound", configuration())) {
      final FutureTask<Boolean> firstRead = new FutureTask<>(() -> clock.now().isAvailable());
      final Thread first = new Thread(firstRead);
      first.start();
      first.join();
      assertTrue(firstRead.get());
      final int before = control.fixtureCloseCount();
      final FutureTask<Boolean> secondRead = new FutureTask<>(() -> clock.now().isAvailable());
      final Thread second = new Thread(secondRead);
      second.start();
      second.join();
      assertTrue(secondRead.get());
      assertTrue(control.fixtureCloseCount() > before);
    }
  }

  @Test
  void nativePathsUseUtf8RegardlessOfTheJnaDefault() throws Exception {
    final Control control = control();
    control.fixtureMode(8);
    final Map<String, String> configuration = configuration();
    configuration.put("shared.memory.path", "/tmp/clock-\u00e9");
    try (BoundedClock clock = new ClockBoundProvider().create(configuration)) {
      configuration.put("shared.memory.path", "mutated-path");
      configuration.put("time.reference", "mutated-reference");
      assertTrue(clock.now().isAvailable());
      final FutureTask<ClockReading> reading = new FutureTask<>(clock::now);
      final Thread caller = new Thread(reading);
      caller.start();
      final ClockReading result = reading.get(10, TimeUnit.SECONDS);
      caller.join();
      assertTrue(result.isAvailable(), result.toString());
      assertEquals("fixture-reference", result.getTimeReference());
    } finally {
      control.fixtureMode(0);
    }
  }

  @Test
  void aFailedNewCallerCanRetryWithoutBreakingExistingContexts() throws Exception {
    final Control control = control();
    control.fixtureMode(0);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try (BoundedClock clock = BoundedClocks.create("clockbound", configuration())) {
      final int opens = control.fixtureOpenCount();
      control.fixtureMode(7);
      assertEquals(ClockReading.UnavailableReason.PROVIDER_ERROR,
          executor.submit(clock::now).get().getUnavailableReason().get());
      assertEquals(opens, control.fixtureOpenCount());
      assertTrue(clock.now().isAvailable(), "An already-open context is unaffected");
      control.fixtureMode(0);
      assertTrue(executor.submit(clock::now).get().isAvailable());
      assertEquals(opens + 1, control.fixtureOpenCount());
    } finally {
      control.fixtureMode(0);
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void closeAttemptsEveryContextEvenWhenTheNativeClientReportsErrors() throws Exception {
    final Control control = control();
    control.fixtureMode(0);
    final BoundedClock clock = BoundedClocks.create("clockbound", configuration());
    try {
      final FutureTask<ClockReading> reading = new FutureTask<>(clock::now);
      final Thread caller = new Thread(reading);
      caller.start();
      assertTrue(reading.get(10, TimeUnit.SECONDS).isAvailable());
      caller.join();
      final int closes = control.fixtureCloseCount();
      control.fixtureMode(9);
      final IOException failure = assertThrows(IOException.class, clock::close);
      assertTrue(failure.getMessage().contains("fixture close failure"));
      assertEquals(1, failure.getSuppressed().length);
      assertEquals(closes + 2, control.fixtureCloseCount());
      clock.close();
      assertThrows(IllegalStateException.class, clock::now);
    } finally {
      control.fixtureMode(0);
      clock.close();
    }
  }

  @Test
  void incompatibleClientIsRejectedBeforeCallingItsFunctions() {
    final String legacy = System.getProperty("clockbound.legacy.library");
    assertNotNull(legacy, "Native suite requires -Dclockbound.legacy.library=<legacy fixture>");
    assertFalse(legacy.isEmpty());
    final Map<String, String> configuration = configuration();
    configuration.put("library.path", legacy);
    final IOException failure = assertThrows(IOException.class,
        () -> BoundedClocks.create("clockbound", configuration));
    assertTrue(failure.getCause() instanceof UnsatisfiedLinkError);
  }
}
