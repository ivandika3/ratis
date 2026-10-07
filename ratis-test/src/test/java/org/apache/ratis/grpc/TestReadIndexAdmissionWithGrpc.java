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
package org.apache.ratis.grpc;

import org.apache.ratis.BaseTest;
import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.server.GrpcServicesImpl;
import org.apache.ratis.proto.RaftProtos.ReadIndexRequestProto;
import org.apache.ratis.proto.RaftProtos.SlidingWindowEntry;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.ResourceUnavailableException;
import org.apache.ratis.rpc.CallId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.impl.MiniRaftCluster;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.CodeInjectionForTesting;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.ratis.ReadOnlyRequestTests.CounterStateMachine;
import static org.apache.ratis.ReadOnlyRequestTests.INCREMENT;
import static org.apache.ratis.ReadOnlyRequestTests.QUERY;
import static org.apache.ratis.ReadOnlyRequestTests.assertReplyExact;

public class TestReadIndexAdmissionWithGrpc extends BaseTest {
  private static final int WAIT_SECONDS = 10;

  private static CompletableFuture<RaftClientReply> sendReadOnly(RaftClient client, RaftPeerId followerId) {
    final RaftClientRequest request = RaftClientRequest.newBuilder()
        .setClientId(client.getId())
        .setServerId(followerId)
        .setGroupId(client.getGroupId())
        .setCallId(CallId.getAndIncrement())
        .setMessage(QUERY)
        .setType(RaftClientRequest.readRequestType())
        .setSlidingWindowEntry(SlidingWindowEntry.newBuilder().setSeqNum(1).setIsFirst(true).build())
        .build();
    // Bypass client retries to observe the exception reconstructed from gRPC metadata.
    return client.getClientRpc().sendRequestAsync(request);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testFollowerReadIndexAdmission() throws Exception {
    final RaftProperties properties = new RaftProperties();
    properties.setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY, CounterStateMachine.class, StateMachine.class);
    RaftServerConfigKeys.Read.setOption(properties, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
    RaftServerConfigKeys.Read.setLeaderLeaseEnabled(properties, false);
    RaftServerConfigKeys.Read.ReadIndex.Batch.setEnabled(properties, true);
    RaftServerConfigKeys.Read.ReadIndex.Batch.setMaxInFlight(properties, 1);
    properties.setInt("raft.server.read.read-index.batch.element-limit", 1);
    RaftClientConfigKeys.Rpc.setRequestTimeout(properties, TimeDuration.valueOf(WAIT_SECONDS, TimeUnit.SECONDS));

    final CountDownLatch dispatched = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicBoolean holdFirst = new AtomicBoolean(true);
    try (MiniRaftClusterWithGrpc cluster = MiniRaftClusterWithGrpc.FACTORY.newCluster(3, properties)) {
      cluster.start();
      final RaftPeerId leaderId = RaftTestUtil.waitForLeader(cluster).getId();
      final List<RaftServer.Division> followers = cluster.getFollowers();
      Assertions.assertEquals(2, followers.size());
      final RaftServer.Division follower = followers.get(0);
      final RaftPeerId followerId = follower.getId();

      try (RaftClient leaderClient = cluster.createClient(leaderId);
           RaftClient followerClient = cluster.createClient(followerId)) {
        assertReplyExact(1, leaderClient.async().send(INCREMENT).get(WAIT_SECONDS, TimeUnit.SECONDS));
        CodeInjectionForTesting.put(GrpcServicesImpl.GRPC_SEND_SERVER_REQUEST, (local, remote, args) -> {
          if (!followerId.equals(local) || args.length == 0 || !(args[0] instanceof ReadIndexRequestProto)
              || !holdFirst.compareAndSet(true, false)) {
            return false;
          }
          dispatched.countDown();
          try {
            Assertions.assertTrue(release.await(30, TimeUnit.SECONDS), "Timed out holding follower ReadIndex");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted holding follower ReadIndex", e);
          }
          return true;
        });
        try {
          final CompletableFuture<Message> accepted = follower.readOnlyAsync(QUERY);
          Assertions.assertTrue(dispatched.await(WAIT_SECONDS, TimeUnit.SECONDS), "ReadIndex was not dispatched");
          Assertions.assertFalse(accepted.isDone(), "The first read must remain admitted while dispatch is held");

          final CompletableFuture<Message> rejected = follower.readOnlyAsync(QUERY);
          final ExecutionException exception = Assertions.assertThrows(ExecutionException.class,
              () -> rejected.get(WAIT_SECONDS, TimeUnit.SECONDS));
          Assertions.assertInstanceOf(ResourceUnavailableException.class, exception.getCause());

          final CompletableFuture<RaftClientReply> overloaded = sendReadOnly(followerClient, followerId);
          final ExecutionException rpcException = Assertions.assertThrows(ExecutionException.class,
              () -> overloaded.get(WAIT_SECONDS, TimeUnit.SECONDS));
          Assertions.assertInstanceOf(ResourceUnavailableException.class, rpcException.getCause());
          Assertions.assertFalse(accepted.isDone(), "Rejection must not fail the already admitted read");

          // Admission is per division, and the injection must leave other follower reads and heartbeats alone.
          Assertions.assertEquals("1",
              followers.get(1).readOnlyAsync(QUERY).get(WAIT_SECONDS, TimeUnit.SECONDS).getContent().toStringUtf8());

          release.countDown();
          Assertions.assertEquals("1", accepted.get(WAIT_SECONDS, TimeUnit.SECONDS).getContent().toStringUtf8());
          // A member future may complete just before its batch finishes fanout and releases admission capacity.
          JavaUtils.attempt(() -> {
            // An overload closes the raw RPC stream; each recovery attempt needs a fresh client.
            try (RaftClient recoveryClient = cluster.createClient(followerId)) {
              assertReplyExact(1, sendReadOnly(recoveryClient, followerId).get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
          }, 10, HUNDRED_MILLIS, "follower ReadIndex admission recovery", LOG);
        } finally {
          CodeInjectionForTesting.remove(GrpcServicesImpl.GRPC_SEND_SERVER_REQUEST);
          release.countDown();
        }
      }
    }
  }
}
