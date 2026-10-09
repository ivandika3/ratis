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
 * Opportunistically batches ReadIndex requests, also known as natural batching or smart batching
 * (see "References"). The caller batches only plain follower reads; read-after-write requests and
 * leader-local reads bypass batching.
 *
 * <p>Pending reads schedule a drain when an in-flight batch slot is available. There is no deliberate
 * batching interval or minimum batch size, but dispatch can still wait for executor availability or
 * a batch slot. Batching amortizes RPC costs across the reads collected before a drain runs; it does
 * not guarantee lower read latency.
 *
 * <p>A batch is a collection of pending read requests with immutable membership. Formation and dispatch
 * are serialized by one logical drain at a time:
 * <ol>
 *   <li>Detach all currently pending reads into a batch. Later arrivals belong to a subsequent batch.</li>
 *   <li>Send one asynchronous ReadIndex request for the batch.</li>
 *   <li>When the request completes, schedule a separate task on the same dedicated executor to deliver
 *     its response or failure sequentially to the batch's ReadIndex futures.</li>
 * </ol>
 *
 * <p>Up to {@code maxInFlight} batches may overlap. A batch retains its slot until its completion task
 * finishes, including synchronous callbacks triggered by completing its futures. A later batch can
 * complete independently of an earlier delayed RPC; its response is not reused to complete other batches.
 * This concurrency does not isolate batches from delays in a shared transport.
 *
 * <p>Caveats:
 * <ol>
 *   <li>Size-one batches add bookkeeping and executor handoffs without reducing the number of RPCs.
 *     Increasing {@code maxInFlight} can reduce batching efficiency and increase leader and network work.</li>
 *   <li>All members share their batch's RPC delay or failure. With no free batch slots, subsequent reads
 *     remain queued until a batch finishes completing.</li>
 *   <li>Future completion is sequential, not atomic, and synchronous callbacks can occupy an executor
 *     worker for a long time, delaying other drain or completion tasks.</li>
 *   <li>Each read still waits for the local applied index before querying the state machine. Reads sharing
 *     an index may cause bursts of
 *     {@link org.apache.ratis.statemachine.StateMachine#query(org.apache.ratis.protocol.Message)} calls,
 *     either during batch completion when the index is already applied or later on the thread advancing
 *     the applied index.</li>
 *   <li>No deadline is attached to a queued read at submission. Cancelling an individual future does not
 *     remove its entry or release its admission capacity.</li>
 * </ol>
 *
 * <p>Queued reads and retained batch members share an {@code elementLimit} admission limit. Excess incoming
 * reads are rejected with {@link ResourceUnavailableException}. Capacity is released after full batch
 * completion or when batching is closed. This bounds entries retained by the batching stage, not downstream
 * applied-index waits, query concurrency, bytes, or read QPS, and does not provide per-client fairness.
 *
 * <p>References:
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
