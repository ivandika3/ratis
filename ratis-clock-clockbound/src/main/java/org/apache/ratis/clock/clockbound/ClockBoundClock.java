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

import com.sun.jna.Pointer;
import org.apache.ratis.clock.BoundedClock;
import org.apache.ratis.clock.ClockReading;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Per-calling-thread contexts, protected from teardown by the clock's lifecycle lock. */
final class ClockBoundClock implements BoundedClock {
  private final ClockBoundNative library;
  private final String reference;
  private final String sharedMemoryPath;
  private final String vmClockPath;
  private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
  private final Map<Thread, Context> contexts = new HashMap<>();
  private boolean closed;

  ClockBoundClock(ClockBoundNative library, String reference, String sharedMemoryPath, String vmClockPath)
      throws IOException {
    this.library = library;
    this.reference = reference;
    this.sharedMemoryPath = sharedMemoryPath;
    this.vmClockPath = vmClockPath;
    contexts.put(Thread.currentThread(), new Context());
  }

  private Context context() throws IOException {
    synchronized (contexts) {
      final Thread thread = Thread.currentThread();
      Context context = contexts.get(thread);
      if (context == null) {
        // Reclaim contexts of terminated callers rather than retaining every historical thread until shutdown.
        final Iterator<Map.Entry<Thread, Context>> iterator = contexts.entrySet().iterator();
        while (iterator.hasNext()) {
          final Map.Entry<Thread, Context> entry = iterator.next();
          if (!entry.getKey().isAlive()) {
            iterator.remove();
            entry.getValue().close();
          }
        }
        context = new Context();
        contexts.put(thread, context);
      }
      return context;
    }
  }

  @Override
  public ClockReading now() {
    lifecycle.readLock().lock();
    try {
      if (closed) {
        throw new IllegalStateException("Bounded clock is closed");
      }
      try {
        return context().read();
      } catch (IOException | RuntimeException e) {
        return ClockReading.unavailable(reference, ClockReading.UnavailableReason.PROVIDER_ERROR, e.toString());
      }
    } finally {
      lifecycle.readLock().unlock();
    }
  }

  @Override
  public void close() throws IOException {
    lifecycle.writeLock().lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      IOException failure = null;
      for (Context context : contexts.values()) {
        try {
          context.close();
        } catch (IOException e) {
          if (failure == null) {
            failure = e;
          } else {
            failure.addSuppressed(e);
          }
        }
      }
      contexts.clear();
      if (failure != null) {
        throw failure;
      }
    } finally {
      lifecycle.writeLock().unlock();
    }
  }

  private final class Context {
    private final Pointer pointer;
    private final ClockBoundNative.Result result = new ClockBoundNative.Result();
    private final ClockBoundNative.Error error = new ClockBoundNative.Error();

    private Context() throws IOException {
      pointer = library.openWith(sharedMemoryPath, vmClockPath, error);
      if (pointer == null) {
        throw new IOException("Cannot open ClockBound shared memory: " + error.describe());
      }
      try {
        final ClockReading probe = read();
        if (probe.getUnavailableReason().orElse(null) == ClockReading.UnavailableReason.PROVIDER_ERROR) {
          throw new IOException(probe.getDescription());
        }
      } catch (IOException | RuntimeException e) {
        try {
          close();
        } catch (IOException closing) {
          e.addSuppressed(closing);
        }
        throw e;
      }
    }

    private ClockReading read() throws IOException {
      if (library.now(pointer, result, error) != null) {
        throw new IOException("ClockBound read failed: " + error.describe());
      }
      return result.toReading(reference);
    }

    private void close() throws IOException {
      try {
        if (library.close(pointer, error) != null) {
          throw new IOException("ClockBound close failed: " + error.describe());
        }
      } catch (RuntimeException e) {
        throw new IOException("Cannot close ClockBound context", e);
      }
    }
  }
}
