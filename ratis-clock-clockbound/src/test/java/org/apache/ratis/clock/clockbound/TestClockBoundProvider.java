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

import com.sun.jna.NativeLong;
import org.apache.ratis.clock.BoundedClocks;
import org.apache.ratis.clock.ClockReading;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestClockBoundProvider {
  @Test
  void discoveryIsIndependentOfNativeInstallation() throws Exception {
    assertTrue(BoundedClocks.getProviderNames().contains("clockbound"));
  }

  @Test
  void configurationIsValidatedBeforeLoadingNativeCode() {
    final ClockBoundProvider provider = new ClockBoundProvider();
    assertThrows(IllegalArgumentException.class, () -> provider.create(Collections.emptyMap()));
    final Map<String, String> properties = new HashMap<>();
    properties.put("time.reference", "test-reference");
    properties.put("typo", "value");
    assertThrows(IllegalArgumentException.class, () -> provider.create(properties));
    properties.remove("typo");
    properties.put("library.path", "/no/such/ratis-clockbound-library");
    assertThrows(IOException.class, () -> provider.create(properties));
    properties.put("shared.memory.path", "bad\u0000path");
    assertThrows(IllegalArgumentException.class, () -> provider.create(properties));
  }

  private static ClockBoundNative.Result sample(int status) {
    final ClockBoundNative.Result result = new ClockBoundNative.Result();
    result.clockStatus = status;
    result.earliest.seconds = new NativeLong(-1);
    result.earliest.nanos = new NativeLong(999_999_999);
    result.latest.seconds = new NativeLong(0);
    result.latest.nanos = new NativeLong(17);
    return result;
  }

  @Test
  void synchronizedAndFreeRunningIntervalsKeepNanosecondPrecision() {
    for (int status : new int[]{1, 2}) {
      final ClockReading reading = sample(status).toReading("test-reference");
      assertTrue(reading.isAvailable());
      assertEquals(Instant.ofEpochSecond(-1, 999_999_999), reading.getBounds().get().getEarliest());
      assertEquals(Instant.ofEpochSecond(0, 17), reading.getBounds().get().getLatest());
    }
  }

  @Test
  void untrustedStatusNeverExposesBounds() {
    for (int status : new int[]{0, 3, 99}) {
      final ClockReading reading = sample(status).toReading("test-reference");
      assertFalse(reading.isAvailable());
      assertFalse(reading.getBounds().isPresent());
      assertEquals(status == 3 ? ClockReading.UnavailableReason.DISRUPTED
          : status == 0 ? ClockReading.UnavailableReason.UNKNOWN : ClockReading.UnavailableReason.PROVIDER_ERROR,
          reading.getUnavailableReason().get());
    }
  }

  @Test
  void malformedNativeIntervalsAreUnavailable() {
    ClockBoundNative.Result result = sample(1);
    result.latest.nanos = new NativeLong(1_000_000_000);
    assertFalse(result.toReading("test-reference").isAvailable());
    result = sample(1);
    result.earliest.nanos = new NativeLong(-1);
    assertFalse(result.toReading("test-reference").isAvailable());
    result = sample(1);
    result.latest.seconds = new NativeLong(-2);
    assertFalse(result.toReading("test-reference").isAvailable());
    result = sample(1);
    result.latest.seconds = new NativeLong(Long.MAX_VALUE);
    assertEquals(ClockReading.UnavailableReason.PROVIDER_ERROR,
        result.toReading("test-reference").getUnavailableReason().get());
  }

  @Test
  void errorDetailsNeedNotBeNulTerminated() {
    final ClockBoundNative.Error error = new ClockBoundNative.Error();
    java.util.Arrays.fill(error.detail, (byte) 'x');
    assertEquals(128, error.describe().substring(error.describe().indexOf(": ") + 2).length());
  }
}
