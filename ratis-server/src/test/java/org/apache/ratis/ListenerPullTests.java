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
import org.apache.ratis.proto.RaftProtos.RaftGroupIdProto;
import org.apache.ratis.proto.RaftProtos.RaftPeerRole;
import org.apache.ratis.proto.RaftProtos.RaftRpcRequestProto;
import org.apache.ratis.proto.RaftProtos.ReadCommittedEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadCommittedEntriesRequestProto;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftConfiguration;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.impl.BlockRequestHandlingInjection;
import org.apache.ratis.server.impl.MiniRaftCluster;
import org.apache.ratis.server.impl.PeerChanges;
import org.apache.ratis.server.impl.RaftServerTestUtil;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachine4Testing;
import org.apache.ratis.util.SizeInBytes;
import org.apache.ratis.util.Slf4jUtils;
import org.apache.ratis.RaftTestUtil.SimpleMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public abstract class ListenerPullTests<CLUSTER extends MiniRaftCluster>
    extends BaseTest
    implements MiniRaftCluster.Factory.Get<CLUSTER> {
  static final Logger LOG = LoggerFactory.getLogger(ListenerPullTests.class);

  {
    getProperties().setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY,
        SimpleStateMachine4Testing.class, StateMachine.class);
    RaftServerConfigKeys.Listener.setSyncFromFollowerEnabled(getProperties(), true);
    RaftServerConfigKeys.Log.setPurgeGap(getProperties(), 1);
    RaftServerConfigKeys.Log.setSegmentSizeMax(getProperties(), SizeInBytes.valueOf("1KB"));
    RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex(getProperties(), true);
    Slf4jUtils.setLogLevel(RaftServer.Division.LOG, Level.DEBUG);
  }

  @Test
  public void testReadCommittedEntriesSemantics() throws Exception {
    runWithNewCluster(3, 1, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division listener = getOnlyListener(cluster);
      final RaftServer.Division source = cluster.getFollowers().get(0);

      final SimpleMessage[] messages = writeMessages(cluster, 3, "semantics");
      assertListenerLog(listener, leader.getInfo().getCurrentTerm(), messages);

      final long committed = source.getRaftLog().getLastCommittedIndex();
      final long startIndex = Math.max(RaftLog.LEAST_VALID_LOG_INDEX, committed - 1);

      final ReadCommittedEntriesReplyProto success = RaftServerTestUtil.getServerRpc(listener)
          .readCommittedEntries(newReadCommittedEntriesRequest(listener, source.getId(), 1L, startIndex));
      Assertions.assertEquals(ReadCommittedEntriesReplyProto.Result.SUCCESS, success.getResult());
      Assertions.assertEquals(source.getInfo().getCurrentTerm(), success.getTerm());
      Assertions.assertTrue(success.hasLeaderId());
      Assertions.assertEquals(leader.getId(), RaftPeerId.valueOf(success.getLeaderId().getId()));
      Assertions.assertEquals(committed, success.getCommitIndex());
      Assertions.assertTrue(success.getEntriesCount() > 0);
      final long firstReturnedIndex = success.getEntries(0).getIndex();
      if (firstReturnedIndex > RaftLog.LEAST_VALID_LOG_INDEX) {
        Assertions.assertTrue(success.hasPreviousLog());
        Assertions.assertEquals(firstReturnedIndex - 1, success.getPreviousLog().getIndex());
      }
      Assertions.assertEquals(success.getEntries(success.getEntriesCount() - 1).getIndex() + 1, success.getNextIndex());

      final ReadCommittedEntriesReplyProto empty = RaftServerTestUtil.getServerRpc(listener)
          .readCommittedEntries(newReadCommittedEntriesRequest(listener, source.getId(), 2L, committed + 1));
      Assertions.assertEquals(ReadCommittedEntriesReplyProto.Result.SUCCESS, empty.getResult());
      Assertions.assertEquals(0, empty.getEntriesCount());

      final ReadCommittedEntriesReplyProto unavailable = RaftServerTestUtil.getServerRpc(listener)
          .readCommittedEntries(newReadCommittedEntriesRequest(listener, source.getId(), 3L, RaftLog.INVALID_LOG_INDEX));
      Assertions.assertEquals(ReadCommittedEntriesReplyProto.Result.LOG_UNAVAILABLE, unavailable.getResult());
      Assertions.assertTrue(unavailable.getLogStartIndex() >= RaftLog.LEAST_VALID_LOG_INDEX);
    });
  }

  @Test
  public void testStableListenerPullFromFollower() throws Exception {
    runWithNewCluster(3, 1, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division listener = getOnlyListener(cluster);
      try {
        Assertions.assertFalse(RaftServerTestUtil.getLogAppenders(leader)
            .anyMatch(appender -> appender.getFollowerId().equals(listener.getId())));

        BlockRequestHandlingInjection.getInstance().blockRequestPair(
            leader.getId().toString(), listener.getId().toString());

        final SimpleMessage[] messages = writeMessages(cluster, 5, "pull");
        assertListenerLog(listener, leader.getInfo().getCurrentTerm(), messages);
        Assertions.assertTrue(listener.getInfo().isListener());
      } finally {
        BlockRequestHandlingInjection.getInstance().unblockAll();
      }
    });
  }

  @Test
  public void testListenerPullFailover() throws Exception {
    runWithNewCluster(3, 1, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division listener = getOnlyListener(cluster);
      final List<RaftServer.Division> followers = new ArrayList<>(cluster.getFollowers());
      Assertions.assertEquals(2, followers.size());

      try {
        BlockRequestHandlingInjection.getInstance().blockRequestPair(
            listener.getId().toString(), followers.get(0).getId().toString());
        final SimpleMessage[] batch1 = writeMessages(cluster, 3, "failover-1");
        assertListenerLog(listener, leader.getInfo().getCurrentTerm(), batch1);

        BlockRequestHandlingInjection.getInstance().unblockRequestPair(
            listener.getId().toString(), followers.get(0).getId().toString());
        BlockRequestHandlingInjection.getInstance().blockRequestPair(
            listener.getId().toString(), followers.get(1).getId().toString());

        final SimpleMessage[] batch2 = writeMessages(cluster, 3, "failover-2");
        assertListenerLog(listener, leader.getInfo().getCurrentTerm(), concat(batch1, batch2));
        Assertions.assertEquals(leader.getRaftLog().getNextIndex(), listener.getRaftLog().getNextIndex());
      } finally {
        BlockRequestHandlingInjection.getInstance().unblockAll();
      }
    });
  }

  @Test
  public void testListenerPullDisabledUntilStableConfiguration() throws Exception {
    runWithNewCluster(3, 1, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division listener = getOnlyListener(cluster);
      final SimpleMessage[] initial = writeMessages(cluster, 2, "guardrail-initial");
      assertListenerLog(listener, leader.getInfo().getCurrentTerm(), initial);

      final List<RaftPeer> listeners = new ArrayList<>(leader.getRaftConf().getAllPeers(RaftPeerRole.LISTENER));
      final PeerChanges changes = cluster.addNewPeers(1, false);
      final RaftPeer newFollower = changes.getAddedPeers().get(0);
      final List<RaftPeer> servers = new ArrayList<>(leader.getRaftConf().getAllPeers(RaftPeerRole.FOLLOWER));
      servers.add(newFollower);

      final CompletableFuture<RaftClientReply> setConf = setConfigurationAsync(
          cluster, leader.getId(), servers, listeners);

      try {
        RaftTestUtil.waitFor(() -> RaftServerTestUtil.getLogAppenders(leader)
            .anyMatch(appender -> appender.getFollowerId().equals(listener.getId())), 100, 10_000);
        Assertions.assertFalse(setConf.isDone());
        final SimpleMessage[] primeBatch = writeMessages(cluster, 1, "guardrail-prime");
        assertListenerLog(listener, leader.getInfo().getCurrentTerm(), concat(initial, primeBatch));

        BlockRequestHandlingInjection.getInstance().blockRequestPair(
            leader.getId().toString(), listener.getId().toString());

        final SimpleMessage[] blockedBatch = writeMessages(cluster, 3, "guardrail-blocked");
        waitBeforePullCanResume();
        Assertions.assertTrue(listener.getRaftLog().getNextIndex() < leader.getRaftLog().getNextIndex());

        BlockRequestHandlingInjection.getInstance().unblockRequestPair(
            leader.getId().toString(), listener.getId().toString());
        cluster.restartServer(newFollower.getId(), false);

        final RaftClientReply setConfReply = setConf.get(20, TimeUnit.SECONDS);
        Assertions.assertTrue(setConfReply.isSuccess());
        RaftTestUtil.waitFor(() -> isStable(listener.getRaftConf()), 100, 10_000);
        RaftTestUtil.waitFor(() -> RaftServerTestUtil.getLogAppenders(leader) != null
                && RaftServerTestUtil.getLogAppenders(leader)
                .noneMatch(appender -> appender.getFollowerId().equals(listener.getId())),
            100, 10_000);
        Assertions.assertFalse(RaftServerTestUtil.getLogAppenders(leader)
            .anyMatch(appender -> appender.getFollowerId().equals(listener.getId())));

        BlockRequestHandlingInjection.getInstance().blockRequestPair(
            leader.getId().toString(), listener.getId().toString());
        final SimpleMessage[] stableBatch = writeMessages(cluster, 3, "guardrail-stable");
        assertListenerLog(listener, cluster.getLeader().getInfo().getCurrentTerm(),
            concat(concat(concat(initial, primeBatch), blockedBatch), stableBatch));
      } finally {
        BlockRequestHandlingInjection.getInstance().unblockAll();
      }
    });
  }

  @Test
  public void testListenerLogUnavailableDoesNotCorruptState() throws Exception {
    runWithNewCluster(3, 1, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division listener = getOnlyListener(cluster);
      final List<RaftServer.Division> followers = new ArrayList<>(cluster.getFollowers());
      Assertions.assertEquals(2, followers.size());

      final SimpleMessage[] initial = writeMessages(cluster, 2, "unavailable-initial");
      assertListenerLog(listener, leader.getInfo().getCurrentTerm(), initial);
      final long listenerNextIndex = listener.getRaftLog().getNextIndex();

      try {
        for (RaftServer.Division follower : followers) {
          BlockRequestHandlingInjection.getInstance().blockRequestPair(
              listener.getId().toString(), follower.getId().toString());
        }

        writeMessages(cluster, 16, "unavailable-lagged-" + repeat('x', 256));
        ONE_SECOND.sleep();
        Assertions.assertEquals(listenerNextIndex, listener.getRaftLog().getNextIndex());

        for (int i = 0; i < followers.size(); i++) {
          final RaftServer.Division follower = followers.get(i);
          final long snapshotIndex = SimpleStateMachine4Testing.get(follower).takeSnapshot();
          Assertions.assertTrue(snapshotIndex > listenerNextIndex);
          final long purgeUpTo = follower.getRaftLog().getLastCommittedIndex() - 1;
          Assertions.assertTrue(purgeUpTo >= listenerNextIndex);
          follower.getRaftLog().purge(purgeUpTo).get();

          final ReadCommittedEntriesReplyProto unavailable = RaftServerTestUtil.getServerRpc(leader)
              .readCommittedEntries(newReadCommittedEntriesRequest(leader, follower.getId(), 10L + i,
                  listenerNextIndex));
          Assertions.assertEquals(ReadCommittedEntriesReplyProto.Result.LOG_UNAVAILABLE, unavailable.getResult());
          Assertions.assertTrue(unavailable.getLogStartIndex() > listenerNextIndex);
        }

        for (RaftServer.Division follower : followers) {
          BlockRequestHandlingInjection.getInstance().unblockRequestPair(
              listener.getId().toString(), follower.getId().toString());
        }

        FIVE_SECONDS.sleep();
        Assertions.assertEquals(RaftPeerRole.LISTENER, listener.getInfo().getCurrentRole());
        Assertions.assertEquals(listenerNextIndex, listener.getRaftLog().getNextIndex());
        assertListenerLog(listener, leader.getInfo().getCurrentTerm(), initial);
      } finally {
        BlockRequestHandlingInjection.getInstance().unblockAll();
      }
    });
  }

  private static RaftServer.Division getOnlyListener(MiniRaftCluster cluster) {
    Assertions.assertEquals(1, cluster.getListeners().size());
    return cluster.getListeners().get(0);
  }

  private ReadCommittedEntriesRequestProto newReadCommittedEntriesRequest(
      RaftServer.Division requestor, RaftPeerId replyId, long callId, long startIndex) {
    final RaftRpcRequestProto serverRequest = RaftRpcRequestProto.newBuilder()
        .setRequestorId(requestor.getId().toByteString())
        .setReplyId(replyId.toByteString())
        .setRaftGroupId(RaftGroupIdProto.newBuilder().setId(requestor.getMemberId().getGroupId().toByteString()))
        .setCallId(callId)
        .build();
    return ReadCommittedEntriesRequestProto.newBuilder()
        .setServerRequest(serverRequest)
        .setStartIndex(startIndex)
        .build();
  }

  private static SimpleMessage[] writeMessages(MiniRaftCluster cluster, int numMessages, String prefix) throws Exception {
    final SimpleMessage[] messages = SimpleMessage.create(numMessages, prefix + "-");
    try (RaftClient client = cluster.createClient()) {
      for (SimpleMessage message : messages) {
        final RaftClientReply reply = client.io().send(message);
        Assertions.assertTrue(reply.isSuccess());
      }
    }
    return messages;
  }

  private static void assertListenerLog(RaftServer.Division listener, long term, SimpleMessage[] messages)
      throws Exception {
    RaftTestUtil.assertLogEntries(listener, term, messages, 100, LOG);
  }

  private static boolean isStable(RaftConfiguration conf) {
    return conf.getPreviousPeers(RaftPeerRole.FOLLOWER).isEmpty()
        && conf.getPreviousPeers(RaftPeerRole.LISTENER).isEmpty();
  }

  private static CompletableFuture<RaftClientReply> setConfigurationAsync(
      MiniRaftCluster cluster, RaftPeerId leaderId, List<RaftPeer> servers, List<RaftPeer> listeners) {
    return CompletableFuture.supplyAsync(() -> {
      try (RaftClient client = cluster.createClient(leaderId)) {
        return client.admin().setConfiguration(servers, listeners);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    });
  }

  private static SimpleMessage[] concat(SimpleMessage[] first, SimpleMessage[] second) {
    final List<SimpleMessage> messages = new ArrayList<>(Arrays.asList(first));
    messages.addAll(Arrays.asList(second));
    return messages.toArray(new SimpleMessage[0]);
  }

  private void waitBeforePullCanResume() throws InterruptedException {
    final long timeoutMinMs = RaftServerConfigKeys.Rpc.timeoutMin(getProperties()).toLong(TimeUnit.MILLISECONDS);
    TimeUnit.MILLISECONDS.sleep(Math.max(50L, Math.min(200L, timeoutMinMs - 1)));
  }

  private static String repeat(char c, int count) {
    final char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
