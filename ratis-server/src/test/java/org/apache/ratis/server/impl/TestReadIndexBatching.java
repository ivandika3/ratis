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
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class TestReadIndexBatching {
  @Test
  void testBatchSizeMustBePositive() {
    final RaftProperties properties = new RaftProperties();

    Assertions.assertThrows(IllegalArgumentException.class,
        () -> RaftServerConfigKeys.Read.ReadIndex.Batch.setBatchSize(properties, 0));

    properties.setInt(RaftServerConfigKeys.Read.ReadIndex.Batch.BATCH_SIZE_KEY, 0);
    Assertions.assertThrows(IllegalArgumentException.class,
        () -> RaftServerConfigKeys.Read.ReadIndex.Batch.batchSize(properties));
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
        executor, 64, 1, request -> {
          readIndexCount.incrementAndGet();
          return CompletableFuture.completedFuture(readIndexReply);
        });

    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null);
    Assertions.assertFalse(first.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null);
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
  void testBatchSizeCapsEachDrain() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexReplyProto readIndexReply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(
        executor, 2, 1, request -> {
          readIndexCount.incrementAndGet();
          return CompletableFuture.completedFuture(readIndexReply);
        });

    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> third = batching.submit(null);

    executor.runNext();
    Assertions.assertEquals(1, readIndexCount.get());
    Assertions.assertFalse(first.isDone());
    Assertions.assertFalse(second.isDone());
    Assertions.assertFalse(third.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertSame(readIndexReply, first.get());
    Assertions.assertSame(readIndexReply, second.get());
    Assertions.assertFalse(third.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertEquals(2, readIndexCount.get());
    Assertions.assertFalse(third.isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertSame(readIndexReply, third.get());
  }

  @Test
  void testWaitsForInFlightBatchAndOffloadsCompletion() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final CompletableFuture<ReadIndexReplyProto> firstReadIndex = new CompletableFuture<>();
    final ReadIndexReplyProto readIndexReply = ReadIndexReplyProto.getDefaultInstance();
    final ReadIndexBatching batching = new ReadIndexBatching(
        executor, 2, 1, request -> {
          if (readIndexCount.getAndIncrement() == 0) {
            return firstReadIndex;
          }
          return CompletableFuture.completedFuture(readIndexReply);
        });

    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> third = batching.submit(null);

    executor.runNext();
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
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 64, 1, request -> failed);

    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null);

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

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  void testCloseCompletesQueuedAndInFlightBatchesExceptionally(int maxInFlight) throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final List<CompletableFuture<ReadIndexReplyProto>> rpcs = new ArrayList<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, maxInFlight, request -> {
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 2 * (maxInFlight + 1); i++) {
      replies.add(batching.submit(null));
    }
    for (int i = 0; i < maxInFlight; i++) {
      executor.runNext();
    }
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
    assertReadIndexException(batching.submit(null));
  }

  @Test
  void testScheduleFailureClosesBatching() throws Exception {
    final ReadIndexBatching batching = new ReadIndexBatching(
        command -> {
          throw new RejectedExecutionException("closed");
        }, 64, 1, request -> CompletableFuture.completedFuture(ReadIndexReplyProto.getDefaultInstance()));

    final CompletableFuture<ReadIndexReplyProto> rejected = batching.submit(null);
    assertReadIndexException(rejected);

    final CompletableFuture<ReadIndexReplyProto> afterClose = batching.submit(null);
    assertReadIndexException(afterClose);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  void testCompletionScheduleFailureClosesBatching(int maxInFlight) throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final CompletableFuture<ReadIndexReplyProto> readIndexFuture = new CompletableFuture<>();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, maxInFlight, request -> {
      readIndexCount.incrementAndGet();
      return readIndexFuture;
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i <= maxInFlight; i++) {
      replies.add(batching.submit(null));
    }
    for (int i = 0; i < maxInFlight; i++) {
      executor.runNext();
    }
    Assertions.assertEquals(maxInFlight, readIndexCount.get());

    executor.rejectNewTasks();
    readIndexFuture.complete(ReadIndexReplyProto.getDefaultInstance());

    for (CompletableFuture<ReadIndexReplyProto> reply : replies) {
      assertReadIndexException(reply);
    }
    Assertions.assertEquals(maxInFlight, readIndexCount.get());
    assertReadIndexException(batching.submit(null));
  }

  @Test
  void testSubmitAfterCloseCompletesExceptionally() {
    final AtomicInteger readIndexCount = new AtomicInteger();
    final ReadIndexBatching batching = new ReadIndexBatching(
        Runnable::run, 64, 1, request -> {
          readIndexCount.incrementAndGet();
          return new CompletableFuture<ReadIndexReplyProto>();
        });

    batching.close();

    final CompletableFuture<ReadIndexReplyProto> reply = batching.submit(null);
    final ExecutionException e = Assertions.assertThrows(ExecutionException.class, reply::get);
    Assertions.assertTrue(e.getCause() instanceof ReadIndexException);
    Assertions.assertEquals(0, readIndexCount.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4})
  void testBoundsInFlightBatchesAndCompletesOutOfOrder(int maxInFlight) throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final List<RaftClientRequest> sent = new ArrayList<>();
    final List<CompletableFuture<ReadIndexReplyProto>> rpcs = new ArrayList<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, maxInFlight, request -> {
      sent.add(request);
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final RaftGroupId groupId = RaftGroupId.randomId();
    final List<RaftClientRequest> requests = new ArrayList<>();
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 2 * (maxInFlight + 1); i++) {
      final RaftClientRequest request = RaftClientRequest.newBuilder()
          .setClientId(ClientId.randomId())
          .setServerId(RaftPeerId.valueOf("leader"))
          .setGroupId(groupId)
          .setCallId(i)
          .setType(RaftClientRequest.readRequestType())
          .build();
      requests.add(request);
      replies.add(batching.submit(request));
    }

    for (int i = 0; i < maxInFlight; i++) {
      Assertions.assertEquals(1, executor.getTaskCount());
      executor.runNext();
      Assertions.assertEquals(i + 1, rpcs.size());
      Assertions.assertSame(requests.get(2 * i), sent.get(i));
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
    Assertions.assertSame(requests.get(2 * maxInFlight), sent.get(maxInFlight));
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
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 2, 2, request -> {
      final CompletableFuture<ReadIndexReplyProto> rpc = new CompletableFuture<>();
      rpcs.add(rpc);
      return rpc;
    });
    final List<CompletableFuture<ReadIndexReplyProto>> replies = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      replies.add(batching.submit(null));
    }
    executor.runNext();
    executor.runNext();
    Assertions.assertEquals(2, rpcs.size());

    final CompletableFuture<Void> continuation = replies.get(0).thenRun(() -> {
      Assertions.assertFalse(replies.get(1).isDone());
      replies.add(batching.submit(null));
      Assertions.assertEquals(0, executor.getTaskCount());
      Assertions.assertEquals(2, rpcs.size());
    });
    final ReadIndexReplyProto firstReply = ReadIndexReplyProto.newBuilder().setReadIndex(10).build();
    rpcs.get(0).complete(firstReply);
    executor.runNext();
    continuation.get(5, TimeUnit.SECONDS);
    Assertions.assertSame(firstReply, replies.get(0).get(5, TimeUnit.SECONDS));
    Assertions.assertSame(firstReply, replies.get(1).get(5, TimeUnit.SECONDS));
    Assertions.assertFalse(replies.get(5).isDone());
    Assertions.assertEquals(1, executor.getTaskCount());

    executor.runNext();
    Assertions.assertEquals(3, rpcs.size());
    for (int i = 1; i < 3; i++) {
      final ReadIndexReplyProto reply = ReadIndexReplyProto.newBuilder().setReadIndex(10 + i).build();
      rpcs.get(i).complete(reply);
      executor.runNext();
      Assertions.assertSame(reply, replies.get(2 * i).get(5, TimeUnit.SECONDS));
      Assertions.assertSame(reply, replies.get(2 * i + 1).get(5, TimeUnit.SECONDS));
    }
    Assertions.assertEquals(0, executor.getTaskCount());
  }

  @Test
  void testSubmitDuringSendDoesNotScheduleAnotherDrain() throws Exception {
    final CapturingExecutor executor = new CapturingExecutor();
    final AtomicInteger readIndexCount = new AtomicInteger();
    final CompletableFuture<Void> sending = new CompletableFuture<>();
    final CompletableFuture<Void> resume = new CompletableFuture<>();
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, 2, request -> {
      if (readIndexCount.incrementAndGet() == 1) {
        sending.complete(null);
        resume.join();
      }
      return new CompletableFuture<>();
    });
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null);
    final CompletableFuture<Void> drain = CompletableFuture.runAsync(executor::runNext);
    try {
      sending.get(5, TimeUnit.SECONDS);
      final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null);
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
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, 2, request -> {
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
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> third = batching.submit(null);
    executor.runNext();
    executor.runNext();
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
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, 2, request -> {
      readIndexCount.incrementAndGet();
      return new CompletableFuture<>();
    });
    final CompletableFuture<ReadIndexReplyProto> reply = batching.submit(null);
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
    final ReadIndexBatching batching = new ReadIndexBatching(executor, 1, 2, request -> {
      readIndexCount.incrementAndGet();
      executor.rejectNewTasks();
      return new CompletableFuture<>();
    });
    final CompletableFuture<ReadIndexReplyProto> first = batching.submit(null);
    final CompletableFuture<ReadIndexReplyProto> second = batching.submit(null);
    executor.runNext();
    assertReadIndexException(first);
    assertReadIndexException(second);
    assertReadIndexException(batching.submit(null));
    Assertions.assertEquals(1, readIndexCount.get());
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
