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
package org.apache.ratis.grpc.server;

import org.apache.ratis.BaseTest;
import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.RaftTestUtil.SimpleMessage;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.MiniRaftClusterWithGrpc;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.impl.MiniRaftCluster;
import org.apache.ratis.server.impl.RaftServerTestUtil;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachine4Testing;
import org.apache.ratis.util.JavaUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collection;

/**
 * Test that gRPC appendEntries stream observers are properly closed when
 * resetClient() is called, preventing HTTP/2 stream leaks on the follower.
 *
 * Uses {@link GrpcServerProtocolService#getOpenAppendEntriesObserverCount()}
 * to directly measure the number of active (non-closed) server-side stream
 * observers. With the fix, this count should remain bounded even after
 * many resetClient() cycles. Without the fix, each reset leaks an observer,
 * causing the count to grow unboundedly.
 *
 * This test is in the {@code org.apache.ratis.grpc.server} package to access
 * the package-private {@link GrpcServerProtocolService}.
 */
public class TestGrpcStreamObserverLeak
    extends BaseTest
    implements MiniRaftClusterWithGrpc.FactoryGet {
  {
    final RaftProperties prop = getProperties();
    prop.setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY,
        SimpleStateMachine4Testing.class, StateMachine.class);
    RaftServerConfigKeys.Log.Appender.setBufferElementLimit(prop, 1);
  }

  public static Collection<Boolean[]> data() {
    return Arrays.asList(new Boolean[][] {{Boolean.FALSE}, {Boolean.TRUE}});
  }

  private static int getOpenObserverCount() {
    return GrpcServerProtocolService.getOpenAppendEntriesObserverCount();
  }

  /**
   * Verifies that repeatedly restarting LogAppenders (which triggers
   * resetClient -> stop + onCompleted/cancelStream) properly closes
   * server-side stream observers.
   *
   * The test measures the open observer count after each restart cycle.
   * With the fix, the count should stabilize at the number of active
   * leader-follower streams (2 for a 3-node cluster without separate
   * heartbeat, 4 with separate heartbeat). Without the fix, the count
   * would grow by 2 (or 4) per restart.
   */
  @ParameterizedTest
  @MethodSource("data")
  public void testStreamCleanupOnLogAppenderRestart(Boolean separateHeartbeat) throws Exception {
    GrpcConfigKeys.Server.setHeartbeatChannel(getProperties(), separateHeartbeat);
    runWithNewCluster(3, cluster -> runTestStreamCleanupOnLogAppenderRestart(cluster, separateHeartbeat));
  }

  private void runTestStreamCleanupOnLogAppenderRestart(
      MiniRaftClusterWithGrpc cluster, boolean separateHeartbeat) throws Exception {
    final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
    final int numFollowers = 2;
    // Each follower gets 1 appendLog stream + optionally 1 heartbeat stream
    final int streamsPerFollower = separateHeartbeat ? 2 : 1;
    final int expectedActiveStreams = numFollowers * streamsPerFollower;
    final int numRestarts = 10;

    // Send initial writes to establish streams
    try (RaftClient client = cluster.createClient(leader.getId())) {
      for (int i = 0; i < 5; i++) {
        Assertions.assertTrue(client.io().send(new SimpleMessage("init-" + i)).isSuccess());
      }
    }

    // Wait for streams to be fully established
    JavaUtils.attempt(() -> {
      final int count = getOpenObserverCount();
      LOG.info("Initial open observer count: {}", count);
      Assertions.assertTrue(count >= expectedActiveStreams,
          "Expected at least " + expectedActiveStreams + " active observers, got " + count);
    }, 10, ONE_SECOND, "initial streams established", LOG);

    final int baselineCount = getOpenObserverCount();
    LOG.info("Baseline open observer count before restarts: {}", baselineCount);

    for (int i = 0; i < numRestarts; i++) {
      // Send writes to ensure streams are active
      try (RaftClient client = cluster.createClient(leader.getId())) {
        final RaftClientReply reply = client.io().send(new SimpleMessage("restart-" + i));
        Assertions.assertTrue(reply.isSuccess());
      }

      RaftServerTestUtil.restartLogAppenders(leader);

      // Wait for old observers to close and new ones to open
      final int iteration = i;
      JavaUtils.attempt(() -> {
        final int count = getOpenObserverCount();
        LOG.info("Open observer count after restart {}: {}", iteration + 1, count);
        // The count should not grow significantly beyond baseline.
        // Allow a small margin for in-flight close/open overlap.
        Assertions.assertTrue(count <= baselineCount + expectedActiveStreams,
            "Observer count " + count + " exceeds baseline " + baselineCount
                + " + " + expectedActiveStreams + " after " + (iteration + 1)
                + " restarts. Streams are likely leaking.");
      }, 20, HUNDRED_MILLIS, "observers closed after restart " + (i + 1), LOG);
    }

    final int finalCount = getOpenObserverCount();
    LOG.info("Final open observer count after {} restarts: {} (baseline was {})",
        numRestarts, finalCount, baselineCount);

    // After all restarts, the count should have returned to roughly baseline
    // (active streams for current leader-follower connections only)
    Assertions.assertTrue(finalCount <= baselineCount + expectedActiveStreams,
        "Final observer count " + finalCount + " exceeds baseline " + baselineCount
            + " + " + expectedActiveStreams + " after " + numRestarts
            + " restarts. Server-side stream observers are leaking.");
  }

  /**
   * Verifies that killing and restarting a follower (which triggers
   * resetClient(Event.ERROR) on the leader) properly closes server-side
   * stream observers via cancelStream().
   *
   * Each kill-restart cycle creates new appendEntries streams. Without the
   * fix, the old streams on the killed follower's server would never close,
   * but the leader would also leak stream observer state on the new follower.
   */
  @ParameterizedTest
  @MethodSource("data")
  public void testStreamCleanupOnFollowerRestart(Boolean separateHeartbeat) throws Exception {
    GrpcConfigKeys.Server.setHeartbeatChannel(getProperties(), separateHeartbeat);
    runWithNewCluster(3, cluster -> runTestStreamCleanupOnFollowerRestart(cluster, separateHeartbeat));
  }

  private void runTestStreamCleanupOnFollowerRestart(
      MiniRaftClusterWithGrpc cluster, boolean separateHeartbeat) throws Exception {
    RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
    final int numCycles = 5;
    final int numFollowers = 2;
    final int streamsPerFollower = separateHeartbeat ? 2 : 1;
    final int expectedActiveStreams = numFollowers * streamsPerFollower;

    // Establish initial streams
    try (RaftClient client = cluster.createClient(leader.getId())) {
      for (int i = 0; i < 5; i++) {
        Assertions.assertTrue(client.io().send(new SimpleMessage("init-" + i)).isSuccess());
      }
    }

    JavaUtils.attempt(() -> {
      Assertions.assertTrue(getOpenObserverCount() >= expectedActiveStreams);
    }, 10, ONE_SECOND, "initial streams established", LOG);

    final int baselineCount = getOpenObserverCount();
    LOG.info("Baseline open observer count: {}", baselineCount);

    final RaftPeerId followerId = cluster.getFollowers().iterator().next().getId();

    for (int i = 0; i < numCycles; i++) {
      try (RaftClient client = cluster.createClient(leader.getId())) {
        Assertions.assertTrue(client.io().send(new SimpleMessage("cycle-" + i)).isSuccess());
      }

      LOG.info("Killing follower {}, cycle {}/{}", followerId, i + 1, numCycles);
      cluster.killServer(followerId);
      Thread.sleep(1000);

      LOG.info("Restarting follower {}, cycle {}/{}", followerId, i + 1, numCycles);
      cluster.restartServer(followerId, false);
      leader = RaftTestUtil.waitForLeader(cluster);

      // Send a write to re-establish streams to the restarted follower
      try (RaftClient client = cluster.createClient(leader.getId())) {
        Assertions.assertTrue(client.io().send(new SimpleMessage("post-restart-" + i)).isSuccess());
      }

      // Wait for old streams to close and new ones to open
      final int iteration = i;
      JavaUtils.attempt(() -> {
        final int count = getOpenObserverCount();
        LOG.info("Open observer count after kill-restart {}: {}", iteration + 1, count);
        // Old follower's observers are gone (server killed).
        // New follower should have fresh observers.
        // Leader's observers for the old follower should have been closed by cancelStream().
        // Allow margin for timing: at most baselineCount + one full set of in-flight streams.
        Assertions.assertTrue(count <= baselineCount + expectedActiveStreams,
            "Observer count " + count + " exceeds baseline " + baselineCount
                + " + " + expectedActiveStreams + " after kill-restart cycle "
                + (iteration + 1) + ". Streams are likely leaking.");
      }, 30, HUNDRED_MILLIS, "observers stabilized after kill-restart " + (i + 1), LOG);
    }

    final int finalCount = getOpenObserverCount();
    LOG.info("Final open observer count after {} kill-restart cycles: {} (baseline was {})",
        numCycles, finalCount, baselineCount);

    Assertions.assertTrue(finalCount <= baselineCount + expectedActiveStreams,
        "Final observer count " + finalCount + " exceeds baseline " + baselineCount
            + " + " + expectedActiveStreams + " after " + numCycles
            + " kill-restart cycles. Server-side stream observers are leaking.");
  }
}
