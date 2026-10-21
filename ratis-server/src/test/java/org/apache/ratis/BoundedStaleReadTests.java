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
package org.apache.ratis;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.exceptions.ReadException;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.impl.MiniRaftCluster;
import org.apache.ratis.server.metrics.RaftServerMetricsImpl;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.apache.ratis.util.JavaUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public abstract class BoundedStaleReadTests<CLUSTER extends MiniRaftCluster>
    extends BaseTest implements MiniRaftCluster.Factory.Get<CLUSTER> {
  public static class BoundedCounter extends ReadOnlyRequestTests.CounterStateMachine {
    private volatile CompletableFuture<Message> delayedQuery;

    @Override
    public boolean supportsBoundedStaleReads() {
      return true;
    }

    @Override
    public CompletableFuture<Message> query(Message message) {
      final CompletableFuture<Message> delayed = delayedQuery;
      return delayed != null ? delayed : super.query(message);
    }
  }

  @BeforeEach
  public void setup() {
    getProperties().setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY, BoundedCounter.class, StateMachine.class);
    getProperties().unset(RaftServerConfigKeys.Read.CLOCK_UNCERTAINTY_KEY);
    RaftServerConfigKeys.Read.ReadIndex.setType(getProperties(), RaftServerConfigKeys.Read.ReadIndex.Type.COMMIT_INDEX);
    RaftServerConfigKeys.Read.setTimeout(getProperties(), TimeDuration.valueOf(2, TimeUnit.SECONDS));
    // Bounded fallback must enforce its own consistency even with the weakest ordinary-read configuration.
    RaftServerConfigKeys.Read.setOption(getProperties(), RaftServerConfigKeys.Read.Option.DEFAULT);
    RaftServerConfigKeys.Read.setLeaderHeartbeatCheckEnabled(getProperties(), false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void testLocalAndLinearizableFallback(boolean configureClock) throws Exception {
    if (configureClock) {
      RaftServerConfigKeys.Read.setClockUncertainty(getProperties(), TimeDuration.valueOf(5, TimeUnit.MILLISECONDS));
    }
    runWithNewCluster(3, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division follower = cluster.getFollowers().get(0);
      try (RaftClient client = cluster.createClient()) {
        final RaftClientReply write = client.io().send(ReadOnlyRequestTests.INCREMENT);
        Assertions.assertTrue(write.isSuccess());
        ReadOnlyRequestTests.assertReplyExact(1, client.io().sendStaleRead(ReadOnlyRequestTests.QUERY,
            write.getLogIndex(), TimeDuration.ZERO, follower.getId()));
        final RaftServerMetricsImpl metrics = (RaftServerMetricsImpl) follower.getRaftServerMetrics();
        final long fallback = metrics.getRegistry().counter("boundedStaleReadFallbackCount").getCount();
        Assertions.assertTrue(fallback > 0);
        ReadOnlyRequestTests.assertReplyExact(1, client.async().sendStaleRead(ReadOnlyRequestTests.QUERY,
            write.getLogIndex(), TimeDuration.ONE_MINUTE, follower.getId()).get(10, TimeUnit.SECONDS));
        Assertions.assertEquals(configureClock ? fallback : fallback + 1,
            metrics.getRegistry().counter("boundedStaleReadFallbackCount").getCount());
        Assertions.assertEquals(configureClock ? 1 : 0,
            metrics.getRegistry().counter("boundedStaleReadLocalCount").getCount());
        final long timestamp = leader.getRaftLog().get(write.getLogIndex()).getAppendTimestampMs();
        Assertions.assertTrue(timestamp > 0);
        Assertions.assertEquals(timestamp, follower.getRaftLog().get(write.getLogIndex()).getAppendTimestampMs());
        Assertions.assertEquals(timestamp, leader.getRaftLog().get(write.getLogIndex()).getAppendTimestampMs());
      }
    });
  }

  @ParameterizedTest
  @EnumSource(RaftServerConfigKeys.Read.ReadIndex.Type.class)
  public void testFallbackAdvertisesRequiredCommitIndex(RaftServerConfigKeys.Read.ReadIndex.Type type)
      throws Exception {
    RaftServerConfigKeys.Read.ReadIndex.setType(getProperties(), type);
    runWithNewCluster(3, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division follower = cluster.getFollowers().get(0);
      try (RaftClient client = cluster.createClient()) {
        final RaftClientReply write = client.io().send(ReadOnlyRequestTests.INCREMENT);
        JavaUtils.attempt(() -> Assertions.assertTrue(
            leader.getRaftLog().getLastCommittedIndex() > write.getLogIndex()),
            50, TimeDuration.valueOf(100, TimeUnit.MILLISECONDS), "commit trailing metadata", LOG);
        final RaftClientRequest read = RaftClientRequest.newBuilder().setClientId(ClientId.randomId())
            .setServerId(follower.getId()).setGroupId(cluster.getGroupId()).setMessage(ReadOnlyRequestTests.QUERY)
            .setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ZERO)).build();
        ReadOnlyRequestTests.assertReplyExact(1,
            ((RaftServer) cluster.getServer(follower.getId())).submitClientRequestAsync(read).get(5, TimeUnit.SECONDS));
      }
    });
  }

  @Test
  public void testMinIndexAndQueryShareRequestDeadline() throws Exception {
    runWithNewCluster(3, cluster -> {
      RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division follower = cluster.getFollowers().get(0);
      try (RaftClient client = cluster.createClient()) {
        client.io().send(ReadOnlyRequestTests.INCREMENT);
        final RaftClientRequest missingIndex = RaftClientRequest.newBuilder()
            .setClientId(ClientId.randomId()).setServerId(follower.getId()).setGroupId(cluster.getGroupId())
            .setCallId(1).setMessage(ReadOnlyRequestTests.QUERY).setTimeoutMs(100)
            .setType(RaftClientRequest.boundedStaleReadRequestType(Long.MAX_VALUE, TimeDuration.ONE_MINUTE)).build();
        final RaftClientReply timeout = ((RaftServer) cluster.getServer(follower.getId())).submitClientRequestAsync(missingIndex)
            .get(5, TimeUnit.SECONDS);
        Assertions.assertFalse(timeout.isSuccess());
        Assertions.assertInstanceOf(ReadException.class, timeout.getException());
        // A timed-out index waiter must not poison other readers.
        ReadOnlyRequestTests.assertReplyExact(1, client.io().sendStaleRead(ReadOnlyRequestTests.QUERY,
            0, TimeDuration.ZERO, follower.getId()));
        final BoundedCounter sm = (BoundedCounter) follower.getStateMachine();
        sm.delayedQuery = new CompletableFuture<>();
        final RaftClientRequest delayed = RaftClientRequest.newBuilder().setClientId(missingIndex.getClientId())
            .setServerId(follower.getId()).setGroupId(cluster.getGroupId())
            .setMessage(ReadOnlyRequestTests.QUERY).setTimeoutMs(100)
            .setCallId(2).setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ZERO)).build();
        final CompletableFuture<RaftClientReply> future =
            ((RaftServer) cluster.getServer(follower.getId())).submitClientRequestAsync(delayed);
        final RaftClientReply expired = future.get(5, TimeUnit.SECONDS);
        Assertions.assertFalse(expired.isSuccess());
        Assertions.assertInstanceOf(ReadException.class, expired.getException());
        sm.delayedQuery.complete(Message.valueOf("1"));
        Assertions.assertSame(expired, future.join());
        sm.delayedQuery = null;
      }
    });
  }

  @Test
  public void testIsolatedFollowerCanUseOnlyEligibleLocalState() throws Exception {
    RaftServerConfigKeys.Read.setClockUncertainty(getProperties(), TimeDuration.ONE_MILLISECOND);
    runWithNewCluster(3, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division follower = cluster.getFollowers().get(0);
      final org.apache.ratis.protocol.RaftPeerId other = cluster.getFollowers().get(1).getId();
      final RaftServer serving = cluster.getServer(follower.getId());
      try (RaftClient client = cluster.createClient()) {
        final RaftClientReply write = client.io().send(ReadOnlyRequestTests.INCREMENT);
        ReadOnlyRequestTests.assertReplyExact(1, client.io().sendStaleRead(ReadOnlyRequestTests.QUERY,
            write.getLogIndex(), TimeDuration.ZERO, follower.getId()));
        cluster.killServer(leader.getId());
        cluster.killServer(other);
        ReadOnlyRequestTests.assertReplyExact(1, client.io().sendStaleRead(ReadOnlyRequestTests.QUERY,
            write.getLogIndex(), TimeDuration.ONE_MINUTE, follower.getId()));
        final RaftClientRequest fresh = RaftClientRequest.newBuilder().setClientId(ClientId.randomId())
            .setServerId(follower.getId()).setGroupId(cluster.getGroupId()).setMessage(ReadOnlyRequestTests.QUERY)
            .setTimeoutMs(100).setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ZERO)).build();
        Assertions.assertTrue(serving.submitClientRequestAsync(fresh)
            .handle((reply, error) -> error != null || !reply.isSuccess()).get(5, TimeUnit.SECONDS));
      }
    });
  }

  @Test
  public void testPublicPauseInvalidatesEvidence() throws Exception {
    RaftServerConfigKeys.Read.setClockUncertainty(getProperties(), TimeDuration.ONE_MILLISECOND);
    runWithNewCluster(3, cluster -> {
      RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division follower = cluster.getFollowers().get(0);
      try (RaftClient client = cluster.createClient()) {
        client.io().send(ReadOnlyRequestTests.INCREMENT);
        ReadOnlyRequestTests.assertReplyExact(1, client.io().sendStaleRead(ReadOnlyRequestTests.QUERY,
            0, TimeDuration.ZERO, follower.getId()));
        final java.lang.reflect.Method pause = follower.getClass().getDeclaredMethod("pause");
        final java.lang.reflect.Method resume = follower.getClass().getDeclaredMethod("resume");
        pause.setAccessible(true);
        resume.setAccessible(true);
        Assertions.assertEquals(true, pause.invoke(follower));
        Assertions.assertEquals(true, resume.invoke(follower));
        final RaftClientRequest request = RaftClientRequest.newBuilder().setClientId(ClientId.randomId())
            .setServerId(follower.getId()).setGroupId(cluster.getGroupId()).setMessage(ReadOnlyRequestTests.QUERY)
            .setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ONE_MINUTE)).build();
        final RaftClientReply reply = ((RaftServer) cluster.getServer(follower.getId()))
            .submitClientRequestAsync(request).get(5, TimeUnit.SECONDS);
        Assertions.assertFalse(reply.isSuccess());
        Assertions.assertInstanceOf(ReadException.class, reply.getException());
      }
    });
  }

  @Test
  public void testStateMachineMustOptIn() throws Exception {
    getProperties().setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY,
        ReadOnlyRequestTests.CounterStateMachine.class, StateMachine.class);
    runWithNewCluster(3, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftClientRequest request = RaftClientRequest.newBuilder()
          .setClientId(ClientId.randomId()).setServerId(leader.getId()).setGroupId(cluster.getGroupId())
          .setCallId(1).setMessage(ReadOnlyRequestTests.QUERY)
          .setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ZERO)).build();
      final RaftClientReply reply = ((RaftServer) cluster.getServer(leader.getId())).submitClientRequestAsync(request)
          .get(5, TimeUnit.SECONDS);
      Assertions.assertFalse(reply.isSuccess());
      Assertions.assertInstanceOf(ReadException.class, reply.getException());
    });
  }
}
