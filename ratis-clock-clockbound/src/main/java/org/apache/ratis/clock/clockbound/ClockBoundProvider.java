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

import org.apache.ratis.clock.BoundedClock;
import org.apache.ratis.clock.BoundedClockProvider;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicitly selected ClockBound 3.x provider. The operator manages the native client and daemon.
 * Qualified deployments use Linux x86_64 or aarch64 and a matching 64-bit client C ABI.
 * Reference metadata is an operator assertion about the daemon's sources and leap policy, not detection.
 */
public final class ClockBoundProvider implements BoundedClockProvider {
  public static final String TIME_REFERENCE = "time.reference";
  public static final String LIBRARY_PATH = "library.path";
  public static final String SHARED_MEMORY_PATH = "shared.memory.path";
  public static final String VMCLOCK_PATH = "vmclock.path";

  private static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      TIME_REFERENCE, LIBRARY_PATH, SHARED_MEMORY_PATH, VMCLOCK_PATH)));

  @Override
  public String getName() {
    return "clockbound";
  }

  @Override
  public BoundedClock create(Map<String, String> configuration) throws IOException {
    final Map<String, String> copy = new HashMap<>(Objects.requireNonNull(configuration, "configuration"));
    copy.forEach((key, value) -> {
      if (!KEYS.contains(key)) {
        throw new IllegalArgumentException("Unknown ClockBound setting: " + key);
      }
      if (value == null || value.trim().isEmpty() || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Invalid ClockBound setting: " + key);
      }
    });
    final String reference = copy.get(TIME_REFERENCE);
    if (reference == null) {
      throw new IllegalArgumentException("ClockBound requires " + TIME_REFERENCE + " (time scale and leap policy)");
    }
    final String path = copy.getOrDefault(LIBRARY_PATH, "clockbound");
    final ClockBoundNative library;
    try {
      library = ClockBoundNative.load(path);
    } catch (UnsatisfiedLinkError | RuntimeException e) {
      throw new IOException("Cannot load the ClockBound 3.x C API from '" + path + "'", e);
    }
    return new ClockBoundClock(library, reference,
        copy.getOrDefault(SHARED_MEMORY_PATH, "/var/run/clockbound/shm1"),
        copy.getOrDefault(VMCLOCK_PATH, "/dev/vmclock0"));
  }
}
