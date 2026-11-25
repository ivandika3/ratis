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
import org.apache.ratis.proto.RaftProtos.ReadRequestTypeProto;
import org.apache.ratis.proto.RaftProtos.RaftRpcReplyProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.ResourceUnavailableException;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.protocol.RaftServerAsynchronousProtocol;
import org.apache.ratis.statemachine.StateMachine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReadIndexRouting {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testAdmissionDoesNotCoverDownstreamReadStages(boolean waitingForAppliedIndex) throws Exception {
    final Routing routing = new Routing(RaftClientRequest.readRequestType().getRead(), 1);
    when(routing.role.getCurrentRole()).thenReturn(RaftPeerRole.FOLLOWER);
    final Message query = Message.valueOf("query");
    final Message answer = Message.valueOf("answer");
    final ReadRequests readRequests = new ReadRequests(waitingForAppliedIndex ? 9 : 10, new RaftProperties());
    final ServerState state = routing.state;
    when(state.getReadRequests()).thenReturn(readRequests);
    final StateMachine stateMachine = routing.stateMachine;
    final CompletableFuture<Message> queryFuture = new CompletableFuture<>();
    when(stateMachine.query(query)).thenReturn(queryFuture);
    routing.setField("readOption", RaftServerConfigKeys.Read.Option.LINEARIZABLE);

    final CompletableFuture<Message> first =
        routing.handler.readOnlyAsync(routing.clientId, routing.readRequestType, query);
    while (!routing.tasks.isEmpty()) {
      routing.tasks.remove().run();
    }
    Assertions.assertFalse(first.isDone());
    final CompletableFuture<Message> second =
        routing.handler.readOnlyAsync(routing.clientId, routing.readRequestType, query);
    Assertions.assertFalse(second.isCompletedExceptionally(), "The earlier read must not retain ReadIndex capacity.");
    while (!routing.tasks.isEmpty()) {
      routing.tasks.remove().run();
    }
    Assertions.assertFalse(first.isDone());
    Assertions.assertFalse(second.isDone());
    if (waitingForAppliedIndex) {
      verify(stateMachine, times(0)).query(any());
      readRequests.getAppliedIndexConsumer().accept(10);
    }
    queryFuture.complete(answer);
    Assertions.assertSame(answer, first.get(5, TimeUnit.SECONDS));
    Assertions.assertSame(answer, second.get(5, TimeUnit.SECONDS));
    verify(stateMachine, times(2)).query(query);
  }

  @Test
  void testOverloadPropagatesThroughLocalAndRpcReads() throws Exception {
    final Routing routing = new Routing(RaftClientRequest.readRequestType().getRead(), 1);
    when(routing.role.getCurrentRole()).thenReturn(RaftPeerRole.FOLLOWER);
    final Message query = Message.valueOf("query");
    final Message answer = Message.valueOf("answer");
    final ServerState state = routing.state;
    when(state.getReadRequests()).thenReturn(new ReadRequests(10, new RaftProperties()));
    final StateMachine stateMachine = routing.stateMachine;
    when(stateMachine.query(query)).thenReturn(CompletableFuture.completedFuture(answer));
    routing.setField("readOption", RaftServerConfigKeys.Read.Option.LINEARIZABLE);

    final CompletableFuture<Message> first =
        routing.handler.readOnlyAsync(routing.clientId, routing.readRequestType, query);
    final CompletableFuture<Message> localRejected =
        routing.handler.readOnlyAsync(routing.clientId, routing.readRequestType, query);
    Assertions.assertTrue(localRejected.isCompletedExceptionally());
    final ExecutionException localFailure = Assertions.assertThrows(ExecutionException.class,
        () -> localRejected.get(5, TimeUnit.SECONDS));
    Assertions.assertInstanceOf(ResourceUnavailableException.class, localFailure.getCause());

    final RaftClientRequest request = RaftClientRequest.newBuilder().setClientId(routing.clientId)
        .setServerId(routing.id).setGroupId(routing.server.getMemberId().getGroupId()).setCallId(1)
        .setType(RaftClientRequest.readRequestType()).setMessage(query).build();
    final CompletableFuture<RaftClientReply> rpcRejected = routing.handler.readAsync(request);
    Assertions.assertTrue(rpcRejected.isCompletedExceptionally());
    final ExecutionException rpcFailure = Assertions.assertThrows(ExecutionException.class,
        () -> rpcRejected.get(5, TimeUnit.SECONDS));
    Assertions.assertInstanceOf(ResourceUnavailableException.class, rpcFailure.getCause());
    Assertions.assertFalse(first.isDone());
    verify(routing.rpc, times(0)).readIndexAsync(any());

    while (!routing.tasks.isEmpty()) {
      routing.tasks.remove().run();
    }
    Assertions.assertSame(answer, first.get(5, TimeUnit.SECONDS));
    final CompletableFuture<Message> next =
        routing.handler.readOnlyAsync(routing.clientId, routing.readRequestType, query);
    Assertions.assertFalse(next.isDone());
    while (!routing.tasks.isEmpty()) {
      routing.tasks.remove().run();
    }
    Assertions.assertSame(answer, next.get(5, TimeUnit.SECONDS));
  }

  @Test
  void testReadAfterWriteStillBypassesExhaustedAdmission() throws Exception {
    final Routing routing = new Routing(RaftClientRequest.readRequestType().getRead(), 1);
    when(routing.role.getCurrentRole()).thenReturn(RaftPeerRole.FOLLOWER);
    final CompletableFuture<ReadIndexReplyProto> first = routing.send("sendReadIndexAsync");
    final CompletableFuture<ReadIndexReplyProto> rejected = routing.send("sendReadIndexAsync");
    final ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
        () -> rejected.get(5, TimeUnit.SECONDS));
    Assertions.assertInstanceOf(ResourceUnavailableException.class, failure.getCause());

    final Method send = ReadRequestHandler.class.getDeclaredMethod(
        "sendReadIndexAsync", ClientId.class, ReadRequestTypeProto.class);
    send.setAccessible(true);
    @SuppressWarnings("unchecked")
    final CompletableFuture<ReadIndexReplyProto> bypass = (CompletableFuture<ReadIndexReplyProto>) send.invoke(
        routing.handler, routing.clientId, RaftClientRequest.readAfterWriteConsistentRequestType().getRead());
    Assertions.assertEquals(10, bypass.get(5, TimeUnit.SECONDS).getReadIndex());
    verify(routing.rpc).readIndexAsync(any());
    Assertions.assertFalse(first.isDone());
    while (!routing.tasks.isEmpty()) {
      routing.tasks.remove().run();
    }
    Assertions.assertEquals(10, first.get(5, TimeUnit.SECONDS).getReadIndex());
  }

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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testReadAfterWriteBypassesBatching(boolean global) throws Exception {
    final ReadRequestTypeProto readRequestType = RaftClientRequest.readAfterWriteConsistentRequestType().getRead()
        .toBuilder().setGlobal(global).build();
    final Routing routing = new Routing(readRequestType);
    when(routing.role.getCurrentRole()).thenReturn(RaftPeerRole.FOLLOWER);

    Assertions.assertEquals(10, routing.send("sendReadIndexAsync").get(5, TimeUnit.SECONDS).getReadIndex());
    Assertions.assertTrue(routing.tasks.isEmpty());
    final ArgumentCaptor<ReadIndexRequestProto> request = ArgumentCaptor.forClass(ReadIndexRequestProto.class);
    verify(routing.rpc).readIndexAsync(request.capture());
    Assertions.assertEquals(routing.clientId.toByteString(),
        request.getValue().getClientRequest().getRpcRequest().getRequestorId());
    Assertions.assertEquals(readRequestType, request.getValue().getClientRequest().getRead());
  }

  @Test
  void testQueuedBatchUsesLocalLeaderAfterPromotion() throws Exception {
    final Routing routing = new Routing();
    when(routing.role.getCurrentRole()).thenReturn(RaftPeerRole.FOLLOWER);
    final CompletableFuture<ReadIndexReplyProto> read = routing.send("sendReadIndexAsync");
    Assertions.assertFalse(read.isDone());
    verify(routing.rpc, times(0)).readIndexAsync(any());

    final LeaderStateImpl leader = mock(LeaderStateImpl.class);
    when(leader.getReadIndex(null)).thenReturn(CompletableFuture.completedFuture(20L));
    synchronized (routing.server) {
      when(routing.role.getCurrentRole()).thenReturn(RaftPeerRole.LEADER);
      when(routing.role.getLeaderState()).thenReturn(Optional.of(leader));
      when(routing.info.getLeaderId()).thenReturn(routing.id);
    }
    while (!routing.tasks.isEmpty()) {
      routing.tasks.remove().run();
    }
    Assertions.assertEquals(20, read.get(5, TimeUnit.SECONDS).getReadIndex());
    verify(leader).getReadIndex(null);
    verify(routing.rpc, times(0)).readIndexAsync(any());
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
    private final ServerState state = mock(ServerState.class);
    private final StateMachine stateMachine = mock(StateMachine.class);
    private final RoleInfo role = mock(RoleInfo.class);
    private final DivisionInfo info = mock(DivisionInfo.class);
    private final RaftServerAsynchronousProtocol rpc = mock(RaftServerAsynchronousProtocol.class);
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final ClientId clientId = ClientId.randomId();
    private final ReadRequestTypeProto readRequestType;
    private final ReadRequestHandler handler;

    Routing() throws Exception {
      this(RaftClientRequest.readRequestType().getRead());
    }

    Routing(ReadRequestTypeProto readRequestType) throws Exception {
      this(readRequestType, 4096);
    }

    Routing(ReadRequestTypeProto readRequestType, int elementLimit) throws Exception {
      this.readRequestType = readRequestType;
      final RaftGroupId groupId = RaftGroupId.randomId();
      final RaftGroupMemberId memberId = RaftGroupMemberId.valueOf(id, groupId);
      when(server.getId()).thenReturn(id);
      when(server.getMemberId()).thenReturn(memberId);
      when(server.getInfo()).thenReturn(info);
      when(server.getRole()).thenReturn(role);
      when(info.getLeaderId()).thenReturn(remoteId);
      when(role.getLeaderState()).thenReturn(Optional.empty());
      final RaftServerRpc serverRpc = mock(RaftServerRpc.class);
      when(server.getServerRpc()).thenReturn(serverRpc);
      when(serverRpc.async()).thenReturn(rpc);
      final ReadIndexReplyProto reply = ReadIndexReplyProto.newBuilder().setReadIndex(10)
          .setServerReply(RaftRpcReplyProto.newBuilder().setSuccess(true)).build();
      when(rpc.readIndexAsync(any())).thenReturn(CompletableFuture.completedFuture(reply));

      final SnapshotInstallationHandler snapshot = mock(SnapshotInstallationHandler.class);
      when(snapshot.getInProgressInstallSnapshotIndex()).thenReturn(RaftLog.INVALID_LOG_INDEX);
      final WriteIndexCache writeIndexCache = new WriteIndexCache(new RaftProperties());
      when(server.getSnapshotInstallationHandler()).thenReturn(snapshot);
      when(server.getWriteIndexCache()).thenReturn(writeIndexCache);
      when(server.getState()).thenReturn(state);
      when(state.getMemberId()).thenReturn(memberId);
      when(server.getStateMachine()).thenReturn(stateMachine);
      handler = new ReadRequestHandler(server, new RaftProperties());
      setField("readIndexBatching", new ReadIndexBatching(tasks::add, elementLimit, 1, (clientId, type) -> {
        try {
          return send("sendReadIndexAsyncImpl", clientId, type);
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(e);
        }
      }));
    }

    private void setField(String name, Object value) throws Exception {
      final Field field = ReadRequestHandler.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(handler, value);
    }

    CompletableFuture<ReadIndexReplyProto> send(String name) throws Exception {
      return send(name, clientId, readRequestType);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<ReadIndexReplyProto> send(String name, ClientId clientId, ReadRequestTypeProto type)
        throws ReflectiveOperationException {
      final Method method = ReadRequestHandler.class.getDeclaredMethod(name, ClientId.class, ReadRequestTypeProto.class);
      method.setAccessible(true);
      return (CompletableFuture<ReadIndexReplyProto>) method.invoke(handler, clientId, type);
    }
  }
}
