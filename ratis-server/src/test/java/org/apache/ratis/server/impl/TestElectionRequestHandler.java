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

import org.apache.ratis.proto.RaftProtos.RaftPeerRole;
import org.apache.ratis.proto.RaftProtos.RaftRpcReplyProto;
import org.apache.ratis.proto.RaftProtos.StartLeaderElectionRequestProto;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.protocol.TermIndex;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestElectionRequestHandler {
  enum StartLeaderElectionResult {
    MISSING_LAST_ENTRY,
    UNRECOGNIZED_LEADER,
    NOT_FOLLOWER,
    BEHIND_LEADER,
    ACCEPTED
  }

  @ParameterizedTest
  @EnumSource(StartLeaderElectionResult.class)
  public void testStartLeaderElectionReplyCallId(StartLeaderElectionResult result) throws Exception {
    final RaftGroupId groupId = RaftGroupId.randomId();
    final RaftGroupMemberId leader = RaftGroupMemberId.valueOf(RaftPeerId.valueOf("s0"), groupId);
    final RaftGroupMemberId follower = RaftGroupMemberId.valueOf(RaftPeerId.valueOf("s1"), groupId);
    final TermIndex leaderLastEntry = TermIndex.valueOf(1, 10);
    final RaftServerImpl server = Mockito.mock(RaftServerImpl.class);
    final ServerState state = Mockito.mock(ServerState.class);
    final DivisionInfo info = Mockito.mock(DivisionInfo.class);
    final RoleInfo role = Mockito.mock(RoleInfo.class);
    Mockito.when(server.getId()).thenReturn(follower.getPeerId());
    Mockito.when(server.getState()).thenReturn(state);
    Mockito.when(server.getInfo()).thenReturn(info);
    Mockito.when(server.getRole()).thenReturn(role);
    Mockito.when(role.getCurrentRole()).thenReturn(RaftPeerRole.LEADER);
    Mockito.when(info.isFollower()).thenReturn(result != StartLeaderElectionResult.NOT_FOLLOWER);
    Mockito.when(state.getMemberId()).thenReturn(follower);
    Mockito.when(state.recognizeLeader("startLeaderElection", leader.getPeerId(), leaderLastEntry.getTerm()))
        .thenReturn(result != StartLeaderElectionResult.UNRECOGNIZED_LEADER);
    Mockito.when(state.getLastEntry()).thenReturn(result == StartLeaderElectionResult.BEHIND_LEADER
        ? TermIndex.valueOf(1, 9) : leaderLastEntry);

    // Use an explicit nonzero ID so this checks replies independently of request ID allocation.
    final long callId = 12345;
    final StartLeaderElectionRequestProto.Builder request = ServerProtoUtils.toStartLeaderElectionRequestProto(
        leader, follower.getPeerId(), leaderLastEntry).toBuilder();
    request.getServerRequestBuilder().setCallId(callId);
    if (result == StartLeaderElectionResult.MISSING_LAST_ENTRY) {
      request.clearLeaderLastEntry();
    }

    final RaftRpcReplyProto reply = new ElectionRequestHandler(server).startLeaderElection(request.build())
        .getServerReply();
    assertAll(
        () -> assertEquals(callId, reply.getCallId(), "Every reply must correlate with its request"),
        () -> assertEquals(result == StartLeaderElectionResult.ACCEPTED, reply.getSuccess()),
        () -> assertEquals(leader.getPeerId().toByteString(), reply.getRequestorId()),
        () -> assertEquals(follower.getPeerId().toByteString(), reply.getReplyId()));
  }
}
