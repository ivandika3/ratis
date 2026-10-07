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
import org.apache.ratis.proto.RaftProtos.ReadRequestTypeProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.apache.ratis.util.JavaUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Opportunistically batch follower-to-leader ReadIndex requests.
 *
 * <p>Each drain captures all queued reads without waiting for a timer. Reply fanout completes
 * all members of a batch in one executor task. Up to {@code maxInFlight}
 * batches can be admitted, each retaining its slot until reply fanout finishes.
 */
class ReadIndexBatching {
  private final Executor executor;
  private final int maxInFlight;
  private final BiFunction<ClientId, ReadRequestTypeProto, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl;

  /** Guarded by {@code this}. */
  private Queue<Pending> pending = new ArrayDeque<>();
  /** Guarded by {@code this}; includes batches whose replies are waiting for or running fanout. */
  private final Set<Batch> inFlight = new HashSet<>();
  /** Guarded by {@code this}; held from scheduling a drain until that drain finishes sending. */
  private boolean drainScheduled;
  /** Guarded by {@code this}. */
  private boolean closed;

  ReadIndexBatching(Executor executor, int maxInFlight,
      BiFunction<ClientId, ReadRequestTypeProto, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl) {
    this.executor = executor;
    this.maxInFlight = maxInFlight;
    this.readIndexAsyncImpl = readIndexAsyncImpl;
  }

  CompletableFuture<ReadIndexReplyProto> submit(ClientId clientId, ReadRequestTypeProto readRequestType) {
    final CompletableFuture<ReadIndexReplyProto> future = new CompletableFuture<>();
    synchronized (this) {
      if (closed) {
        return JavaUtils.completeExceptionally(newClosedException());
      }
      pending.add(new Pending(clientId, readRequestType, future));
    }

    scheduleDrain();
    return future;
  }

  void close() {
    close(newClosedException());
  }

  private void close(Throwable throwable) {
    final Queue<Pending> queued;
    final List<Batch> running;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      drainScheduled = false;
      queued = pending;
      pending = new ArrayDeque<>();
      running = new ArrayList<>(inFlight);
      running.forEach(Batch::cancel);
      inFlight.clear();
    }
    queued.forEach(p -> p.future.completeExceptionally(throwable));
    running.forEach(batch -> batch.completeExceptionally(throwable));
  }

  private void scheduleDrain() {
    synchronized (this) {
      if (closed || pending.isEmpty() || drainScheduled || inFlight.size() >= maxInFlight) {
        return;
      }
      drainScheduled = true;
    }
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
      if (closed || pending.isEmpty() || inFlight.size() >= maxInFlight) {
        drainScheduled = false;
        return;
      }
      batch = new Batch(pending);
      pending = new ArrayDeque<>();
      inFlight.add(batch);
    }

    try {
      batch.send(readIndexAsyncImpl, (reply, throwable) -> scheduleCompletion(batch, reply, throwable));
    } finally {
      synchronized (this) {
        drainScheduled = false;
      }
      scheduleDrain();
    }
  }

  private void scheduleCompletion(Batch batch, ReadIndexReplyProto reply, Throwable throwable) {
    try {
      executor.execute(() -> complete(batch, reply, throwable));
    } catch (RejectedExecutionException e) {
      close(new ReadIndexException("Failed to schedule ReadIndex batch completion.", e));
    }
  }

  private void complete(Batch batch, ReadIndexReplyProto reply, Throwable throwable) {
    final Throwable failure = throwable == null ? null : JavaUtils.unwrapCompletionException(throwable);
    batch.complete(reply, failure);
    synchronized (this) {
      inFlight.remove(batch);
    }
    scheduleDrain();
  }

  private static class Pending {
    private final ClientId clientId;
    private final ReadRequestTypeProto readRequestType;
    private final CompletableFuture<ReadIndexReplyProto> future;

    Pending(ClientId clientId, ReadRequestTypeProto readRequestType, CompletableFuture<ReadIndexReplyProto> future) {
      this.clientId = clientId;
      this.readRequestType = readRequestType;
      this.future = future;
    }
  }

  private static class Batch {
    private enum State {
      PENDING, SENDING, COMPLETED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    /** Membership is immutable after detaching the submitting queue. */
    private final Queue<Pending> pending;

    Batch(Queue<Pending> pending) {
      this.pending = pending;
    }

    void send(BiFunction<ClientId, ReadRequestTypeProto, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl,
        BiConsumer<ReadIndexReplyProto, Throwable> completion) {
      if (pending.isEmpty()) {
        return;
      }
      // Claim dispatch atomically against cancellation; claimed sends may finish during close.
      if (!state.compareAndSet(State.PENDING, State.SENDING)) {
        return;
      }

      final CompletableFuture<ReadIndexReplyProto> replyFuture;
      try {
        // Plain reads only need one ReadIndex RPC for the batch.  Read-after-write requests
        // bypass batching before reaching this class, since their ReadIndex depends on
        // client-specific write-index state.
        final Pending first = pending.peek();
        replyFuture = readIndexAsyncImpl.apply(first.clientId, first.readRequestType);
      } catch (Throwable t) {
        completion.accept(null, t);
        return;
      }

      replyFuture.whenComplete(completion);
    }

    private void complete(ReadIndexReplyProto reply, Throwable throwable) {
      for (Pending next : pending) {
        if (state.get() == State.COMPLETED) {
          break;
        }
        if (throwable == null) {
          next.future.complete(reply);
        } else {
          next.future.completeExceptionally(throwable);
        }
      }
      state.compareAndSet(State.SENDING, State.COMPLETED);
    }

    private void cancel() {
      state.set(State.COMPLETED);
    }

    private void completeExceptionally(Throwable throwable) {
      cancel();
      // A successful fanout may be blocked in a continuation; still settle its remaining members.
      pending.forEach(p -> p.future.completeExceptionally(throwable));
    }
  }
}
