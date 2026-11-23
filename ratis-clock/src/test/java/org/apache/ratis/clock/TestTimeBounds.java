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
package org.apache.ratis.clock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TestTimeBounds {
  @Test
  void endpointsAreExactAndOrdered() {
    final Instant earliest = Instant.ofEpochSecond(-1, 999_999_999);
    final TimeBounds bounds = TimeBounds.valueOf(earliest, Instant.EPOCH);
    assertEquals(earliest, bounds.getEarliest());
    assertEquals(Instant.EPOCH, bounds.getLatest());
    assertEquals(Duration.ofNanos(1), bounds.getWidth());
    assertEquals(bounds, TimeBounds.valueOf(earliest, Instant.EPOCH));
    assertEquals(bounds.hashCode(), TimeBounds.valueOf(earliest, Instant.EPOCH).hashCode());
    assertThrows(IllegalArgumentException.class, () -> TimeBounds.valueOf(Instant.EPOCH, earliest));
    assertThrows(NullPointerException.class, () -> TimeBounds.valueOf(null, Instant.EPOCH));
    assertThrows(NullPointerException.class, () -> TimeBounds.valueOf(Instant.EPOCH, null));
  }

  @Test
  void zeroWidthAndLargeRangesAreRepresentable() {
    assertEquals(Duration.ZERO, TimeBounds.valueOf(Instant.EPOCH, Instant.EPOCH).getWidth());
    final TimeBounds bounds = TimeBounds.valueOf(Instant.MIN, Instant.MAX);
    assertEquals(Duration.between(Instant.MIN, Instant.MAX), bounds.getWidth());
  }

  @Test
  void laterReadMayHaveALowerLatestEndpoint() {
    final TimeBounds first = TimeBounds.valueOf(Instant.ofEpochMilli(99_000), Instant.ofEpochMilli(101_000));
    final TimeBounds second = TimeBounds.valueOf(Instant.ofEpochMilli(100_000), Instant.ofEpochMilli(100_200));
    assertTrue(second.getLatest().isBefore(first.getLatest()));
    assertEquals(Duration.ofMillis(200), second.getWidth());
  }

  @Test
  void readingHasEitherBoundsOrAReasonAndAlwaysAReference() {
    final TimeBounds bounds = TimeBounds.valueOf(Instant.EPOCH, Instant.EPOCH.plusNanos(1));
    final ClockReading available = ClockReading.available("test-reference", bounds);
    assertTrue(available.isAvailable());
    assertEquals(bounds, available.getBounds().get());
    assertFalse(available.getUnavailableReason().isPresent());
    assertEquals("test-reference", available.getTimeReference());
    for (ClockReading.UnavailableReason reason : ClockReading.UnavailableReason.values()) {
      final ClockReading unavailable = ClockReading.unavailable("test-reference", reason, "explanation");
      assertFalse(unavailable.isAvailable());
      assertFalse(unavailable.getBounds().isPresent());
      assertEquals(reason, unavailable.getUnavailableReason().get());
      assertEquals("explanation", unavailable.getDescription());
      assertEquals("test-reference", unavailable.getTimeReference());
    }
    assertThrows(IllegalArgumentException.class, () -> ClockReading.available(" ", bounds));
    assertThrows(NullPointerException.class, () -> ClockReading.available("test-reference", null));
    assertThrows(NullPointerException.class,
        () -> ClockReading.unavailable("test-reference", null, "explanation"));
  }
}
