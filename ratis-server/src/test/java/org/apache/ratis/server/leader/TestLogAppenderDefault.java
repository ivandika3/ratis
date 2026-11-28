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
package org.apache.ratis.server.leader;

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto.AppendResult;
import org.apache.ratis.proto.RaftProtos.AppendEntriesRequestProto;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.Timestamp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestLogAppenderDefault {
  private static final RaftPeerId FOLLOWER_ID = RaftPeerId.valueOf("follower");

  @Test
  @Timeout(10)
  void higherTermRejectionDoesNotRenewLeaderLease() throws Exception {
    assertLeaseAcknowledgement(AppendResult.NOT_LEADER, 2, false);
  }

  @Test
  @Timeout(10)
  void sameTermRejectionDoesNotRenewLeaderLease() throws Exception {
    assertLeaseAcknowledgement(AppendResult.NOT_LEADER, 1, false);
  }

  @Test
  @Timeout(10)
  void sameTermSuccessRenewsLeaderLease() throws Exception {
    assertLeaseAcknowledgement(AppendResult.SUCCESS, 1, true);
  }

  @Test
  @Timeout(10)
  void sameTermInconsistencyRenewsLeaderLease() throws Exception {
    assertLeaseAcknowledgement(AppendResult.INCONSISTENCY, 1, true);
  }

  private static void assertLeaseAcknowledgement(AppendResult result, long term, boolean renewsLease) throws Exception {
    final LeaderState leaderState = mock(LeaderState.class);
    final TestFollowerInfo follower = new TestFollowerInfo(8, 4);
    final Timestamp acknowledged = follower.getLastRespondedAppendEntriesSendTime();
    final LogAppenderDefault appender = spy(newLogAppender(leaderState, follower));
    final RaftServerRpc rpc = mock(RaftServerRpc.class);
    when(appender.getServer().getRaftServer().getServerRpc()).thenReturn(rpc);
    final AppendEntriesRequestProto request = AppendEntriesRequestProto.newBuilder().setLeaderTerm(1).build();
    final AppendEntriesReplyProto reply = AppendEntriesReplyProto.newBuilder()
        .setResult(result).setTerm(term).setNextIndex(8).build();
    when(rpc.appendEntries(any())).thenReturn(reply);
    final AtomicBoolean running = new AtomicBoolean(true);
    doAnswer(invocation -> running.get()).when(appender).isRunning();
    doReturn(true).when(appender).shouldSendAppendEntries();
    doReturn(null).when(appender).shouldInstallSnapshot();
    doReturn(request).when(appender).newAppendEntriesRequest(anyLong(), anyBoolean());
    doAnswer(invocation -> {
      running.set(false);
      return null;
    }).when(leaderState).onAppendEntriesReply(appender, reply);

    appender.run();

    if (renewsLease) {
      Assertions.assertTrue(follower.getLastRespondedAppendEntriesSendTime().compareTo(acknowledged) > 0,
          "A follower accepting the current term must provide a new timestamp for lease renewal");
    } else {
      Assertions.assertEquals(acknowledged, follower.getLastRespondedAppendEntriesSendTime(),
          "A rejected AppendEntries must not provide a new timestamp for renewing the old leader's lease");
    }
    verify(rpc).appendEntries(request);
    if (result == AppendResult.NOT_LEADER) {
      verify(leaderState).onFollowerTerm(follower, term);
    }
  }

  @Test
  void heartbeatSuccessAdvancesOnlyToRequestPreviousIndex() {
    final LeaderState leaderState = mock(LeaderState.class);
    final TestFollowerInfo follower = new TestFollowerInfo(8, 4);
    final LogAppenderDefault appender = newLogAppender(leaderState, follower);
    final AppendEntriesReplyProto reply = newSuccessReply(20);

    appender.handleReply(reply, RaftLog.INVALID_LOG_INDEX, 9, true);

    Assertions.assertEquals(9, follower.getMatchIndex());
    Assertions.assertEquals(10, follower.getNextIndex());
    Assertions.assertEquals(1, follower.successfulMatchIndexUpdates.get());
    Assertions.assertEquals(1, follower.nextIndexIncreases.get());
    verify(leaderState).onFollowerSuccessAppendEntries(follower);
    verify(leaderState).onAppendEntriesReply(appender, reply);
    verifyNoMoreInteractions(leaderState);
  }

  @Test
  void heartbeatSuccessWithoutPreviousLogDoesNotAdvance() {
    final LeaderState leaderState = mock(LeaderState.class);
    final TestFollowerInfo follower = new TestFollowerInfo(8, 4);
    final LogAppenderDefault appender = newLogAppender(leaderState, follower);
    final AppendEntriesReplyProto reply = newSuccessReply(20);

    appender.handleReply(reply, RaftLog.INVALID_LOG_INDEX, RaftLog.INVALID_LOG_INDEX, true);

    Assertions.assertEquals(4, follower.getMatchIndex());
    Assertions.assertEquals(8, follower.getNextIndex());
    Assertions.assertEquals(0, follower.successfulMatchIndexUpdates.get());
    Assertions.assertEquals(0, follower.nextIndexIncreases.get());
    verify(leaderState).onAppendEntriesReply(appender, reply);
    verifyNoMoreInteractions(leaderState);
  }

  @Test
  void appendSuccessStillUsesReplyNextIndex() {
    final LeaderState leaderState = mock(LeaderState.class);
    final TestFollowerInfo follower = new TestFollowerInfo(8, 4);
    final LogAppenderDefault appender = newLogAppender(leaderState, follower);
    final AppendEntriesReplyProto reply = newSuccessReply(20);

    appender.handleReply(reply, 8, 7, false);

    Assertions.assertEquals(19, follower.getMatchIndex());
    Assertions.assertEquals(20, follower.getNextIndex());
    Assertions.assertEquals(1, follower.successfulMatchIndexUpdates.get());
    Assertions.assertEquals(1, follower.nextIndexIncreases.get());
    verify(leaderState).onFollowerSuccessAppendEntries(follower);
    verify(leaderState).onAppendEntriesReply(appender, reply);
    verifyNoMoreInteractions(leaderState);
  }

  @Test
  void noInstallSnapshotWhenFollowerSnapshotCoversPreviousEntry() {
    // RATIS-2500: leader log is [159, 168) with the previous entry 158 purged, and the follower
    // (nextIndex = 159) has a snapshot covering 158 (typically reported via ALREADY_INSTALLED).
    // The leader should append (previous == null is allowed by newAppendEntriesRequest in this case)
    // instead of looping on InstallSnapshot notifications.
    final LeaderState leaderState = mock(LeaderState.class);
    final TestFollowerInfo follower = new TestFollowerInfo(159, 158);
    follower.setSnapshotIndex(158);
    final LogAppenderDefault appender = newLogAppender(leaderState, follower, newRaftLog(168, 159));

    Assertions.assertFalse(appender.shouldInstallSnapshot(true));
  }

  @Test
  void installSnapshotWhenFollowerSnapshotDoesNotCoverPreviousEntry() {
    final LeaderState leaderState = mock(LeaderState.class);
    final TestFollowerInfo follower = new TestFollowerInfo(159, 100);
    follower.setSnapshotIndex(100);
    final LogAppenderDefault appender = newLogAppender(leaderState, follower, newRaftLog(168, 159));

    Assertions.assertTrue(appender.shouldInstallSnapshot(true));
  }

  @Test
  void installSnapshotWhenFollowerNextIndexBelowLeaderStartIndex() {
    final LeaderState leaderState = mock(LeaderState.class);
    final TestFollowerInfo follower = new TestFollowerInfo(150, 149);
    follower.setSnapshotIndex(149);
    final LogAppenderDefault appender = newLogAppender(leaderState, follower, newRaftLog(168, 159));

    Assertions.assertTrue(appender.shouldInstallSnapshot(true));
  }

  private static LogAppenderDefault newLogAppender(LeaderState leaderState, FollowerInfo follower) {
    return newLogAppender(leaderState, follower, mock(RaftLog.class));
  }

  private static LogAppenderDefault newLogAppender(LeaderState leaderState, FollowerInfo follower, RaftLog raftLog) {
    final RaftServer.Division division = mock(RaftServer.Division.class);
    final RaftServer raftServer = mock(RaftServer.class);
    when(division.getRaftServer()).thenReturn(raftServer);
    when(division.getThreadGroup()).thenReturn(Thread.currentThread().getThreadGroup());
    when(raftServer.getProperties()).thenReturn(new RaftProperties());
    when(division.getRaftLog()).thenReturn(raftLog);
    when(division.getStateMachine()).thenReturn(mock(StateMachine.class));
    return new LogAppenderDefault(division, leaderState, follower);
  }

  private static RaftLog newRaftLog(long nextIndex, long startIndex) {
    final RaftLog raftLog = mock(RaftLog.class);
    when(raftLog.getNextIndex()).thenReturn(nextIndex);
    when(raftLog.getStartIndex()).thenReturn(startIndex);
    return raftLog;
  }

  private static AppendEntriesReplyProto newSuccessReply(long nextIndex) {
    return AppendEntriesReplyProto.newBuilder()
        .setResult(AppendEntriesReplyProto.AppendResult.SUCCESS)
        .setNextIndex(nextIndex)
        .build();
  }

  private static final class TestFollowerInfo implements FollowerInfo {
    private final RaftPeer peer = RaftPeer.newBuilder().setId(FOLLOWER_ID).build();
    private final AtomicInteger successfulMatchIndexUpdates = new AtomicInteger();
    private final AtomicInteger nextIndexIncreases = new AtomicInteger();
    private long matchIndex;
    private long nextIndex;
    private long snapshotIndex;
    private Timestamp lastRespondedAppendEntriesSendTime = Timestamp.currentTime().addTimeMs(-10000);

    private TestFollowerInfo(long nextIndex, long matchIndex) {
      this.nextIndex = nextIndex;
      this.matchIndex = matchIndex;
    }

    @Override
    public String getName() {
      return FOLLOWER_ID.toString();
    }

    @Override
    public RaftPeerId getId() {
      return FOLLOWER_ID;
    }

    @Override
    public RaftPeer getPeer() {
      return peer;
    }

    @Override
    public long getMatchIndex() {
      return matchIndex;
    }

    @Override
    public boolean updateMatchIndex(long newMatchIndex) {
      if (newMatchIndex > matchIndex) {
        matchIndex = newMatchIndex;
        successfulMatchIndexUpdates.incrementAndGet();
        return true;
      }
      return false;
    }

    @Override
    public long getAppliedIndex() {
      return 0;
    }

    @Override
    public boolean updateAppliedIndex(long newAppliedIndex) {
      return false;
    }

    @Override
    public long getCommitIndex() {
      return 0;
    }

    @Override
    public boolean updateCommitIndex(long newCommitIndex) {
      return false;
    }

    @Override
    public long getSnapshotIndex() {
      return snapshotIndex;
    }

    @Override
    public void setSnapshotIndex(long newSnapshotIndex) {
      snapshotIndex = newSnapshotIndex;
    }

    @Override
    public void setAttemptedToInstallSnapshot() {
    }

    @Override
    public boolean hasAttemptedToInstallSnapshot() {
      return false;
    }

    @Override
    public long getNextIndex() {
      return nextIndex;
    }

    @Override
    public void increaseNextIndex(long newNextIndex) {
      if (newNextIndex > nextIndex) {
        nextIndex = newNextIndex;
        nextIndexIncreases.incrementAndGet();
      }
    }

    @Override
    public void decreaseNextIndex(long newNextIndex) {
      nextIndex = Math.min(nextIndex, newNextIndex);
    }

    @Override
    public void setNextIndex(long newNextIndex) {
      nextIndex = newNextIndex;
    }

    @Override
    public void updateNextIndex(long newNextIndex) {
      nextIndex = newNextIndex;
    }

    @Override
    public void computeNextIndex(LongUnaryOperator op) {
      nextIndex = op.applyAsLong(nextIndex);
    }

    @Override
    public Timestamp getLastRpcResponseTime() {
      return Timestamp.currentTime();
    }

    @Override
    public Timestamp getLastRpcSendTime() {
      return Timestamp.currentTime();
    }

    @Override
    public void updateLastRpcResponseTime() {
    }

    @Override
    public void updateLastRpcSendTime(boolean isHeartbeat) {
    }

    @Override
    public Timestamp getLastRpcTime() {
      return Timestamp.currentTime();
    }

    @Override
    public Timestamp getLastHeartbeatSendTime() {
      return Timestamp.currentTime();
    }

    @Override
    public Timestamp getLastRespondedAppendEntriesSendTime() {
      return lastRespondedAppendEntriesSendTime;
    }

    @Override
    public void updateLastRespondedAppendEntriesSendTime(Timestamp sendTime) {
      lastRespondedAppendEntriesSendTime = sendTime;
    }

    @Override
    public ErrorState getErrorState() {
      return null;
    }
  }
}
