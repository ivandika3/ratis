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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/** Explicit provider selection; there is no default engine or automatic substitution. */
public final class BoundedClocks {
  private BoundedClocks() {
  }

  /** Discovers factory names using the calling thread's context class loader, without creating clocks. */
  public static Set<String> getProviderNames() throws IOException {
    return Collections.unmodifiableSet(discover().keySet());
  }

  public static BoundedClock create(String name, Map<String, String> configuration) throws IOException {
    Objects.requireNonNull(name, "name");
    if (name.trim().isEmpty()) {
      throw new IllegalArgumentException("Provider name must not be blank");
    }
    final Map<String, String> copy = new HashMap<>();
    Objects.requireNonNull(configuration, "configuration").forEach((key, value) ->
        copy.put(Objects.requireNonNull(key, "configuration key"),
            Objects.requireNonNull(value, "configuration value")));
    final BoundedClockProvider provider = discover().get(name);
    if (provider == null) {
      throw new IOException("No bounded-clock provider named '" + name + "'");
    }
    return Objects.requireNonNull(provider.create(Collections.unmodifiableMap(copy)), "provider returned null clock");
  }

  private static Map<String, BoundedClockProvider> discover() throws IOException {
    final Map<String, BoundedClockProvider> providers = new TreeMap<>();
    try {
      for (BoundedClockProvider provider : ServiceLoader.load(BoundedClockProvider.class)) {
        final String name = provider.getName();
        if (name == null || name.trim().isEmpty()) {
          throw new IOException("Bounded-clock provider has no name: " + provider.getClass().getName());
        }
        if (providers.putIfAbsent(name, provider) != null) {
          throw new IOException("Duplicate bounded-clock provider name: " + name);
        }
      }
    } catch (ServiceConfigurationError e) {
      throw new IOException("Cannot discover bounded-clock providers", e);
    }
    return providers;
  }
}
