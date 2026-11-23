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

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable outcome of one observation. A reference identifier declares a time scale and leap policy;
 * it does not prove synchronization. Different references must not be compared without qualification.
 */
public final class ClockReading {
  public enum UnavailableReason {
    /** No trustworthy bound, including lost synchronization or expired calibration. */
    UNKNOWN,
    /** A clock discontinuity invalidated the bound. */
    DISRUPTED,
    /** The provider could not obtain or decode a reading. */
    PROVIDER_ERROR
  }

  public static ClockReading available(String timeReference, TimeBounds bounds) {
    return new ClockReading(timeReference, Objects.requireNonNull(bounds, "bounds"), null, "");
  }

  public static ClockReading unavailable(String timeReference, UnavailableReason reason, String description) {
    return new ClockReading(timeReference, null, Objects.requireNonNull(reason, "reason"), description);
  }

  private final String timeReference;
  private final TimeBounds bounds;
  private final UnavailableReason unavailableReason;
  private final String description;

  private ClockReading(String timeReference, TimeBounds bounds, UnavailableReason reason, String description) {
    this.timeReference = Objects.requireNonNull(timeReference, "timeReference");
    if (timeReference.trim().isEmpty()) {
      throw new IllegalArgumentException("timeReference must not be blank");
    }
    this.bounds = bounds;
    this.unavailableReason = reason;
    this.description = Objects.requireNonNull(description, "description");
  }

  public String getTimeReference() {
    return timeReference;
  }

  public boolean isAvailable() {
    return bounds != null;
  }

  public Optional<TimeBounds> getBounds() {
    return Optional.ofNullable(bounds);
  }

  public Optional<UnavailableReason> getUnavailableReason() {
    return Optional.ofNullable(unavailableReason);
  }

  public String getDescription() {
    return description;
  }

  @Override
  public String toString() {
    return timeReference + ": " + (isAvailable() ? bounds : unavailableReason + " (" + description + ")");
  }
}
