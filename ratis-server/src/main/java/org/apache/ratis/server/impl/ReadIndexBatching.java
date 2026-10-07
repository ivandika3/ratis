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
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.apache.ratis.protocol.exceptions.ResourceUnavailableException;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.ResourceSemaphore;

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
 * Opportunistically batch ReadIndex requests. Opportunistic batching is also referred to by
 * "Natural batching" or "Smart batching" in other articles / literatures (see "References").
 *
 * <p>In opportunistic batching, instead of waiting for a given batch interval or a specific batch size,
 * the sender immediately sends requests as soon as there are any pending requests.
 * This means that it can handle bursty workloads by batching the requests into a single batch which amortizes
 * the per-request latency over time. In a less busy cluster, the latency should still be minimized since
 * the sender does not wait for any batch interval or specific batch size.
 *
 * <p>
 * In the context of ReadIndex, we define a batch as a collection of pending read requests. For each batch,
 * we schedule a "drain" task that will do the following:
 * <ol>
 *   <li>
 *     Seal / close the existing batch. This means that no more requests can be added to this batch.
 *   </li>
 *   <li>
 *     Send a single ReadIndex request for the batch.
 *   </li>
 *   <li>
 *     After the ReadIndex request returns, we schedule for batch completion which delivers the
 *     RPC response or failure to all of its ReadIndex futures in a separate task.
 *   </li>
 * </ol>
 * <p>
 * To mitigate head-of-line blocking where a slow network can cause a single ReadIndex to take longer, we
 * allow multiple inflight batches (up to {@code maxInflight} batches). For example
 * <ol>
 *   <li>
 *     Batch 1: RPC 1 still pending
 *   </li>
 *   <li>
 *     Batch 2: RPC 2 succeeds with ReadIndex 120
 *   </li>
 * </ol>
 * The Batch 2 pending ReadIndex requests can be replied by ReadIndex 120 without being blocked by the earlier
 * Batch 1. It is possible to allow Batch 1 to return immediately with ReadIndex 120 without violating
 * linearizability since Batch 1 is sent before Batch 2. However, the tradeoff is that Batch 1 can have
 * a lower ReadIndex (and therefore less waiting) if the ReadIndex request returns quickly. Therefore,
 * this proposed optimizations can be considered if head-of-line blocking is a significant overhead.
 *
 * <p>
 * Note that there are a few possible caveats on enabling ReadIndex batching
 * <ol>
 *   <li>
 *     The purpose of opportunistic batching is to improve the requests throughput by reducing
 *     the average request latency, not minimizing individual request. Therefore, latency for a single
 *     read request might increase.
 *   </li>
 *   <li>
 *     Since the batch completion completes the futures all at once in a short amount of time,
 *     this can cause a bursty {@link org.apache.ratis.statemachine.StateMachine#query(Message)}
 *     which can cause higher contentions.
 *   </li>
 * </ol>
 *
 * <p>
 * Queued reads and retained batch members share an {@code elementLimit} pending limit to prevent
 * unbounded number of elements in pending batches.
 * <p>
 * References:
 * <ul>
 *   <li>
 *     <a href="https://www.vldb.org/pvldb/vol18/p2831-giortamis.pdf">
 *       The LAW theorem: Local Reads and Linearizable Asynchronous Replication</a>
 *   </li>
 *   <li>
 *     <a href="https://mechanical-sympathy.blogspot.com/2011/10/smart-batching.html">
 *       Smart Batching</a>
 *   </li>
 *   <li>
 *     <a href="https://martinfowler.com/articles/mechanical-sympathy-principles.html">
 *       Principles of Mechanical Sympathy</a>
 *   </li>
 * </ul>
 */
class ReadIndexBatching {
  private final Executor executor;
  private final ResourceSemaphore resource;
  private final int maxInFlight;
  private final BiFunction<ClientId, ReadRequestTypeProto, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl;

  /** Guarded by {@code this}. */
  private Queue<Pending> pending = new ArrayDeque<>();
  /** Guarded by {@code this}; includes batches waiting for RPC replies or batch completion. */
  private final Set<Batch> inFlight = new HashSet<>();
  /** Guarded by {@code this}; held from scheduling a drain until that drain finishes sending. */
  private boolean drainScheduled;
  /** Guarded by {@code this}. */
  private boolean closed;

  ReadIndexBatching(Executor executor, int elementLimit, int maxInFlight,
      BiFunction<ClientId, ReadRequestTypeProto, CompletableFuture<ReadIndexReplyProto>> readIndexAsyncImpl) {
    this.executor = executor;
    this.resource = new ResourceSemaphore(elementLimit);
    this.maxInFlight = maxInFlight;
    this.readIndexAsyncImpl = readIndexAsyncImpl;
  }

  CompletableFuture<ReadIndexReplyProto> submit(ClientId clientId, ReadRequestTypeProto readRequestType) {
    final CompletableFuture<ReadIndexReplyProto> future;
    synchronized (this) {
      if (closed) {
        return JavaUtils.completeExceptionally(newClosedException());
      }
      if (!resource.tryAcquire()) {
        return JavaUtils.completeExceptionally(new ResourceUnavailableException(
            "Failed to acquire a ReadIndex request: element limit reached (" + resource + ")."));
      }
      future = new CompletableFuture<>();
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
      // Drop ownership before settling futures; late completion tasks must not return permits again.
      resource.release(resource.used());
      resource.close();
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
      if (inFlight.remove(batch)) {
        resource.release(batch.pending.size());
      }
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
        // Plain ReadIndex calculation is client-independent, so use the first request's clientId.
        // Read-after-write requests depend on client-specific write state and bypass batching.
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
      pending.forEach(p -> p.future.completeExceptionally(throwable));
    }
  }
}
