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

import org.apache.ratis.BaseTest;
import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.simulation.MiniRaftClusterWithSimulatedRpc;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class TestReadIndexExecutor extends BaseTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  void testBatchingDoesNotWaitForServerExecutor(int threadPoolSize) throws Exception {
    final RaftProperties properties = new RaftProperties();
    RaftServerConfigKeys.Read.ReadIndex.Batch.setEnabled(properties, true);
    RaftServerConfigKeys.Read.ReadIndex.Batch.setThreadPoolSize(properties, threadPoolSize);
    RaftServerConfigKeys.ThreadPool.setServerCached(properties, false);
    RaftServerConfigKeys.ThreadPool.setServerSize(properties, 1);
    try (MiniRaftClusterWithSimulatedRpc cluster = MiniRaftClusterWithSimulatedRpc.FACTORY.newCluster(1, properties)) {
      cluster.initServers();
      final RaftServerImpl server = (RaftServerImpl) cluster.iterateDivisions().iterator().next();
      server.getRole().setLeaderElectionPause(true);
      cluster.start();
      final ReadIndexBatching batching = (ReadIndexBatching) RaftTestUtil.getDeclaredField(server, "readIndexBatching");
      final ExecutorService executor = (ExecutorService) RaftTestUtil.getDeclaredField(batching, "executor");
      Assertions.assertNotSame(server.getServerExecutor(), executor);

      final CountDownLatch started = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      final Future<?> busy = server.getServerExecutor().submit(() -> {
        started.countDown();
        release.await();
        return null;
      });
      try {
        Assertions.assertTrue(started.await(5, TimeUnit.SECONDS));
        final CompletableFuture<ReadIndexReplyProto> read =
            batching.submit(ClientId.randomId(), RaftClientRequest.readRequestType().getRead());
        // Elections are paused, so dispatch and error fanout must run independently without a known leader.
        final ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
            () -> read.get(5, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(ReadIndexException.class, failure.getCause());
        Assertions.assertTrue(failure.getCause().getMessage().contains("Leader is unknown"));
        Assertions.assertFalse(busy.isDone());
      } finally {
        release.countDown();
        busy.get(5, TimeUnit.SECONDS);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testExecutorLifecycle(boolean enabled) throws Exception {
    final RaftProperties properties = new RaftProperties();
    RaftServerConfigKeys.Read.ReadIndex.Batch.setEnabled(properties, enabled);
    try (MiniRaftClusterWithSimulatedRpc cluster = MiniRaftClusterWithSimulatedRpc.FACTORY.newCluster(1, properties)) {
      cluster.initServers();
      final RaftServerImpl server = (RaftServerImpl) cluster.iterateDivisions().iterator().next();
      final ExecutorService executor = (ExecutorService) RaftTestUtil.getDeclaredField(server, "readIndexExecutor");
      if (enabled) {
        Assertions.assertNotNull(executor);
        final ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        Assertions.assertEquals(2, pool.getMaximumPoolSize());
        Assertions.assertEquals(0, pool.getPoolSize(), "Workers should be created lazily.");
      } else {
        Assertions.assertNull(executor);
        Assertions.assertNull(RaftTestUtil.getDeclaredField(server, "readIndexBatching"));
      }
      server.getRole().setLeaderElectionPause(true);
      cluster.start();
      if (enabled) {
        final String worker = executor.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
        Assertions.assertTrue(worker.startsWith(server.getId() + "-read-index"));
      }
      server.close();
      server.close();
      if (enabled) {
        Assertions.assertTrue(executor.isShutdown());
        Assertions.assertTrue(executor.isTerminated());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  void testThreadPoolSizeIsReadAtCreation(int threadPoolSize) throws Exception {
    final RaftProperties properties = new RaftProperties();
    RaftServerConfigKeys.Read.ReadIndex.Batch.setEnabled(properties, true);
    RaftServerConfigKeys.Read.ReadIndex.Batch.setThreadPoolSize(properties, threadPoolSize);
    try (MiniRaftClusterWithSimulatedRpc cluster = MiniRaftClusterWithSimulatedRpc.FACTORY.newCluster(1, properties)) {
      cluster.initServers();
      final RaftServerImpl server = (RaftServerImpl) cluster.iterateDivisions().iterator().next();
      server.getRole().setLeaderElectionPause(true);
      cluster.start();
      final ThreadPoolExecutor executor = (ThreadPoolExecutor) RaftTestUtil.getDeclaredField(server, "readIndexExecutor");
      Assertions.assertEquals(threadPoolSize, executor.getCorePoolSize());
      Assertions.assertEquals(threadPoolSize, executor.getMaximumPoolSize());
      RaftServerConfigKeys.Read.ReadIndex.Batch.setThreadPoolSize(
          server.getRaftServer().getProperties(), threadPoolSize + 1);
      Assertions.assertEquals(threadPoolSize, executor.getCorePoolSize());
      Assertions.assertEquals(threadPoolSize, executor.getMaximumPoolSize());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  void testCloseSettlesQueuedReadsBeforeExecutorTermination(int threadPoolSize) throws Exception {
    final RaftProperties properties = new RaftProperties();
    RaftServerConfigKeys.Read.ReadIndex.Batch.setEnabled(properties, true);
    RaftServerConfigKeys.Read.ReadIndex.Batch.setThreadPoolSize(properties, threadPoolSize);
    try (MiniRaftClusterWithSimulatedRpc cluster = MiniRaftClusterWithSimulatedRpc.FACTORY.newCluster(1, properties)) {
      cluster.initServers();
      final RaftServerImpl server = (RaftServerImpl) cluster.iterateDivisions().iterator().next();
      server.getRole().setLeaderElectionPause(true);
      cluster.start();
      final ReadIndexBatching batching = (ReadIndexBatching) RaftTestUtil.getDeclaredField(server, "readIndexBatching");
      final ExecutorService executor = (ExecutorService) RaftTestUtil.getDeclaredField(server, "readIndexExecutor");
      final CountDownLatch started = new CountDownLatch(threadPoolSize);
      final CountDownLatch release = new CountDownLatch(1);
      for (int i = 0; i < threadPoolSize; i++) {
        executor.submit(() -> {
          started.countDown();
          release.await();
          return null;
        });
      }
      CompletableFuture<Void> closing = null;
      try {
        Assertions.assertTrue(started.await(5, TimeUnit.SECONDS));
        final CompletableFuture<ReadIndexReplyProto> read =
            batching.submit(ClientId.randomId(), RaftClientRequest.readRequestType().getRead());
        final CompletableFuture<Void> closeFuture = CompletableFuture.runAsync(server::close);
        closing = closeFuture;
        final ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
            () -> read.get(5, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(ReadIndexException.class, failure.getCause());
        Assertions.assertTrue(failure.getCause().getMessage().contains("closed"));
        RaftTestUtil.waitFor(executor::isShutdown, 10, 5000);
        Assertions.assertThrows(TimeoutException.class, () -> closeFuture.get(100, TimeUnit.MILLISECONDS),
            "Shutdown must wait for the dedicated workers.");
      } finally {
        release.countDown();
        if (closing != null) {
          closing.get(5, TimeUnit.SECONDS);
        }
      }
      Assertions.assertTrue(executor.isTerminated());
    }
  }
}
