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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestBoundedClocks {
  private static final AtomicInteger CREATIONS = new AtomicInteger();
  @TempDir
  private Path directory;

  public static class TestProvider implements BoundedClockProvider {
    @Override
    public String getName() {
      return "test";
    }

    @Override
    public BoundedClock create(Map<String, String> configuration) {
      assertThrows(UnsupportedOperationException.class, () -> configuration.put("unexpected", "value"));
      CREATIONS.incrementAndGet();
      return new TestClock(configuration);
    }
  }

  public static class DuplicateProvider extends TestProvider {
  }

  private static class TestClock implements BoundedClock {
    private final Map<String, String> configuration;
    private boolean closed;

    TestClock(Map<String, String> configuration) {
      this.configuration = configuration;
    }

    @Override
    public synchronized ClockReading now() {
      if (closed) {
        throw new IllegalStateException("closed");
      }
      return ClockReading.available(configuration.get("time.reference"),
          TimeBounds.valueOf(Instant.EPOCH, Instant.EPOCH.plusNanos(1)));
    }

    @Override
    public synchronized void close() {
      closed = true;
    }
  }

  private void withProviders(String services, CheckedRunnable action) throws Exception {
    final Path serviceFile = directory.resolve("providers");
    Files.write(serviceFile, services.getBytes(StandardCharsets.UTF_8));
    final URL url = serviceFile.toUri().toURL();
    final Thread thread = Thread.currentThread();
    final ClassLoader previous = thread.getContextClassLoader();
    final String resource = "META-INF/services/" + BoundedClockProvider.class.getName();
    thread.setContextClassLoader(new ClassLoader(previous) {
      @Override
      public Enumeration<URL> getResources(String name) throws IOException {
        return resource.equals(name) ? Collections.enumeration(Collections.singletonList(url))
            : super.getResources(name);
      }
    });
    try {
      action.run();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  @FunctionalInterface
  private interface CheckedRunnable {
    void run() throws Exception;
  }

  @Test
  void discoveryDoesNotCreateClocksAndCreationCopiesConfiguration() throws Exception {
    withProviders(TestProvider.class.getName(), () -> {
      CREATIONS.set(0);
      assertEquals(Collections.singleton("test"), BoundedClocks.getProviderNames());
      assertEquals(0, CREATIONS.get());
      final Map<String, String> configuration = new HashMap<>();
      configuration.put("time.reference", "test-reference");
      try (BoundedClock clock = BoundedClocks.create("test", configuration)) {
        configuration.put("time.reference", "changed");
        assertEquals("test-reference", clock.now().getTimeReference());
        assertEquals(1, CREATIONS.get());
        clock.close();
        clock.close();
        assertThrows(IllegalStateException.class, clock::now);
      }
    });
  }

  @Test
  void missingDuplicateAndBrokenProvidersFailExplicitly() throws Exception {
    withProviders("", () -> assertThrows(IOException.class,
        () -> BoundedClocks.create("missing", Collections.emptyMap())));
    withProviders(TestProvider.class.getName() + "\n" + DuplicateProvider.class.getName(),
        () -> assertThrows(IOException.class, BoundedClocks::getProviderNames));
    withProviders("missing.Provider",
        () -> assertThrows(IOException.class, BoundedClocks::getProviderNames));
  }

  @Test
  void nullConfigurationIsRejectedBeforeProviderInitialization() throws Exception {
    withProviders(TestProvider.class.getName(), () -> {
      CREATIONS.set(0);
      assertThrows(NullPointerException.class, () -> BoundedClocks.create("test", null));
      final Map<String, String> invalid = new HashMap<>();
      invalid.put("key", null);
      assertThrows(NullPointerException.class, () -> BoundedClocks.create("test", invalid));
      assertEquals(0, CREATIONS.get());
    });
  }
}
