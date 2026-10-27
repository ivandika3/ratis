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
import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.proto.RaftProtos.RaftRpcReplyProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.ReadException;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TestBoundedReadRequestHandler {
  @Test
  public void testOldLeaderCannotSilentlyWeakenFallback() throws Exception {
    final RaftServerImpl server = Mockito.mock(RaftServerImpl.class, Mockito.RETURNS_DEEP_STUBS);
    final RaftGroupMemberId member = RaftGroupMemberId.valueOf(RaftPeerId.valueOf("s1"), RaftGroupId.randomId());
    Mockito.when(server.getState().getMemberId()).thenReturn(member);
    Mockito.when(server.getId()).thenReturn(member.getPeerId());
    final org.apache.ratis.server.metrics.RaftServerMetricsImpl metrics =
        new org.apache.ratis.server.metrics.RaftServerMetricsImpl(member, id -> 0,
            () -> Mockito.mock(org.apache.ratis.server.RetryCache.Statistics.class));
    Mockito.when(server.getRaftServerMetrics()).thenReturn(metrics);
    Mockito.when(server.getState().getStateMachineUpdater().getAppliedLogPrefix()).thenReturn(new AppliedLogPrefix(0));
    Mockito.when(server.getRole().getLeaderState()).thenReturn(Optional.empty());
    Mockito.when(server.getInfo().getLeaderId()).thenReturn(RaftPeerId.valueOf("s0"));
    Mockito.when(server.getSnapshotInstallationHandler().getInProgressInstallSnapshotIndex())
        .thenReturn(RaftLog.INVALID_LOG_INDEX);
    Mockito.when(server.getServerRpc().async().readIndexAsync(Mockito.any())).thenReturn(
        CompletableFuture.completedFuture(ReadIndexReplyProto.newBuilder().setReadIndex(0)
            .setServerReply(RaftRpcReplyProto.newBuilder().setSuccess(true)).build()));
    final RaftClientRequest request = RaftClientRequest.newBuilder().setClientId(ClientId.randomId())
        .setServerId(member.getPeerId()).setGroupId(member.getGroupId()).setMessage(Message.EMPTY)
        .setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ZERO)).build();
    Mockito.when(server.newExceptionReply(Mockito.eq(request), Mockito.any(ReadException.class)))
        .thenAnswer(call -> RaftClientReply.newBuilder().setRequest(request).setException(call.getArgument(1)).build());
    final ReadRequestHandler handler = new ReadRequestHandler(server, new RaftProperties());
    final RaftClientReply reply = handler.boundedStaleReadAsync(request, Runnable::run).get(5, TimeUnit.SECONDS);
    Assertions.assertFalse(reply.isSuccess());
    Assertions.assertTrue(reply.getException().getMessage().contains("does not support validated ReadIndex"));
    Mockito.verify(server.getStateMachine(), Mockito.never()).query(Mockito.any());
    metrics.unregister();
  }

  @Test
  public void testQueueingConsumesDeadline() throws Exception {
    final RaftServerImpl server = Mockito.mock(RaftServerImpl.class, Mockito.RETURNS_DEEP_STUBS);
    final RaftGroupMemberId member = RaftGroupMemberId.valueOf(RaftPeerId.valueOf("s1"), RaftGroupId.randomId());
    Mockito.when(server.getState().getMemberId()).thenReturn(member);
    Mockito.when(server.getId()).thenReturn(member.getPeerId());
    final org.apache.ratis.server.metrics.RaftServerMetricsImpl metrics =
        new org.apache.ratis.server.metrics.RaftServerMetricsImpl(member, id -> 0,
            () -> Mockito.mock(org.apache.ratis.server.RetryCache.Statistics.class));
    Mockito.when(server.getRaftServerMetrics()).thenReturn(metrics);
    final RaftClientRequest request = RaftClientRequest.newBuilder().setClientId(ClientId.randomId())
        .setServerId(member.getPeerId()).setGroupId(member.getGroupId()).setMessage(Message.EMPTY).setTimeoutMs(10)
        .setType(RaftClientRequest.boundedStaleReadRequestType(0, TimeDuration.ZERO)).build();
    Mockito.when(server.newExceptionReply(Mockito.eq(request), Mockito.any(ReadException.class)))
        .thenAnswer(call -> RaftClientReply.newBuilder().setRequest(request).setException(call.getArgument(1)).build());
    final List<Runnable> queued = new ArrayList<>();
    final ReadRequestHandler handler = new ReadRequestHandler(server, new RaftProperties());
    final CompletableFuture<RaftClientReply> future = handler.boundedStaleReadAsync(request, queued::add);
    final RaftClientReply reply = future.get(5, TimeUnit.SECONDS);
    Assertions.assertFalse(reply.isSuccess());
    queued.get(0).run();
    Assertions.assertSame(reply, future.join());
    Mockito.verify(server.getStateMachine(), Mockito.never()).query(Mockito.any());
    metrics.unregister();
  }
}
