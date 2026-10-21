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

import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.RaftRpcReplyProto;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.leader.LogAppender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;

public class TestFreshReadIndexHeartbeats {
  @Test
  public void testEachReadRequiresNewAcknowledgements() {
    final ReadIndexHeartbeats heartbeats = new ReadIndexHeartbeats();
    final LogAppender appender = Mockito.mock(LogAppender.class);
    final RaftPeerId peer = RaftPeerId.valueOf("s1");
    Mockito.when(appender.getFollowerId()).thenReturn(peer);
    Mockito.when(appender.getCallIdComparator()).thenReturn(Long::compare);
    Mockito.when(appender.getCallId()).thenReturn(10L);
    final ReadIndexHeartbeats.AppendEntriesListener first =
        heartbeats.addFreshAppendEntriesListener(1, Collections.singletonList(appender),
            acknowledged -> acknowledged.test(peer));
    final AppendEntriesReplyProto reply = AppendEntriesReplyProto.newBuilder().setFollowerCommit(1)
        .setServerReply(RaftRpcReplyProto.newBuilder().setSuccess(true).setCallId(10)).build();
    heartbeats.onAppendEntriesReply(appender, reply, acknowledged -> acknowledged.test(peer));
    Assertions.assertEquals(1L, first.getFuture().join());
    Mockito.when(appender.getCallId()).thenReturn(11L);
    final ReadIndexHeartbeats.AppendEntriesListener second =
        heartbeats.addFreshAppendEntriesListener(1, Collections.singletonList(appender),
            acknowledged -> acknowledged.test(peer));
    heartbeats.onAppendEntriesReply(appender, reply, acknowledged -> acknowledged.test(peer));
    Assertions.assertFalse(second.getFuture().isDone());
    heartbeats.onAppendEntriesReply(appender, reply.toBuilder()
        .setServerReply(reply.getServerReply().toBuilder().setCallId(11)).build(),
        acknowledged -> acknowledged.test(peer));
    Assertions.assertEquals(1L, second.getFuture().join());
  }
}
