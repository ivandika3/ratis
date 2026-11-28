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

import org.apache.ratis.proto.RaftProtos.StartLeaderElectionRequestProto;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.protocol.TermIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class TestServerProtoUtils {
  @Test
  public void testStartLeaderElectionRequestCallIds() {
    final RaftGroupMemberId leader = RaftGroupMemberId.valueOf(RaftPeerId.valueOf("s0"), RaftGroupId.randomId());
    final RaftPeerId follower = RaftPeerId.valueOf("s1");
    final TermIndex lastEntry = TermIndex.valueOf(1, 10);
    final StartLeaderElectionRequestProto first = ServerProtoUtils.toStartLeaderElectionRequestProto(
        leader, follower, lastEntry);
    final StartLeaderElectionRequestProto second = ServerProtoUtils.toStartLeaderElectionRequestProto(
        leader, follower, lastEntry);

    assertNotEquals(first.getServerRequest().getCallId(), second.getServerRequest().getCallId(),
        "Overlapping leadership-transfer requests must have distinct call IDs");
  }
}
