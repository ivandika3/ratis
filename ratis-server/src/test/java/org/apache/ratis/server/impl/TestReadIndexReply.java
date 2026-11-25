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
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto.AppendResult;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.proto.RaftProtos.MetadataProto;
import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadIndexRequestProto;
import org.apache.ratis.proto.RaftProtos.ReadRequestTypeProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.protocol.RaftServerAsynchronousProtocol;
import org.apache.ratis.server.protocol.RaftServerProtocol.Op;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Timeout(30)
public class TestReadIndexReply extends BaseTest {
  private static final RaftPeerId LEADER = RaftPeerId.valueOf("s0");
  private static final RaftPeerId FOLLOWER = RaftPeerId.valueOf("s1");
  private static final Message QUERY = Message.valueOf("read");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void testReplyAdvancesCommitIndexWithoutAppendEntries(boolean advanceTerm) throws Exception {
    try (Follower follower = new Follower()) {
      final CountDownLatch applying = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      Mockito.doAnswer(invocation -> {
        applying.countDown();
        Assertions.assertTrue(release.await(10, TimeUnit.SECONDS));
        return invocation.callRealMethod();
      }).when(follower.stateMachine).notifyTermIndexUpdated(1, 2);
      final CompletableFuture<Message> read = follower.read();
      if (advanceTerm) {
        follower.server.changeToFollowerAndPersistMetadata(2, true, Op.REQUEST_VOTE).join();
      }
      try {
        follower.reply.complete(follower.reply(TermIndex.valueOf(1, 2)));
        Assertions.assertEquals(2, follower.server.getRaftLog().getLastCommittedIndex());
        Assertions.assertTrue(applying.await(10, TimeUnit.SECONDS));
        Assertions.assertFalse(read.isDone(), "the read must still wait for state-machine application");
      } finally {
        release.countDown();
      }
      Assertions.assertSame(QUERY, read.get(10, TimeUnit.SECONDS));
      Assertions.assertEquals(2, follower.server.getInfo().getLastAppliedIndex());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent", "term mismatch", "index mismatch", "missing entry"})
  public void testUnverifiableReplyWaitsForAppendEntries(String reason) throws Exception {
    try (Follower follower = new Follower()) {
      TermIndex committed = TermIndex.valueOf(1, 2);
      switch (reason) {
        case "absent":
          committed = null;
          break;
        case "term mismatch":
          committed = TermIndex.valueOf(2, 2);
          break;
        case "index mismatch":
          committed = TermIndex.valueOf(1, 1);
          break;
        case "missing entry":
          follower.server.getRaftLog().truncate(2).join();
          break;
        default:
          throw new AssertionError(reason);
      }
      final CompletableFuture<Message> read = follower.read();
      follower.reply.complete(follower.reply(committed));
      Assertions.assertEquals(0, follower.server.getRaftLog().getLastCommittedIndex());
      Assertions.assertFalse(read.isDone());
      // Normal replication remains sufficient, including when the response came from an old leader version.
      follower.append(Collections.singletonList(entry(2)), 2, TermIndex.valueOf(1, 1));
      Assertions.assertSame(QUERY, read.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  public void testReplyDuringSnapshotInstallationDoesNotAdvanceCommitIndex() throws Exception {
    try (Follower follower = new Follower()) {
      final CompletableFuture<Message> read = follower.read();
      follower.server.getSnapshotInstallationHandler().setInProgressInstallSnapshotIndex(10);
      try {
        follower.reply.complete(follower.reply(TermIndex.valueOf(1, 2)));
        Assertions.assertEquals(0, follower.server.getRaftLog().getLastCommittedIndex());
        Assertions.assertTrue(read.isCompletedExceptionally());
      } finally {
        follower.server.getSnapshotInstallationHandler().setInProgressInstallSnapshotIndex(RaftLog.INVALID_LOG_INDEX);
      }
    }
  }

  @Test
  public void testReplyWhilePausedDoesNotAdvanceCommitIndex() throws Exception {
    try (Follower follower = new Follower()) {
      final CompletableFuture<Message> read = follower.read();
      Assertions.assertTrue(follower.server.pause());
      follower.reply.complete(follower.reply(TermIndex.valueOf(1, 2)));
      Assertions.assertEquals(0, follower.server.getRaftLog().getLastCommittedIndex());
      Assertions.assertFalse(read.isDone());
      Assertions.assertTrue(follower.server.resume());
      follower.append(Collections.emptyList(), 2, TermIndex.valueOf(1, 2));
      Assertions.assertSame(QUERY, read.get(10, TimeUnit.SECONDS));
    }
  }

  private static LogEntryProto entry(long index) {
    return LogEntryProto.newBuilder().setTerm(1).setIndex(index)
        .setMetadataEntry(MetadataProto.getDefaultInstance()).build();
  }

  private class Follower implements AutoCloseable {
    private final CompletableFuture<ReadIndexReplyProto> reply = new CompletableFuture<>();
    private final RaftGroupId groupId = RaftGroupId.randomId();
    private final BaseStateMachine stateMachine = Mockito.spy(new BaseStateMachine());
    private final RaftServerImpl server;

    Follower() throws Exception {
      final RaftProperties properties = new RaftProperties();
      RaftServerConfigKeys.setStorageDir(properties,
          Collections.singletonList(new File(getTestDir(), groupId.toString())));
      RaftServerConfigKeys.Log.setUseMemory(properties, true);
      RaftServerConfigKeys.Read.setOption(properties, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
      final TimeDuration timeout = TimeDuration.valueOf(60, TimeUnit.SECONDS);
      RaftServerConfigKeys.Rpc.setTimeoutMin(properties, timeout);
      RaftServerConfigKeys.Rpc.setTimeoutMax(properties, timeout.add(TimeDuration.ONE_SECOND));
      RaftServerConfigKeys.Rpc.setFirstElectionTimeoutMin(properties, timeout);
      RaftServerConfigKeys.Rpc.setFirstElectionTimeoutMax(properties, timeout.add(TimeDuration.ONE_SECOND));
      final RaftServerAsynchronousProtocol async = Mockito.mock(RaftServerAsynchronousProtocol.class);
      Mockito.when(async.readIndexAsync(Mockito.any(ReadIndexRequestProto.class))).thenReturn(reply);
      final RaftServerRpc rpc = Mockito.mock(RaftServerRpc.class);
      Mockito.when(rpc.async()).thenReturn(async);
      final RaftGroup group = RaftGroup.valueOf(groupId, Arrays.asList(peer(LEADER), peer(FOLLOWER)));
      final RaftServerProxy proxy = Mockito.mock(RaftServerProxy.class);
      Mockito.when(proxy.getId()).thenReturn(FOLLOWER);
      Mockito.when(proxy.getPeer()).thenReturn(group.getPeer(FOLLOWER));
      Mockito.when(proxy.getProperties()).thenReturn(properties);
      Mockito.when(proxy.getThreadGroup()).thenReturn(new ThreadGroup("read-index-reply"));
      Mockito.when(proxy.getServerRpc()).thenReturn(rpc);
      Mockito.doReturn(CompletableFuture.completedFuture(QUERY)).when(stateMachine).query(Mockito.any());
      server = new RaftServerImpl(group, stateMachine, proxy, RaftStorage.StartupOption.FORMAT);
      server.start();
      append(Arrays.asList(entry(0), entry(1), entry(2)), 0, null);
      JavaUtils.attempt(() -> Assertions.assertEquals(0, server.getInfo().getLastAppliedIndex()),
          100, TimeDuration.valueOf(10, TimeUnit.MILLISECONDS), "initial commit applied", LOG);
    }

    CompletableFuture<Message> read() throws Exception {
      return server.readOnlyAsync(ClientId.randomId(), ReadRequestTypeProto.getDefaultInstance(), QUERY);
    }

    ReadIndexReplyProto reply(TermIndex committed) {
      return ServerProtoUtils.toReadIndexReplyProto(FOLLOWER, RaftGroupMemberId.valueOf(LEADER, groupId),
          true, 2, committed);
    }

    void append(List<LogEntryProto> entries, long commitIndex, TermIndex previous) throws Exception {
      Assertions.assertEquals(AppendResult.SUCCESS, server.appendEntries(ServerProtoUtils.toAppendEntriesRequestProto(
          RaftGroupMemberId.valueOf(LEADER, groupId), FOLLOWER, 1, entries, commitIndex, false,
          previous, Collections.emptyList(), 0, null)).getResult());
    }

    @Override
    public void close() {
      server.close();
    }
  }

  private static RaftPeer peer(RaftPeerId id) {
    return RaftPeer.newBuilder().setId(id).setAddress("127.0.0.1:0").build();
  }
}
