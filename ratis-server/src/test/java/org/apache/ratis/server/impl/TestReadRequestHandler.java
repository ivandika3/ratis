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
import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadIndexRequestProto;
import org.apache.ratis.proto.RaftProtos.ReadRequestTypeProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.leader.LogAppender;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.util.LifeCycle;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TestReadRequestHandler extends BaseTest {
  @Test
  public void testReadIndexReplyDoesNotTriggerHeartbeat() throws Exception {
    final RaftProperties properties = new RaftProperties();
    final RaftPeerId leaderId = RaftPeerId.valueOf("s0");
    final RaftPeerId followerId = RaftPeerId.valueOf("s1");
    final RaftGroupId groupId = RaftGroupId.randomId();
    final RaftGroupMemberId leaderMemberId = RaftGroupMemberId.valueOf(leaderId, groupId);
    final RaftServerImpl server = Mockito.mock(RaftServerImpl.class);
    final ServerState state = Mockito.mock(ServerState.class);
    final RoleInfo role = Mockito.mock(RoleInfo.class);
    final LeaderStateImpl leader = Mockito.mock(LeaderStateImpl.class);
    final DivisionInfo info = Mockito.mock(DivisionInfo.class);
    final RaftLog log = Mockito.mock(RaftLog.class);
    Mockito.when(server.getInfo()).thenReturn(info);
    Mockito.when(info.getLifeCycleState()).thenReturn(LifeCycle.State.RUNNING);
    Mockito.when(state.getLog()).thenReturn(log);
    Mockito.when(log.getLastCommittedIndex()).thenReturn(11L);
    Mockito.when(log.getTermIndex(10)).thenReturn(TermIndex.valueOf(5, 10));
    final LogAppender appender = Mockito.mock(LogAppender.class);
    Mockito.when(server.getId()).thenReturn(leaderId);
    Mockito.when(server.getState()).thenReturn(state);
    Mockito.when(server.getStateMachine()).thenReturn(new BaseStateMachine());
    Mockito.when(server.getRole()).thenReturn(role);
    Mockito.when(server.getWriteIndexCache()).thenReturn(new WriteIndexCache(properties));
    Mockito.when(state.getMemberId()).thenReturn(leaderMemberId);
    Mockito.when(role.getLeaderState()).thenReturn(Optional.of(leader));
    Mockito.when(leader.getReadIndex(null)).thenReturn(CompletableFuture.completedFuture(10L));
    Mockito.when(leader.getLogAppender(followerId)).thenReturn(Optional.of(appender));

    final ReadRequestHandler handler = new ReadRequestHandler(server, properties);
    final ReadIndexRequestProto request = ServerProtoUtils.toReadIndexRequestProto(ClientId.randomId(),
        ReadRequestTypeProto.getDefaultInstance(), RaftGroupMemberId.valueOf(followerId, groupId), leaderId);
    final ReadIndexReplyProto reply = handler.readIndexAsync(request).get(10, TimeUnit.SECONDS);
    Assertions.assertTrue(reply.getServerReply().getSuccess());
    Assertions.assertEquals(10, reply.getReadIndex());
    Assertions.assertEquals(TermIndex.valueOf(5, 10).toProto(), reply.getCommitted());
    Mockito.verify(appender, Mockito.never()).triggerHeartbeat();

    // A read-after-write index can be ahead of commitment; it must not be advertised as committed.
    Mockito.when(log.getLastCommittedIndex()).thenReturn(9L);
    Assertions.assertFalse(handler.readIndexAsync(request).get(10, TimeUnit.SECONDS).hasCommitted());
    // A committed entry may already have been purged. Keep the read index without a commit hint.
    Mockito.when(log.getLastCommittedIndex()).thenReturn(11L);
    Mockito.when(log.getTermIndex(10)).thenReturn(null);
    Assertions.assertFalse(handler.readIndexAsync(request).get(10, TimeUnit.SECONDS).hasCommitted());
    Mockito.verify(appender, Mockito.never()).triggerHeartbeat();
  }
}
