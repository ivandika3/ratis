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

import org.apache.ratis.client.impl.ClientProtoUtils;
import org.apache.ratis.client.impl.OrderedAsync;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.ReadIndexReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadIndexRequestProto;
import org.apache.ratis.proto.RaftProtos.ReadRequestTypeProto;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.LeaderNotReadyException;
import org.apache.ratis.protocol.exceptions.ReadException;
import org.apache.ratis.protocol.exceptions.ReadIndexException;
import org.apache.ratis.protocol.exceptions.StaleReadException;
import org.apache.ratis.protocol.exceptions.StateMachineException;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.leader.LogAppender;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.LifeCycle;
import org.apache.ratis.util.TimeDuration;
import org.apache.ratis.util.TimeoutExecutor;
import org.apache.ratis.util.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.apache.ratis.server.impl.ServerProtoUtils.toReadIndexReplyProto;
import static org.apache.ratis.server.impl.ServerProtoUtils.toReadIndexRequestProto;

/** Handle read requests, including stale reads, linearizable reads and readIndex, for a {@link RaftServerImpl}. */
class ReadRequestHandler {
  static final Logger LOG = LoggerFactory.getLogger(ReadRequestHandler.class);

  private final RaftServerImpl server;
  private final ServerState state;
  private final StateMachine stateMachine;
  private final RaftServerConfigKeys.Read.Option readOption;
  private final TimeDuration readTimeout;
  private final Long clockUncertaintyMs;

  private final CompletableFuture<RaftClientReply> dummySuccessReply;

  ReadRequestHandler(RaftServerImpl server, RaftProperties properties) {
    this.server = server;
    this.state = server.getState();
    this.stateMachine = server.getStateMachine();
    this.readOption = RaftServerConfigKeys.Read.option(properties);
    this.readTimeout = RaftServerConfigKeys.Read.timeout(properties);
    final TimeDuration uncertainty = RaftServerConfigKeys.Read.clockUncertainty(properties);
    if (uncertainty == null) {
      clockUncertaintyMs = null;
    } else {
      final long millis = uncertainty.toLong(TimeUnit.MILLISECONDS);
      clockUncertaintyMs = millis < Long.MAX_VALUE
          && TimeDuration.valueOf(millis, TimeUnit.MILLISECONDS).compareTo(uncertainty) < 0 ? millis + 1 : millis;
    }

    this.dummySuccessReply = CompletableFuture.completedFuture(RaftClientReply.newBuilder()
        .setClientId(ClientId.emptyClientId())
        .setServerId(server.getId())
        .setGroupId(getMemberId().getGroupId())
        .setSuccess()
        .build());
  }

  RaftGroupMemberId getMemberId() {
    return state.getMemberId();
  }

  CompletableFuture<RaftClientReply> staleReadAsync(RaftClientRequest request) {
    final long minIndex = request.getType().getStaleRead().getMinIndex();
    final long commitIndex = state.getLog().getLastCommittedIndex();
    LOG.debug("{}: minIndex={}, commitIndex={} from {}", getMemberId(), minIndex, commitIndex, request.getClientId());
    if (commitIndex < minIndex) {
      final StaleReadException e = new StaleReadException(
          "Unable to serve stale-read due to server commit index = " + commitIndex + " < min = " + minIndex);
      return CompletableFuture.completedFuture(
          server.newExceptionReply(request, new StateMachineException(getMemberId(), e)));
    }
    return processQueryFuture(stateMachine.queryStale(request.getMessage(), minIndex), request);
  }

  CompletableFuture<RaftClientReply> boundedStaleReadAsync(RaftClientRequest request, Executor executor) {
    // Capture before submitting to the client executor. Neither fallback nor apply waiting resets these values.
    final long requestTime = System.currentTimeMillis();
    final Timestamp admitted = Timestamp.currentTime();
    final TimeDuration timeout = request.getTimeoutMs() > 0
        ? TimeDuration.min(readTimeout, TimeDuration.valueOf(request.getTimeoutMs(), TimeUnit.MILLISECONDS))
        : readTimeout;
    final CompletableFuture<Message> result = new CompletableFuture<>();
    final Runnable checkDeadline = () -> {
      try {
        server.assertLifeCycleState(LifeCycle.States.RUNNING);
      } catch (IOException e) {
        throw new CompletionException(new ReadException(e));
      }
      if (result.isDone() || admitted.elapsedTime().compareTo(timeout) >= 0) {
        throw new CompletionException(new ReadException("Bounded stale read timed out or was cancelled"));
      }
    };
    TimeoutExecutor.getInstance().onTimeout(timeout,
        () -> result.completeExceptionally(new ReadException("Bounded stale read timed out after " + timeout)),
        LOG, () -> "Failed to time out bounded stale read");
    final CompletableFuture<RaftClientReply> reply = result.handle((message, error) -> error == null
        ? server.newReplyBuilder(request).setSuccess().setMessage(message).build()
        : readException2Reply(request, error));
    reply.whenComplete((value, error) -> {
      if (reply.isCancelled()) {
        result.cancel(false);
      }
    });
    try {
      executor.execute(() -> {
        try {
          checkDeadline.run();
          final AppliedLogPrefix prefix = state.getStateMachineUpdater().getAppliedLogPrefix();
          if (prefix == null) {
            throw new ReadException("State machine does not support timestamp bounded stale reads");
          }
          final ReadException installing = getReadExceptionIfInstallingSnapshot();
          if (installing != null) {
            throw installing;
          }
          final long generation = prefix.getGeneration();
          final long minIndex = request.getType().getBoundedStaleRead().getMinIndex();
          final long maxStaleness = request.getType().getBoundedStaleRead().getMaxStalenessMs();
          final boolean local = clockUncertaintyMs != null
              && prefix.canServe(requestTime, maxStaleness, clockUncertaintyMs, minIndex);
          server.getRaftServerMetrics().onBoundedStaleRead(local);
          final CompletableFuture<Long> barrier = local ? CompletableFuture.completedFuture(minIndex)
              : getFreshReadIndex(request.getClientId());
          barrier.thenCompose(index -> {
            checkDeadline.run();
            final CompletableFuture<Long> applied = prefix.waitToAdvance(Math.max(index, minIndex), generation);
            result.whenComplete((value, error) -> applied.cancel(false));
            return applied;
          }).thenCompose(index -> {
            checkDeadline.run();
            return prefix.query(generation, () -> stateMachine.query(request.getMessage()));
          }).whenComplete((message, error) -> {
            if (error != null) {
              result.completeExceptionally(error);
            } else {
              try {
                checkDeadline.run();
                final ReadException snapshot = getReadExceptionIfInstallingSnapshot();
                if (snapshot != null) {
                  throw snapshot;
                }
                result.complete(message);
              } catch (Exception e) {
                result.completeExceptionally(e);
              }
            }
          });
        } catch (Exception e) {
          result.completeExceptionally(e instanceof UnsupportedOperationException ? new ReadException(e) : e);
        }
      });
    } catch (RuntimeException e) {
      result.completeExceptionally(new ReadException(e));
    }
    return reply;
  }

  private CompletableFuture<Long> getFreshReadIndex(ClientId clientId) throws IOException {
    final LeaderStateImpl leader = server.getRole().getLeaderState().orElse(null);
    if (leader != null) {
      return leader.getFreshReadIndex();
    }
    final RaftPeerId leaderId = server.getInfo().getLeaderId();
    if (leaderId == null) {
      return JavaUtils.completeExceptionally(new ReadIndexException("Leader is unknown"));
    }
    final ReadIndexRequestProto request = toReadIndexRequestProto(
        clientId, ReadRequestTypeProto.getDefaultInstance(), getMemberId(), leaderId)
        .toBuilder().setRequireFresh(true).build();
    return server.getServerRpc().async().readIndexAsync(request).thenApply(response -> {
      if (!response.getFresh()) {
        throw new CompletionException(new ReadException("Leader does not support validated ReadIndex"));
      }
      if (!response.getServerReply().getSuccess()) {
        throw new CompletionException(new ReadIndexException("Leader could not validate ReadIndex"));
      }
      return response.getReadIndex();
    });
  }

  ReadException getReadException(String op, long installSnapshot, boolean started) {
    return installSnapshot == RaftLog.INVALID_LOG_INDEX ? null : new ReadException(getMemberId() + ": Failed to " + op
        + " readIndex as snapshot (" + installSnapshot + ") installation is " + (started ? "started" : "in progress"));
  }

  private ReadException getReadExceptionIfInstallingSnapshot() {
    return getReadException("add", server.getSnapshotInstallationHandler().getInProgressInstallSnapshotIndex(), false);
  }

  private CompletableFuture<ReadIndexReplyProto> sendReadIndexAsync(
      ClientId clientId, ReadRequestTypeProto readRequestType) {
    final long installSnapshot = server.getSnapshotInstallationHandler().getInProgressInstallSnapshotIndex();
    if (installSnapshot != RaftLog.INVALID_LOG_INDEX) {
      return JavaUtils.completeExceptionally(getReadException("get", installSnapshot, false));
    }
    final RaftPeerId leaderId = server.getInfo().getLeaderId();
    if (leaderId == null) {
      return JavaUtils.completeExceptionally(new ReadIndexException(getMemberId() + ": Leader is unknown."));
    }
    final ReadIndexRequestProto request = toReadIndexRequestProto(clientId, readRequestType, getMemberId(), leaderId);
    try {
      return server.getServerRpc().async().readIndexAsync(request);
    } catch (IOException e) {
      return JavaUtils.completeExceptionally(e);
    }
  }

  private CompletableFuture<Long> getReadIndex(
      ClientId clientId, ReadRequestTypeProto readRequestType, LeaderStateImpl leader) {
    if (leader != null) {
      return server.getWriteIndexCache().getWriteIndexFuture(clientId, readRequestType)
          .thenCompose(leader::getReadIndex);
    }
    return sendReadIndexAsync(clientId, readRequestType).thenApply(reply -> {
      if (reply.getServerReply().getSuccess()) {
        return reply.getReadIndex();
      } else {
        throw new CompletionException(new ReadIndexException(server.getId()
                + ": Failed to get read index from the leader: " + reply));
      }
    });
  }

  CompletableFuture<Message> readOnlyAsync(ClientId clientId, ReadRequestTypeProto readRequestType,
      Message queryMessage) throws IOException {
    Objects.requireNonNull(clientId, "clientId == null");
    Objects.requireNonNull(readRequestType, "readRequestType == null");
    Objects.requireNonNull(queryMessage, "queryMessage == null");
    server.assertLifeCycleState(LifeCycle.States.RUNNING);
    if (readRequestType.getPreferNonLinearizable() || readOption == RaftServerConfigKeys.Read.Option.DEFAULT) {
      if (!server.getInfo().isLeader()) {
        return JavaUtils.completeExceptionally(server.newNotLeaderException());
      }
      if (!server.getInfo().isLeaderReady()) {
        return JavaUtils.completeExceptionally(new LeaderNotReadyException(getMemberId()));
      }
      return stateMachine.query(queryMessage);
    } else if (readOption == RaftServerConfigKeys.Read.Option.LINEARIZABLE) {
      final LeaderStateImpl leader = server.getRole().getLeaderState().orElse(null);
      return getReadIndex(clientId, readRequestType, leader)
          .thenCompose(readIndex -> state.getReadRequests().waitToAdvance(readIndex,
              this::getReadExceptionIfInstallingSnapshot))
          .thenCompose(readIndex -> stateMachine.query(queryMessage));
    } else {
      throw new IllegalStateException("Unexpected read option: " + readOption);
    }
  }

  CompletableFuture<RaftClientReply> readAsync(RaftClientRequest request) {
    if (request.getType().getRead().getPreferNonLinearizable()
        || readOption == RaftServerConfigKeys.Read.Option.DEFAULT) {
      final CompletableFuture<RaftClientReply> reply = server.checkLeaderState(request);
      if (reply != null) {
        return reply;
      }
      return isDummyRead(request) ? CompletableFuture.completedFuture(server.newSuccessReply(request))
          : queryStateMachine(request);
    } else if (readOption == RaftServerConfigKeys.Read.Option.LINEARIZABLE) {
      final LeaderStateImpl leader = server.getRole().getLeaderState().orElse(null);
      final CompletableFuture<Long> replyFuture;
      replyFuture = getReadIndex(request.getClientId(), request.getType().getRead(), leader);

      return replyFuture
          .thenCompose(readIndex -> state.getReadRequests().waitToAdvance(readIndex,
              this::getReadExceptionIfInstallingSnapshot))
          .thenCompose(readIndex -> isDummyRead(request)
              ? CompletableFuture.completedFuture(server.newSuccessReply(request)) : queryStateMachine(request))
          .exceptionally(e -> readException2Reply(request, e));
    } else {
      throw new IllegalStateException("Unexpected read option: " + readOption);
    }
  }

  static boolean isDummyRead(RaftClientRequest request) {
    return request.getMessage() != null && OrderedAsync.DUMMY.getContent().equals(request.getMessage().getContent());
  }

  private RaftClientReply readException2Reply(RaftClientRequest request, Throwable e) {
    e = JavaUtils.unwrapCompletionException(e);
    if (e instanceof StateMachineException ) {
      return server.newExceptionReply(request, (StateMachineException) e);
    } else if (e instanceof ReadException) {
      return server.newExceptionReply(request, (ReadException) e);
    } else if (e instanceof ReadIndexException) {
      return server.newExceptionReply(request, (ReadIndexException) e);
    } else {
      throw new CompletionException(e);
    }
  }

  private CompletableFuture<RaftClientReply> queryStateMachine(RaftClientRequest request) {
    if (request.getType().getRead().getDummy()) {
      return dummySuccessReply;
    }
    return processQueryFuture(stateMachine.query(request.getMessage()), request);
  }

  private CompletableFuture<RaftClientReply> processQueryFuture(
      CompletableFuture<Message> queryFuture, RaftClientRequest request) {
    return queryFuture.thenApply(r -> server.newReplyBuilder(request).setSuccess().setMessage(r).build())
        .exceptionally(e -> {
          e = JavaUtils.unwrapCompletionException(e);
          if (e instanceof StateMachineException) {
            return server.newExceptionReply(request, (StateMachineException)e);
          }
          throw new CompletionException(e);
        });
  }

  CompletableFuture<ReadIndexReplyProto> readIndexAsync(ReadIndexRequestProto request) throws IOException {
    server.assertLifeCycleState(LifeCycle.States.RUNNING);

    final RaftPeerId peerId = RaftPeerId.valueOf(request.getServerRequest().getRequestorId());

    final LeaderStateImpl leader = server.getRole().getLeaderState().orElse(null);
    if (leader == null) {
      return CompletableFuture.completedFuture(toReadIndexReplyProto(peerId, getMemberId())
          .toBuilder().setFresh(request.getRequireFresh()).build());
    }

    final RaftClientRequest clientRequest = ClientProtoUtils.toRaftClientRequest(request.getClientRequest());
    final CompletableFuture<Long> readIndex = request.getRequireFresh() ? leader.getFreshReadIndex()
        : getReadIndex(clientRequest.getClientId(), clientRequest.getType().getRead(), leader);
    return readIndex.thenApply(index -> toReadIndexReplyProto(peerId, getMemberId(), true, index)
            .toBuilder().setFresh(request.getRequireFresh()).build())
        .whenComplete((reply, exception) -> {
          if (exception == null) {
            // Leader should try to trigger heartbeat immediately after leader replies the ReadIndex to the follower
            // so that the follower's commitIndex can be updated to the leader's commitIndex and the follower
            // can start applying the logs up until the leader's commitIndex (instead of waiting for the next
            // AppendEntries to happen through heartbeat or new transactions (which might increase the latency
            // considerably)).
            // Note that if the follower commitIndex is already equal to the leader's commitIndex, no heartbeat
            // will be triggered, see GrpcLogAppender#isFollowerCommitBehindLastCommitIndex.
            RaftPeerId requestorId = RaftPeerId.valueOf(reply.getServerReply().getRequestorId());
            leader.getLogAppender(requestorId).ifPresent(LogAppender::triggerHeartbeat);
          }
        })
        .exceptionally(throwable -> toReadIndexReplyProto(peerId, getMemberId())
            .toBuilder().setFresh(request.getRequireFresh()).build());
  }
}
