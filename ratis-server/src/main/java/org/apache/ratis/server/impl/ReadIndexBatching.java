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

import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.apache.ratis.util.JavaUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Opportunistically batch follower-to-leader ReadIndex requests.
 *
 * <p>The batch is drained on the server executor without waiting for a timer. {@code batchSize}
 * is only a maximum drain cap, not a target size.
 */
class ReadIndexBatching {
  private final Executor executor;
  private final int batchSize;
  private final Function<RaftClientRequest, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl;

  /** Guarded by {@code this}. */
  private final Queue<Pending> pending = new ArrayDeque<>();
  /** Guarded by {@code this}; owns the only ReadIndex RPC slot until its reply fanout completes. */
  private Batch inFlight;
  /** Guarded by {@code this}; prevents duplicate drain tasks before one claims pending requests. */
  private boolean drainScheduled;
  /** Guarded by {@code this}. */
  private boolean closed;

  ReadIndexBatching(Executor executor, int batchSize,
      Function<RaftClientRequest, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl) {
    this.executor = executor;
    this.batchSize = batchSize;
    this.readIndexAsyncImpl = readIndexAsyncImpl;
  }

  CompletableFuture<ReadIndexReplyProto> submit(RaftClientRequest request) {
    final CompletableFuture<ReadIndexReplyProto> future = new CompletableFuture<>();
    final boolean schedule;
    synchronized (this) {
      if (closed) {
        return JavaUtils.completeExceptionally(newClosedException());
      }
      pending.add(new Pending(request, future));
      schedule = inFlight == null && !drainScheduled;
      if (schedule) {
        drainScheduled = true;
      }
    }

    if (schedule) {
      scheduleDrain();
    }
    return future;
  }

  void close() {
    close(newClosedException());
  }

  private void close(Throwable throwable) {
    final List<Pending> queued;
    final Batch running;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      drainScheduled = false;
      queued = new ArrayList<>(pending);
      pending.clear();
      running = inFlight;
      inFlight = null;
    }
    queued.forEach(p -> p.future.completeExceptionally(throwable));
    if (running != null) {
      running.completeExceptionally(throwable);
    }
  }

  private void scheduleDrain() {
    try {
      executor.execute(this::drain);
    } catch (RejectedExecutionException e) {
      close(new ReadIndexException("Failed to schedule ReadIndex batch drain.", e));
    }
  }

  private static ReadIndexException newClosedException() {
    return new ReadIndexException("ReadIndex batching is closed.");
  }

  private void drain() {
    final Batch batch;
    synchronized (this) {
      if (closed || pending.isEmpty() || inFlight != null) {
        drainScheduled = false;
        return;
      }
      batch = pollBatch();
      inFlight = batch;
      drainScheduled = false;
    }

    batch.send(readIndexAsyncImpl, (reply, throwable) -> scheduleCompletion(batch, reply, throwable));
  }

  private Batch pollBatch() {
    final List<Pending> batch = new ArrayList<>(Math.min(batchSize, pending.size()));
    for (int i = 0; i < batchSize; i++) {
      final Pending next = pending.poll();
      if (next == null) {
        break;
      }
      batch.add(next);
    }
    return new Batch(batch);
  }

  private void scheduleCompletion(Batch batch, ReadIndexReplyProto reply, Throwable throwable) {
    try {
      executor.execute(() -> complete(batch, reply, throwable));
    } catch (RejectedExecutionException e) {
      close(new ReadIndexException("Failed to schedule ReadIndex batch completion.", e));
    }
  }

  private void complete(Batch batch, ReadIndexReplyProto reply, Throwable throwable) {
    if (throwable != null) {
      batch.completeExceptionally(JavaUtils.unwrapCompletionException(throwable));
    } else {
      batch.complete(reply);
    }

    final boolean schedule;
    synchronized (this) {
      if (inFlight == batch) {
        inFlight = null;
      }
      schedule = !closed && !pending.isEmpty() && !drainScheduled;
      if (schedule) {
        drainScheduled = true;
      }
    }
    if (schedule) {
      scheduleDrain();
    }
  }

  private static class Pending {
    private final RaftClientRequest request;
    private final CompletableFuture<ReadIndexReplyProto> future;

    Pending(RaftClientRequest request, CompletableFuture<ReadIndexReplyProto> future) {
      this.request = request;
      this.future = future;
    }
  }

  private static class Batch {
    private final AtomicBoolean completed = new AtomicBoolean();
    private final List<Pending> pending;

    Batch(List<Pending> pending) {
      this.pending = pending;
    }

    void send(Function<RaftClientRequest, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl,
        BiConsumer<ReadIndexReplyProto, Throwable> completion) {
      if (pending.isEmpty()) {
        return;
      }
      if (completed.get()) {
        return;
      }

      final CompletableFuture<ReadIndexReplyProto> replyFuture;
      try {
        // Plain reads only need one ReadIndex RPC for the batch.  Read-after-write requests
        // bypass batching before reaching this class, since their client request carries
        // per-client write-index state.
        replyFuture = readIndexAsyncImpl.apply(pending.get(0).request);
      } catch (Throwable t) {
        completion.accept(null, t);
        return;
      }

      replyFuture.whenComplete(completion);
    }

    private void complete(ReadIndexReplyProto reply) {
      if (completed.compareAndSet(false, true)) {
        pending.forEach(p -> p.future.complete(reply));
      }
    }

    private void completeExceptionally(Throwable throwable) {
      if (completed.compareAndSet(false, true)) {
        pending.forEach(p -> p.future.completeExceptionally(throwable));
      }
    }
  }
}
