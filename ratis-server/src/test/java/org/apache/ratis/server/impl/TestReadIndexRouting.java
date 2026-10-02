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

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.RaftPeerRole;
import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadIndexRequestProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.protocol.RaftServerAsynchronousProtocol;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReadIndexRouting {
  @ParameterizedTest
  @EnumSource(value = RaftPeerRole.class, names = {"FOLLOWER", "CANDIDATE", "LISTENER"})
  void testOnlyFollowersAreBatched(RaftPeerRole currentRole) throws Exception {
    final Routing routing = new Routing();
    when(routing.role.getCurrentRole()).thenReturn(currentRole);
    final CompletableFuture<ReadIndexReplyProto> reply = routing.send("sendReadIndexAsync");

    final boolean batched = currentRole == RaftPeerRole.FOLLOWER;
    Assertions.assertEquals(batched ? 1 : 0, routing.tasks.size());
    verify(routing.rpc, times(batched ? 0 : 1)).readIndexAsync(any());
    while (!routing.tasks.isEmpty()) {
      routing.tasks.remove().run();
    }
    Assertions.assertEquals(10, reply.get(5, TimeUnit.SECONDS).getReadIndex());
  }

  @Test
  void testPromotionCannotProduceSelfDirectedRpc() throws Exception {
    final Routing routing = new Routing();
    final AtomicReference<LeaderStateImpl> localLeader = new AtomicReference<>();
    final AtomicReference<RaftPeerId> leaderId = new AtomicReference<>(routing.remoteId);
    final CountDownLatch readingLeader = new CountDownLatch(1);
    final CountDownLatch promoted = new CountDownLatch(1);
    final LeaderStateImpl leader = mock(LeaderStateImpl.class);
    when(leader.getReadIndex(null)).thenReturn(CompletableFuture.completedFuture(20L));
    when(routing.info.getLeaderId()).thenAnswer(invocation -> leaderId.get());
    when(routing.role.getLeaderState()).thenAnswer(invocation -> {
      final LeaderStateImpl snapshot = localLeader.get();
      readingLeader.countDown();
      // Without the transition monitor, promotion can invalidate this snapshot before leaderId is read.
      if (!Thread.holdsLock(routing.server)) {
        Assertions.assertTrue(promoted.await(5, TimeUnit.SECONDS));
      }
      return Optional.ofNullable(snapshot);
    });

    final CompletableFuture<Void> promotion = CompletableFuture.runAsync(() -> {
      try {
        Assertions.assertTrue(readingLeader.await(5, TimeUnit.SECONDS));
        synchronized (routing.server) {
          localLeader.set(leader);
          leaderId.set(routing.id);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      } finally {
        promoted.countDown();
      }
    });
    try {
      Assertions.assertEquals(10, routing.send("sendReadIndexAsyncImpl").get(5, TimeUnit.SECONDS).getReadIndex());
      promotion.get(5, TimeUnit.SECONDS);
      final ArgumentCaptor<ReadIndexRequestProto> request = ArgumentCaptor.forClass(ReadIndexRequestProto.class);
      verify(routing.rpc).readIndexAsync(request.capture());
      Assertions.assertEquals(routing.remoteId.toByteString(), request.getValue().getServerRequest().getReplyId());
      Assertions.assertEquals(20, routing.send("sendReadIndexAsyncImpl").get(5, TimeUnit.SECONDS).getReadIndex());
      verify(routing.rpc, times(1)).readIndexAsync(any());
    } finally {
      readingLeader.countDown();
      promotion.get(5, TimeUnit.SECONDS);
    }
  }

  private static class Routing {
    private final RaftPeerId id = RaftPeerId.valueOf("follower");
    private final RaftPeerId remoteId = RaftPeerId.valueOf("leader");
    private final RaftServerImpl server = mock(RaftServerImpl.class);
    private final RoleInfo role = mock(RoleInfo.class);
    private final DivisionInfo info = mock(DivisionInfo.class);
    private final RaftServerAsynchronousProtocol rpc = mock(RaftServerAsynchronousProtocol.class);
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final RaftClientRequest request;

    Routing() throws Exception {
      final RaftGroupId groupId = RaftGroupId.randomId();
      request = RaftClientRequest.newBuilder()
          .setClientId(ClientId.randomId())
          .setServerId(id)
          .setGroupId(groupId)
          .setType(RaftClientRequest.readRequestType())
          .build();
      when(server.getId()).thenReturn(id);
      when(server.getMemberId()).thenReturn(RaftGroupMemberId.valueOf(id, groupId));
      when(server.getInfo()).thenReturn(info);
      when(info.getLeaderId()).thenReturn(remoteId);
      when(role.getLeaderState()).thenReturn(Optional.empty());
      final RaftServerRpc serverRpc = mock(RaftServerRpc.class);
      when(server.getServerRpc()).thenReturn(serverRpc);
      when(serverRpc.async()).thenReturn(rpc);
      final ReadIndexReplyProto reply = ReadIndexReplyProto.newBuilder().setReadIndex(10).build();
      when(rpc.readIndexAsync(any())).thenReturn(CompletableFuture.completedFuture(reply));

      final SnapshotInstallationHandler snapshot = mock(SnapshotInstallationHandler.class);
      when(snapshot.getInProgressInstallSnapshotIndex()).thenReturn(RaftLog.INVALID_LOG_INDEX);
      final WriteIndexCache writeIndexCache = new WriteIndexCache(new RaftProperties());
      setField("role", role);
      setField("snapshotInstallationHandler", snapshot);
      setField("writeIndexCache", writeIndexCache);
      setField("readIndexBatching", new ReadIndexBatching(tasks::add, 2, 1,
          ignored -> CompletableFuture.completedFuture(reply)));
    }

    private void setField(String name, Object value) throws Exception {
      final Field field = RaftServerImpl.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(server, value);
    }

    @SuppressWarnings("unchecked")
    CompletableFuture<ReadIndexReplyProto> send(String name) throws Exception {
      final Method method = RaftServerImpl.class.getDeclaredMethod(name, RaftClientRequest.class);
      method.setAccessible(true);
      return (CompletableFuture<ReadIndexReplyProto>) method.invoke(server, request);
    }
  }
}
