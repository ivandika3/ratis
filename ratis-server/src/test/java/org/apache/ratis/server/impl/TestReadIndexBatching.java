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

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadRequestTypeProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class TestReadIndexBatching {
  @Test
  void testThreadPoolSizeConfiguration() {
    final RaftProperties properties = new RaftProperties();
    Assertions.assertEquals("raft.server.read.read-index.batch.threadpool.size",
        RaftServerConfigKeys.Read.ReadIndex.Batch.THREAD_POOL_SIZE_KEY);
    Assertions.assertEquals(2, RaftServerConfigKeys.Read.ReadIndex.Batch.threadPoolSize(properties));
    for (int size : new int[]{1, 2, 4}) {
      RaftServerConfigKeys.Read.ReadIndex.Batch.setThreadPoolSize(properties, size);
      Assertions.assertEquals(size, RaftServerConfigKeys.Read.ReadIndex.Batch.threadPoolSize(properties));
    }
    for (int invalid : new int[]{0, -1}) {
      Assertions.assertThrows(IllegalArgumentException.class,
          () -> RaftServerConfigKeys.Read.ReadIndex.Batch.setThreadPoolSize(properties, invalid));
      properties.setInt(RaftServerConfigKeys.Read.ReadIndex.Batch.THREAD_POOL_SIZE_KEY, invalid);
      Assertions.assertThrows(IllegalArgumentException.class,
          () -> RaftServerConfigKeys.Read.ReadIndex.Batch.threadPoolSize(properties));
    }
  }

  @Test
  void testMaxInFlightConfiguration() {
    final RaftProperties properties = new RaftProperties();
    Assertions.assertFalse(RaftServerConfigKeys.Read.ReadIndex.Batch.enabled(properties));
    Assertions.assertEquals(1, RaftServerConfigKeys.Read.ReadIndex.Batch.maxInFlight(properties));

    RaftServerConfigKeys.Read.ReadIndex.Batch.setMaxInFlight(properties, 4);
    Assertions.assertEquals(4, RaftServerConfigKeys.Read.ReadIndex.Batch.maxInFlight(properties));
    for (int invalid : new int[]{0, -1}) {
      Assertions.assertThrows(IllegalArgumentException.class,
          () -> RaftServerConfigKeys.Read.ReadIndex.Batch.setMaxInFlight(properties, invalid));
      properties.setInt(RaftServerConfigKeys.Read.ReadIndex.Batch.MAX_IN_FLIGHT_KEY, invalid);
      Assertions.assertThrows(IllegalArgumentException.class,
          () -> RaftServerConfigKeys.Read.ReadIndex.Batch.maxInFlight(properties));
    }
  }

  @Test
  void testSubmitSchedulesOneOpportunisticDrain() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexReplyProto readIndexReply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(
        executor, 1, (clientId, readRequestType) -> {
          readIndexCount.incrementAndGet();
          return CompletableFuture.completedFuture(readIndexReply);
        });

    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    Assertions.assertFalse(first.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);
    Assertions.assertFalse(second.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertEquals(1, readIndexCount.get());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertSame(readIndexReply, first.get());
    Assertions.assertSame(readIndexReply, second.get());
  }

  @Test
  void testLargeSnapshotUsesOneRpcAndOneCompletionTask() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexReplyProto readIndexReply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(
        executor, 1, (clientId, readRequestType) -> {
          readIndexCount.incrementAndGet();
          return CompletableFuture.completedFuture(readIndexReply);
        });

    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 1024; i++) {
      replies.add(batching.submit(null, null));
    }

    executor.runNext();
    Assertions.assertEquals(1, readIndexCount.get());
    Assertions.assertEquals(1, executor.getTaskCount());
    executor.runNext();
    Assertions.assertEquals(1024, replies.stream().filter(CompletableFuture::isDone).count());
    Assertions.assertEquals(1, readIndexCount.get());
    for (CompletableFuture<ReadIndexReplyProto> reply : replies) {
      Assertions.assertSame(readIndexReply, reply.get(5, TimeUnit.SECONDS));
    }
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testSnapshotAllUsesOneRpcAndCompletesFrozenMembership() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexReplyProto firstReply = ReadIndexReplyProto.newBuilder().setReadIndex(10).build();
    final ReadIndexReplyProto nextReply = ReadIndexReplyProto.newBuilder().setReadIndex(20).build();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, (clientId, type) ->
        CompletableFuture.completedFuture(readIndexCount.incrementAndGet() == 1 ? firstReply : nextReply));
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      replies.add(batching.submit(null, null));
    }
    executor.runNext();
    Assertions.assertEquals(1, readIndexCount.get());
    final CompletableFuture<ReadIndexReplyProto> later = batching.submit(null, null);
    final AtomicInteger otherWork = new AtomicInteger();
    executor.execute(otherWork::incrementAndGet);

    executor.runNext();
    for (CompletableFuture<ReadIndexReplyProto> reply : replies) {
      Assertions.assertSame(firstReply, reply.get(5, TimeUnit.SECONDS));
    }
    Assertions.assertFalse(later.isDone());
    Assertions.assertEquals(1, readIndexCount.get());
    Assertions.assertEquals(0, otherWork.get());
    executor.runNext();
    Assertions.assertEquals(1, otherWork.get());
    Assertions.assertFalse(later.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());
    executor.runNext();
    Assertions.assertEquals(2, readIndexCount.get());
    executor.runNext();
    Assertions.assertSame(nextReply, later.get(5, TimeUnit.SECONDS));
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testWaitsForInFlightBatchAndOffloadsCompletion() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final CompletableFuture<ReadIndexReplyProto> firstReadIndex = new CompletableFuture<>();
    final ReadIndexReplyProto readIndexReply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(
        executor, 1, (clientId, readRequestType) -> {
          if (readIndexCount.getAndIncrement() == 0) {
            return firstReadIndex;
          }
          return CompletableFuture.completedFuture(readIndexReply);
        });

    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);

    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> third = batching.submit(null, null);
    Assertions.assertEquals(1, readIndexCount.get());
    Assertions.assertEquals(0, executor.getTaskCount());

    firstReadIndex.complete(readIndexReply);
    Assertions.assertFalse(first.isDone());
    Assertions.assertFalse(second.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertSame(readIndexReply, first.get());
    Assertions.assertSame(readIndexReply, second.get());
    Assertions.assertFalse(third.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertEquals(2, readIndexCount.get());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertSame(readIndexReply, third.get());
  }

  @Test
  void testReadIndexFailureCompletesBatchFuturesExceptionally() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final RuntimeException failure = new RuntimeException("read index failed");
    final CompletableFuture<ReadIndexReplyProto> failed = new CompletableFuture<>();
    failed.completeExceptionally(failure);
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, (clientId, readRequestType) -> failed);

    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);

    executor.runNext();
    Assertions.assertFalse(first.isDone());
    Assertions.assertFalse(second.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertSame(failure,
        Assertions.assertThrows(ExecutionException.class, first::get).getCause());
    Assertions.assertSame(failure,
        Assertions.assertThrows(ExecutionException.class, second::get).getCause());
  }

  @Test
  void testReadIndexFailureCompletesSnapshotInOneTask() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final RuntimeException failure = new RuntimeException("read index failed");
    final CompletableFuture<ReadIndexReplyProto> failed = new CompletableFuture<>();
    failed.completeExceptionally(new CompletionException(failure));
    final ReadIndexReplyProto reply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, (clientId, type) ->
        readIndexCount.incrementAndGet() == 1 ? failed : CompletableFuture.completedFuture(reply));
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      replies.add(batching.submit(null, null));
    }
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> later = batching.submit(null, null);

    Assertions.assertEquals(1, executor.getTaskCount());
    executor.runNext();
    for (CompletableFuture<ReadIndexReplyProto> future : replies) {
      Assertions.assertSame(failure,
          Assertions.assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause());
    }
    Assertions.assertEquals(1, readIndexCount.get());
    Assertions.assertFalse(later.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());
    executor.runNext();
    Assertions.assertEquals(2, readIndexCount.get());
    executor.runNext();
    Assertions.assertSame(reply, later.get(5, TimeUnit.SECONDS));
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testCloseFromReplyContinuationCancelsRemainingMembers() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexReplyProto reply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, (clientId, type) -> {
      readIndexCount.incrementAndGet();
      return CompletableFuture.completedFuture(reply);
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      replies.add(batching.submit(null, null));
    }
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> later = batching.submit(null, null);
    final CompletableFuture<Void> continuation = replies.get(0).thenRun(batching::close);
    executor.runNext();
    continuation.get(5, TimeUnit.SECONDS);

    Assertions.assertSame(reply, replies.get(0).get(5, TimeUnit.SECONDS));
    for (int i = 1; i < replies.size(); i++) {
      assertReadIndexException(replies.get(i));
    }
    assertReadIndexException(later);
    Assertions.assertEquals(1, readIndexCount.get());
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testNextDrainScheduleFailureAfterFanoutClosesPendingBatches() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final List<CompletableFuture<ReadIndexReplyProto>> rpcs = new ArrayList<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, type) -> {
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      replies.add(batching.submit(null, null));
    }
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> inFlight = batching.submit(null, null);
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> queued = batching.submit(null, null);
    final CompletableFuture<Void> continuation = replies.get(0).thenRun(executor::rejectNewTasks);
    final ReadIndexReplyProto reply = ReadIndexReplyProto.getDefaultInstance();
    rpcs.get(0).complete(reply);
    executor.runNext();
    continuation.get(5, TimeUnit.SECONDS);

    for (CompletableFuture<ReadIndexReplyProto> future : replies) {
      Assertions.assertSame(reply, future.get(5, TimeUnit.SECONDS));
    }
    assertReadIndexException(inFlight);
    assertReadIndexException(queued);
    assertReadIndexException(batching.submit(null, null));
    rpcs.get(1).complete(reply);
    Assertions.assertEquals(2, rpcs.size());
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  void testCloseCompletesQueuedAndInFlightBatchesExceptionally(int maxInFlight) throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final List<CompletableFuture<ReadIndexReplyProto>> rpcs = new ArrayList<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, maxInFlight, (clientId, readRequestType) -> {
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < maxInFlight; i++) {
      replies.add(batching.submit(null, null));
      replies.add(batching.submit(null, null));
      executor.runNext();
    }
    replies.add(batching.submit(null, null));
    replies.add(batching.submit(null, null));
    Assertions.assertEquals(maxInFlight, rpcs.size());

    rpcs.get(0).complete(ReadIndexReplyProto.getDefaultInstance());
    Assertions.assertEquals(1, executor.getTaskCount());
    Assertions.assertFalse(replies.get(0).isDone());
    batching.close();
    batching.close();
    for (CompletableFuture<ReadIndexReplyProto> reply : replies) {
      assertReadIndexException(reply);
    }

    rpcs.forEach(rpc -> rpc.complete(ReadIndexReplyProto.getDefaultInstance()));
    while (executor.getTaskCount() > 0) {
      executor.runNext();
    }
    Assertions.assertEquals(maxInFlight, rpcs.size());
    for (CompletableFuture<ReadIndexReplyProto> reply : replies) {
      assertReadIndexException(reply);
    }
    assertReadIndexException(batching.submit(null, null));
  }

  @Test
  void testScheduleFailureClosesBatching() throws Exception {
    final ReadIndexBatching batching = new ReadIndexBatching(
        command -> {
          throw new RejectedExecutionException("closed");
        }, 1, (clientId, readRequestType) -> CompletableFuture.completedFuture(ReadIndexReplyProto.getDefaultInstance()));

    final CompletableFuture<ReadIndexReplyProto> rejected = batching.submit(null, null);
    assertReadIndexException(rejected);

    final CompletableFuture<ReadIndexReplyProto> afterClose = batching.submit(null, null);
    assertReadIndexException(afterClose);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  void testCompletionScheduleFailureClosesBatching(int maxInFlight) throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final CompletableFuture<ReadIndexReplyProto> readIndexFuture = new CompletableFuture<>();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, maxInFlight, (clientId, readRequestType) -> {
      readIndexCount.incrementAndGet();
      return readIndexFuture;
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < maxInFlight; i++) {
      replies.add(batching.submit(null, null));
      executor.runNext();
    }
    replies.add(batching.submit(null, null));
    Assertions.assertEquals(maxInFlight, readIndexCount.get());

    executor.rejectNewTasks();
    readIndexFuture.complete(ReadIndexReplyProto.getDefaultInstance());

    for (CompletableFuture<ReadIndexReplyProto> reply : replies) {
      assertReadIndexException(reply);
    }
    Assertions.assertEquals(maxInFlight, readIndexCount.get());
    assertReadIndexException(batching.submit(null, null));
  }

  @Test
  void testSubmitAfterCloseCompletesExceptionally() {
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexBatching batching = new ReadIndexBatching(
        Runnable::run, 1, (clientId, readRequestType) -> {
          readIndexCount.incrementAndGet();
          return new CompletableFuture<ReadIndexReplyProto>();
        });

    batching.close();

    final CompletableFuture<ReadIndexReplyProto> reply = batching.submit(null, null);
    final ExecutionException e = Assertions.assertThrows(ExecutionException.class, reply::get);
    Assertions.assertTrue(e.getCause() instanceof ReadIndexException);
    Assertions.assertEquals(0, readIndexCount.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4})
  void testBoundsInFlightBatchesAndCompletesOutOfOrder(int maxInFlight) throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final List<ClientId> sent = new ArrayList<>();
    final ReadRequestTypeProto readRequestType = RaftClientRequest.readRequestType().getRead();
    final List<CompletableFuture<ReadIndexReplyProto>> rpcs = new ArrayList<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, maxInFlight, (clientId, type) -> {
      sent.add(clientId);
      Assertions.assertSame(readRequestType, type);
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final List<ClientId> clientIds = new ArrayList<>();
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i <= maxInFlight; i++) {
      for (int j = 0; j < 2; j++) {
        final ClientId clientId = ClientId.randomId();
        clientIds.add(clientId);
        replies.add(batching.submit(clientId, readRequestType));
      }
      if (i < maxInFlight) {
        Assertions.assertEquals(1, executor.getTaskCount());
        executor.runNext();
        Assertions.assertEquals(i + 1, rpcs.size());
        Assertions.assertSame(clientIds.get(2 * i), sent.get(i));
      }
    }
    Assertions.assertEquals(0, executor.getTaskCount());

    final ReadIndexReplyProto laterReply = ReadIndexReplyProto.newBuilder().setReadIndex(10L * maxInFlight).build();
    rpcs.get(maxInFlight - 1).complete(laterReply);
    Assertions.assertFalse(replies.get(2 * (maxInFlight - 1)).isDone());
    Assertions.assertEquals(maxInFlight, rpcs.size());
    executor.runNext();
    Assertions.assertSame(laterReply, replies.get(2 * (maxInFlight - 1)).get(5, TimeUnit.SECONDS));
    Assertions.assertSame(laterReply, replies.get(2 * (maxInFlight - 1) + 1).get(5, TimeUnit.SECONDS));
    Assertions.assertFalse(replies.get(0).isDone());

    executor.runNext();
    Assertions.assertEquals(maxInFlight + 1, rpcs.size());
    Assertions.assertSame(clientIds.get(2 * maxInFlight), sent.get(maxInFlight));
    Assertions.assertEquals(0, executor.getTaskCount());

    for (int i = 0; i <= maxInFlight; i++) {
      if (i == maxInFlight - 1) {
        continue;
      }
      final ReadIndexReplyProto reply = ReadIndexReplyProto.newBuilder().setReadIndex(10L * (i + 1)).build();
      rpcs.get(i).complete(reply);
      executor.runNext();
      Assertions.assertSame(reply, replies.get(2 * i).get(5, TimeUnit.SECONDS));
      Assertions.assertSame(reply, replies.get(2 * i + 1).get(5, TimeUnit.SECONDS));
    }
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testRetainsSlotDuringReplyFanout() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final List<CompletableFuture<ReadIndexReplyProto>> rpcs = new ArrayList<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, readRequestType) -> {
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      replies.add(batching.submit(null, null));
    }
    executor.runNext();
    replies.add(batching.submit(null, null));
    replies.add(batching.submit(null, null));
    executor.runNext();
    replies.add(batching.submit(null, null));
    Assertions.assertEquals(2, rpcs.size());

    final CompletableFuture<Void> continuation = replies.get(0).thenRun(() -> {
      Assertions.assertFalse(replies.get(1).isDone());
      replies.add(batching.submit(null, null));
      Assertions.assertEquals(0, executor.getTaskCount());
      Assertions.assertEquals(2, rpcs.size());
    });
    final ReadIndexReplyProto firstReply = ReadIndexReplyProto.newBuilder().setReadIndex(10).build();
    rpcs.get(0).complete(firstReply);
    executor.runNext();
    continuation.get(5, TimeUnit.SECONDS);
    Assertions.assertSame(firstReply, replies.get(0).get(5, TimeUnit.SECONDS));
    Assertions.assertSame(firstReply, replies.get(1).get(5, TimeUnit.SECONDS));
    Assertions.assertSame(firstReply, replies.get(2).get(5, TimeUnit.SECONDS));
    Assertions.assertFalse(replies.get(6).isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    Assertions.assertEquals(2, rpcs.size());
    executor.runNext();
    Assertions.assertEquals(3, rpcs.size());
    for (int i = 1; i < 3; i++) {
      final ReadIndexReplyProto reply = ReadIndexReplyProto.newBuilder().setReadIndex(10 + i).build();
      rpcs.get(i).complete(reply);
      executor.runNext();
      Assertions.assertSame(reply, replies.get(2 * i + 1).get(5, TimeUnit.SECONDS));
      Assertions.assertSame(reply, replies.get(2 * i + 2).get(5, TimeUnit.SECONDS));
    }
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testSubmitDuringSendDoesNotScheduleAnotherDrain() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final CompletableFuture<Void> sending = new CompletableFuture<>();
    final CompletableFuture<Void> resume = new CompletableFuture<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, readRequestType) -> {
      if (readIndexCount.incrementAndGet() == 1) {
        sending.complete(null);
        resume.join();
      }
      return new CompletableFuture<>();
    });
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    final CompletableFuture<Void> drain = CompletableFuture.runAsync(executor::runNext);
    try {
      sending.get(5, TimeUnit.SECONDS);
      final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);
      Assertions.assertEquals(0, executor.getTaskCount());
      Assertions.assertEquals(1, readIndexCount.get());

      resume.complete(null);
      drain.get(5, TimeUnit.SECONDS);
      Assertions.assertEquals(1, executor.getTaskCount());
      executor.runNext();
      Assertions.assertEquals(2, readIndexCount.get());
      Assertions.assertFalse(first.isDone());
      Assertions.assertFalse(second.isDone());
      Assertions.assertEquals(0, executor.getTaskCount());
    } finally {
      resume.complete(null);
      drain.get(5, TimeUnit.SECONDS);
      batching.close();
    }
    assertReadIndexException(first);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testReadIndexFailuresAreBatchLocal(boolean synchronousFailure) throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final CompletableFuture<ReadIndexReplyProto> firstRpc = new CompletableFuture<>();
    final RuntimeException failure = new RuntimeException("read index failed");
    final ReadIndexReplyProto success = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, readRequestType) -> {
      final int count = readIndexCount.incrementAndGet();
      if (count == 1) {
        return firstRpc;
      } else if (count == 2) {
        if (synchronousFailure) {
          throw failure;
        }
        final CompletableFuture<ReadIndexReplyProto> failed = new CompletableFuture<>();
        failed.completeExceptionally(failure);
        return failed;
      }
      return CompletableFuture.completedFuture(success);
    });
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> third = batching.submit(null, null);
    Assertions.assertEquals(2, readIndexCount.get());
    Assertions.assertFalse(second.isDone());

    executor.runNext();
    Assertions.assertSame(failure,
        Assertions.assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause());
    Assertions.assertFalse(first.isDone());
    Assertions.assertFalse(third.isDone());
    executor.runNext();
    Assertions.assertEquals(3, readIndexCount.get());
    executor.runNext();
    Assertions.assertSame(success, third.get(5, TimeUnit.SECONDS));
    Assertions.assertFalse(first.isDone());

    firstRpc.complete(success);
    executor.runNext();
    Assertions.assertSame(success, first.get(5, TimeUnit.SECONDS));
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testCloseBeforeScheduledDrainDoesNotSend() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, readRequestType) -> {
      readIndexCount.incrementAndGet();
      return new CompletableFuture<>();
    });
    final CompletableFuture<ReadIndexReplyProto> reply = batching.submit(null, null);
    batching.close();
    executor.runNext();
    assertReadIndexException(reply);
    Assertions.assertEquals(0, readIndexCount.get());
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testNextDrainScheduleFailureClosesBatching() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final CompletableFuture<Void> sending = new CompletableFuture<>();
    final CompletableFuture<Void> resume = new CompletableFuture<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, readRequestType) -> {
      readIndexCount.incrementAndGet();
      sending.complete(null);
      resume.join();
      return new CompletableFuture<>();
    });
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    final CompletableFuture<Void> drain = CompletableFuture.runAsync(executor::runNext);
    try {
      sending.get(5, TimeUnit.SECONDS);
      final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);
      executor.rejectNewTasks();
      resume.complete(null);
      drain.get(5, TimeUnit.SECONDS);
      assertReadIndexException(first);
      assertReadIndexException(second);
      assertReadIndexException(batching.submit(null, null));
      Assertions.assertEquals(1, readIndexCount.get());
    } finally {
      resume.complete(null);
      drain.get(5, TimeUnit.SECONDS);
      batching.close();
    }
  }

  @Test
  void testCloseDuringReplyFanoutSettlesRemainingMembers() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, (clientId, readRequestType) -> rpc);
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> queued = batching.submit(null, null);

    final CountDownLatch completing = new CountDownLatch(1);
    final CompletableFuture<Void> resume = new CompletableFuture<>();
    final CompletableFuture<Void> continuation = first.thenRun(() -> {
      completing.countDown();
      resume.join();
    });
    final ReadIndexReplyProto reply = ReadIndexReplyProto.getDefaultInstance();
    rpc.complete(reply);
    final CompletableFuture<Void> fanout = CompletableFuture.runAsync(executor::runNext);
    try {
      Assertions.assertTrue(completing.await(5, TimeUnit.SECONDS));
      batching.close();
      Assertions.assertSame(reply, first.get(5, TimeUnit.SECONDS));
      assertReadIndexException(second);
      assertReadIndexException(queued);
    } finally {
      resume.complete(null);
      fanout.get(5, TimeUnit.SECONDS);
      continuation.get(5, TimeUnit.SECONDS);
      batching.close();
    }
    assertReadIndexException(second);
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testCloseDuringSendDoesNotWaitForSender() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final CountDownLatch sending = new CountDownLatch(1);
    final CompletableFuture<Void> resume = new CompletableFuture<>();
    final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, readRequestType) -> {
      readIndexCount.incrementAndGet();
      sending.countDown();
      resume.join();
      return rpc;
    });
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);
    final CompletableFuture<Void> drain = CompletableFuture.runAsync(executor::runNext);
    try {
      Assertions.assertTrue(sending.await(5, TimeUnit.SECONDS));
      batching.close();
      assertReadIndexException(first);
      assertReadIndexException(second);
    } finally {
      resume.complete(null);
      drain.get(5, TimeUnit.SECONDS);
      batching.close();
    }
    rpc.complete(ReadIndexReplyProto.getDefaultInstance());
    while (executor.getTaskCount() > 0) {
      executor.runNext();
    }
    Assertions.assertEquals(1, readIndexCount.get());
    assertReadIndexException(first);
    assertReadIndexException(second);
  }

  @Test
  void testRequestAfterRpcStartsBelongsToAnotherBatch() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final List<CompletableFuture<ReadIndexReplyProto>> rpcs = new ArrayList<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, (clientId, readRequestType) -> {
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null, null);
    executor.runNext();
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null, null);
    executor.runNext();
    Assertions.assertEquals(2, rpcs.size());

    final ReadIndexReplyProto firstReply = ReadIndexReplyProto.newBuilder().setReadIndex(10).build();
    rpcs.get(0).complete(firstReply);
    executor.runNext();
    Assertions.assertSame(firstReply, first.get(5, TimeUnit.SECONDS));
    Assertions.assertFalse(second.isDone());

    final ReadIndexReplyProto secondReply = ReadIndexReplyProto.newBuilder().setReadIndex(20).build();
    rpcs.get(1).complete(secondReply);
    executor.runNext();
    Assertions.assertSame(secondReply, second.get(5, TimeUnit.SECONDS));
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testConcurrentSubmittersTerminate(boolean closeWhileSubmitting) throws Exception {
    final ExecutorService executor = Executors.newFixedThreadPool(4);
    final ExecutorService submitters = Executors.newFixedThreadPool(4);
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch submitted = new CountDownLatch(4);
    final ReadIndexReplyProto reply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2,
        (clientId, readRequestType) -> CompletableFuture.completedFuture(reply));
    final List<CompletableFuture<List<CompletableFuture<ReadIndexReplyProto>>>> submissions = new ArrayList<>();
    try {
      for (int i = 0; i < 4; i++) {
        submissions.add(CompletableFuture.supplyAsync(() -> {
          final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
          try {
            Assertions.assertTrue(start.await(5, TimeUnit.SECONDS));
            replies.add(batching.submit(null, null));
            submitted.countDown();
            Assertions.assertTrue(submitted.await(5, TimeUnit.SECONDS));
            for (int j = 1; j < 100; j++) {
              replies.add(batching.submit(null, null));
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          return replies;
        }, submitters));
      }
      start.countDown();
      Assertions.assertTrue(submitted.await(5, TimeUnit.SECONDS));
      if (closeWhileSubmitting) {
        batching.close();
      }
      for (CompletableFuture<List<CompletableFuture<ReadIndexReplyProto>>> submission : submissions) {
        final List<CompletableFuture<ReadIndexReplyProto>> replies = submission.get(5, TimeUnit.SECONDS);
        Assertions.assertEquals(100, replies.size());
        for (CompletableFuture<ReadIndexReplyProto> future : replies) {
          if (!closeWhileSubmitting || !future.isCompletedExceptionally()) {
            Assertions.assertSame(reply, future.get(5, TimeUnit.SECONDS));
          } else {
            assertReadIndexException(future);
          }
        }
      }
    } finally {
      start.countDown();
      batching.close();
      submitters.shutdownNow();
      executor.shutdownNow();
      Assertions.assertTrue(submitters.awaitTermination(5, TimeUnit.SECONDS));
      Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static void assertReadIndexException(CompletableFuture<ReadIndexReplyProto> future) throws Exception {
    final ExecutionException e = Assertions.assertThrows(ExecutionException.class,
        () -> future.get(5, TimeUnit.SECONDS));
    Assertions.assertTrue(e.getCause() instanceof ReadIndexException);
  }

  private static class CapturingExecutor implements Executor {
    private final List<Runnable> tasks = new ArrayList<>();
    private boolean rejectNewTasks;

    public synchronized int getTaskCount() {
      return tasks.size();
    }

    @Override
    public synchronized void execute(Runnable command) {
      if (rejectNewTasks) {
        throw new RejectedExecutionException("closed");
      }
      tasks.add(command);
    }

    void runNext() {
      final Runnable task;
      synchronized (this) {
        task = tasks.remove(0);
      }
      task.run();
    }

    synchronized void rejectNewTasks() {
      rejectNewTasks = true;
    }
  }
}
