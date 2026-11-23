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

import java.io.IOException;

/**
 * A thread-safe source of bounded physical time, independent of elapsed-time clocks.
 *
 * <p>For each available reading there is a sampling instant between invocation and completion
 * whose reference time lies in the inclusive bounds, assuming the provider's documented source,
 * drift and platform requirements hold. This is a containment contract, not a confidence percentile.
 * The result need not contain the time at method return or later use. Endpoints and widths need not
 * be monotonic. Cooperating nodes must use compatible declared time references and leap policies.
 *
 * <p>Unavailable readings confer no time guarantee. In particular they must not be replaced by
 * ordinary wall time with invented uncertainty.
 */
public interface BoundedClock extends AutoCloseable {
  /** Obtains a new observation. Calls after closure throw {@link IllegalStateException}. */
  ClockReading now();

  /** Idempotently closes the clock, waiting for in-flight reads before releasing resources. */
  @Override
  void close() throws IOException;
}
