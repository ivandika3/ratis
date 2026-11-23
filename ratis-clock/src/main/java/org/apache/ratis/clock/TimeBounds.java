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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable, inclusive endpoints of a physical-time observation, not a continuously fresh clock. */
public final class TimeBounds {
  public static TimeBounds valueOf(Instant earliest, Instant latest) {
    return new TimeBounds(earliest, latest);
  }

  private final Instant earliest;
  private final Instant latest;

  private TimeBounds(Instant earliest, Instant latest) {
    this.earliest = Objects.requireNonNull(earliest, "earliest");
    this.latest = Objects.requireNonNull(latest, "latest");
    if (earliest.isAfter(latest)) {
      throw new IllegalArgumentException("earliest must not be after latest");
    }
  }

  public Instant getEarliest() {
    return earliest;
  }

  public Instant getLatest() {
    return latest;
  }

  /** Returns the entire window, not its half-width. No application-level precision limit is imposed. */
  public Duration getWidth() {
    return Duration.between(earliest, latest);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof TimeBounds)) {
      return false;
    }
    final TimeBounds that = (TimeBounds) obj;
    return earliest.equals(that.earliest) && latest.equals(that.latest);
  }

  @Override
  public int hashCode() {
    return Objects.hash(earliest, latest);
  }

  @Override
  public String toString() {
    return "[" + earliest + ", " + latest + "]";
  }
}
