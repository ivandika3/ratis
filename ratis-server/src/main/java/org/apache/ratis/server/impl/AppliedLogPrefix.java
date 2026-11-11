/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.server.impl;

import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.exceptions.ReadException;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.Preconditions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Query-visible, contiguous committed application for state machines opting into bounded stale reads. */
final class AppliedLogPrefix {
  private static final class Pending {
    private final Long timestamp;
    private boolean completed;

    private Pending(LogEntryProto entry) {
      timestamp = entry.hasAppendTimestampMs() ? entry.getAppendTimestampMs() : null;
    }
  }

  private final NavigableMap<Long, Pending> pending = new TreeMap<>();
  private final Map<CompletableFuture<Long>, Long> waiters = new HashMap<>();
  private long index;
  private Long timestamp;
  private long generation;
  private boolean valid = true;

  AppliedLogPrefix(long index) {
    this.index = index;
  }

  synchronized long getGeneration() {
    return generation;
  }

  synchronized int getPendingCount() {
    return pending.size();
  }

  synchronized boolean canServe(long requestTime, long maxStaleness, long uncertainty, long minIndex) {
    if (!valid || timestamp == null || maxStaleness <= 0 || uncertainty < 0 || index < minIndex) {
      return false;
    }
    try {
      return Math.addExact(Math.subtractExact(requestTime, timestamp), Math.multiplyExact(2, uncertainty))
          <= maxStaleness;
    } catch (ArithmeticException e) {
      return false;
    }
  }

  void add(LogEntryProto entry, CompletableFuture<Message> applied) {
    final long currentGeneration;
    synchronized (this) {
      if (!valid) {
        return;
      }
      final long previous = pending.isEmpty() ? index : pending.lastKey();
      Preconditions.assertTrue(entry.getIndex() == previous + 1, "Noncontiguous application");
      currentGeneration = generation;
      pending.put(entry.getIndex(), new Pending(entry));
    }
    if (applied == null) {
      complete(entry.getIndex(), currentGeneration, null);
    } else {
      applied.whenComplete((message, error) -> complete(entry.getIndex(), currentGeneration, error));
    }
  }

  private void complete(long completedIndex, long expectedGeneration, Throwable error) {
    final List<CompletableFuture<Long>> ready = new ArrayList<>();
    final long appliedIndex;
    synchronized (this) {
      if (!valid || expectedGeneration != generation) {
        return;
      }
      if (error != null) {
        valid = false;
        generation++;
        timestamp = null;
        pending.clear();
        ready.addAll(waiters.keySet());
        waiters.clear();
      } else {
        pending.get(completedIndex).completed = true;
        while (!pending.isEmpty() && pending.firstEntry().getValue().completed) {
          final Map.Entry<Long, Pending> first = pending.pollFirstEntry();
          index = first.getKey();
          if (first.getValue().timestamp != null) {
            timestamp = first.getValue().timestamp;
          }
        }
        waiters.entrySet().removeIf(e -> {
          if (e.getValue() <= index) {
            ready.add(e.getKey());
            return true;
          }
          return false;
        });
      }
      appliedIndex = index;
    }
    ready.forEach(f -> {
      if (error == null) {
        f.complete(appliedIndex);
      } else {
        f.completeExceptionally(new ReadException(error));
      }
    });
  }

  synchronized CompletableFuture<Long> waitToAdvance(long required, long expectedGeneration) {
    if (!valid || generation != expectedGeneration) {
      return JavaUtils.completeExceptionally(new ReadException("Applied state was replaced or invalidated"));
    }
    if (index >= required) {
      return CompletableFuture.completedFuture(index);
    }
    final CompletableFuture<Long> future = new CompletableFuture<>();
    waiters.put(future, required);
    future.whenComplete((value, error) -> {
      synchronized (AppliedLogPrefix.this) {
        waiters.remove(future);
      }
    });
    return future;
  }

  private synchronized void checkGeneration(long expectedGeneration) {
    if (!valid || generation != expectedGeneration) {
      throw new CompletionException(new ReadException("Applied state was replaced or invalidated"));
    }
  }

  CompletableFuture<Message> query(long expectedGeneration, Supplier<CompletableFuture<Message>> query) {
    checkGeneration(expectedGeneration);
    return query.get().thenApply(message -> {
      checkGeneration(expectedGeneration);
      return message;
    });
  }

  void invalidate() {
    final List<CompletableFuture<Long>> failed;
    synchronized (this) {
      generation++;
      valid = false;
      timestamp = null;
      pending.clear();
      failed = new ArrayList<>(waiters.keySet());
      waiters.clear();
    }
    failed.forEach(f -> f.completeExceptionally(new ReadException("Applied state was replaced or invalidated")));
  }

  synchronized void reset(long snapshotIndex) {
    Preconditions.assertTrue(!valid, "Invalidate before resetting application");
    index = snapshotIndex;
    valid = true;
  }
}
