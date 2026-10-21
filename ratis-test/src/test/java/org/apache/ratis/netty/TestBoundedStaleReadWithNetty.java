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
package org.apache.ratis.netty;

import org.apache.ratis.BoundedStaleReadTests;
import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.ReadOnlyRequestTests;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.netty.server.NettyRpcService;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

public class TestBoundedStaleReadWithNetty extends BoundedStaleReadTests<MiniRaftClusterWithNetty>
    implements MiniRaftClusterWithNetty.FactoryGet {
  @Test
  public void testFallbackReconnectsAfterConnectionClose() throws Exception {
    runWithNewCluster(3, cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final RaftServer.Division follower = cluster.getFollowers().get(0);
      final RaftServer serving = cluster.getServer(follower.getId());
      try (RaftClient client = cluster.createClient()) {
        client.io().send(ReadOnlyRequestTests.INCREMENT);
        ReadOnlyRequestTests.assertReplyExact(1, client.io().sendStaleRead(ReadOnlyRequestTests.QUERY,
            0, TimeDuration.ZERO, follower.getId()));
        final NettyRpcService rpc = (NettyRpcService) serving.getServerRpc();
        rpc.getProxies().getProxy(leader.getId()).close();
        final RaftClientRequest request = RaftClientRequest.newBuilder().setClientId(ClientId.randomId())
            .setServerId(follower.getId()).setGroupId(cluster.getGroupId()).setMessage(ReadOnlyRequestTests.QUERY)
            .setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ZERO)).build();
        final boolean failed = serving.submitClientRequestAsync(request)
            .handle((reply, error) -> error != null || !reply.isSuccess()).get(5, TimeUnit.SECONDS);
        Assertions.assertTrue(failed);
        final RaftClientReply recovered = serving.submitClientRequestAsync(request).get(5, TimeUnit.SECONDS);
        ReadOnlyRequestTests.assertReplyExact(1, recovered);
      }
    });
  }
}
