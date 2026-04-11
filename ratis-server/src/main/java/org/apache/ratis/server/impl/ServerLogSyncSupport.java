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

import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.proto.RaftProtos.RaftRpcRequestProto;
import org.apache.ratis.proto.RaftProtos.ReadCommittedEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadCommittedEntriesRequestProto;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.exceptions.ServerNotReadyException;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.raftlog.RaftLogIOException;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.util.CodeInjectionForTesting;
import org.apache.ratis.util.IOUtils;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.LifeCycle;
import org.apache.ratis.util.Preconditions;
import org.apache.ratis.util.ProtoUtils;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.apache.ratis.server.impl.ServerImplUtils.assertGroup;
import static org.apache.ratis.server.impl.ServerProtoUtils.toReadCommittedEntriesReplyProto;
import static org.apache.ratis.server.util.ServerStringUtils.toReadCommittedEntriesReplyString;
import static org.apache.ratis.server.util.ServerStringUtils.toReadCommittedEntriesRequestString;

final class ServerLogSyncSupport {
  private static final Logger LOG = LoggerFactory.getLogger(ServerLogSyncSupport.class);

  private ServerLogSyncSupport() {}

  static ReadCommittedEntriesReplyProto readCommittedEntries(
      RaftServerImpl server, ReadCommittedEntriesRequestProto request) throws IOException {
    try {
      return readCommittedEntriesAsync(server, request).join();
    } catch (CompletionException e) {
      throw IOUtils.asIOException(JavaUtils.unwrapCompletionException(e));
    }
  }

  static CompletableFuture<ReadCommittedEntriesReplyProto> readCommittedEntriesAsync(
      RaftServerImpl server, ReadCommittedEntriesRequestProto request) throws IOException {
    final RaftRpcRequestProto rpcRequest = request.getServerRequest();
    try {
      final RaftPeerId requestorId = RaftPeerId.valueOf(rpcRequest.getRequestorId());
      final RaftGroupId requestorGroupId = ProtoUtils.toRaftGroupId(rpcRequest.getRaftGroupId());
      CodeInjectionForTesting.execute(RaftServerImpl.READ_COMMITTED_ENTRIES, server.getId(), requestorId, request);

      server.assertLifeCycleState(LifeCycle.States.STARTING_OR_RUNNING);
      if (!server.isStartComplete()) {
        throw new ServerNotReadyException(server.getMemberId() + ": The server role is not yet initialized.");
      }
      assertGroup(server.getMemberId(), requestorId, requestorGroupId);

      return CompletableFuture.completedFuture(
          readCommittedEntries(server, requestorId, rpcRequest.getCallId(), request.getStartIndex()));
    } catch (Exception t) {
      LOG.error("{}: Failed readCommittedEntries {}", server.getMemberId(),
          toReadCommittedEntriesRequestString(request), t);
      throw IOUtils.asIOException(t);
    }
  }

  static CompletableFuture<Long> applyReadCommittedEntriesAsync(
      RaftServerImpl server, ReadCommittedEntriesReplyProto reply) throws IOException {
    if (reply.getResult() != ReadCommittedEntriesReplyProto.Result.SUCCESS) {
      return JavaUtils.completeExceptionally(new IOException("Unexpected readCommittedEntries reply: " + reply));
    }

    final RaftPeerId leaderId = reply.hasLeaderId() ? RaftPeerId.valueOf(reply.getLeaderId().getId()) : null;
    updateListenerLeaderMetadata(server, leaderId, reply.getTerm(), RaftServerImpl.READ_COMMITTED_ENTRIES);

    final TermIndex previous = reply.hasPreviousLog() ? TermIndex.valueOf(reply.getPreviousLog()) : null;
    return server.applyPulledEntriesToLocalLog(previous, reply.getEntriesList(), reply.getCommitIndex(),
        toReadCommittedEntriesReplyString(reply));
  }

  static void logAppendEntries(boolean isHeartbeat, Supplier<String> message) {
    if (isHeartbeat) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("HEARTBEAT: {}", message.get());
      }
    } else if (LOG.isDebugEnabled()) {
      LOG.debug(message.get());
    }
  }

  static Optional<FollowerState> updateLastRpcTime(
      RaftServerImpl server, FollowerState.UpdateType updateType) {
    final Optional<FollowerState> fs = server.getRole().getFollowerState();
    if (fs.isPresent() && server.isLifeCycleRunning()) {
      fs.get().updateLastRpcTime(updateType);
      return fs;
    }
    return Optional.empty();
  }

  static CompletableFuture<Void> appendLog(RaftServerImpl server, List<LogEntryProto> entries) {
    return CompletableFuture.completedFuture(null)
        .thenComposeAsync(dummy -> JavaUtils.allOf(server.getState().getLog().append(entries)),
            server.getServerExecutor());
  }

  private static ReadCommittedEntriesReplyProto readCommittedEntries(
      RaftServerImpl server, RaftPeerId requestorId, long callId, long startIndex) throws IOException {
    final ReadCommittedEntriesReplyProto reply;
    synchronized (server) {
      final ServerState state = server.getState();
      final long currentTerm = state.getCurrentTerm();
      final RaftPeerId leaderId = state.getLeaderId();
      final long commitIndex = state.getLog().getLastCommittedIndex();
      final long nextIndex = state.getNextIndex();
      final long logStartIndex = getFirstAvailableLogIndex(server);

      if (!server.getInfo().isFollower()) {
        reply = toReadCommittedEntriesReplyProto(requestorId, server.getMemberId(),
            ServerProtoUtils.ReadCommittedEntriesReplyContext.newBuilder(callId,
                    ReadCommittedEntriesReplyProto.Result.NOT_LEADER, currentTerm)
                .setLeaderId(leaderId)
                .setCommitIndex(commitIndex)
                .setLogStartIndex(logStartIndex)
                .setPrevious(getPrevious(server, nextIndex))
                .setNextIndex(nextIndex));
      } else if (startIndex < logStartIndex) {
        reply = toReadCommittedEntriesReplyProto(requestorId, server.getMemberId(),
            ServerProtoUtils.ReadCommittedEntriesReplyContext.newBuilder(callId,
                    ReadCommittedEntriesReplyProto.Result.LOG_UNAVAILABLE, currentTerm)
                .setLeaderId(leaderId)
                .setCommitIndex(commitIndex)
                .setLogStartIndex(logStartIndex)
                .setPrevious(getPrevious(server, logStartIndex))
                .setNextIndex(logStartIndex));
      } else {
        final long endExclusive = Math.min(commitIndex + 1, nextIndex);
        final List<LogEntryProto> entries;
        if (startIndex >= endExclusive) {
          entries = new ArrayList<>();
        } else {
          entries = readCommittedEntriesFromLog(server, startIndex, endExclusive);
          if (entries == null) {
            final long currentLogStartIndex = getFirstAvailableLogIndex(server);
            final ReadCommittedEntriesReplyProto unavailable = toReadCommittedEntriesReplyProto(requestorId,
                server.getMemberId(), ServerProtoUtils.ReadCommittedEntriesReplyContext.newBuilder(callId,
                    ReadCommittedEntriesReplyProto.Result.LOG_UNAVAILABLE, currentTerm)
                    .setLeaderId(leaderId)
                    .setCommitIndex(commitIndex)
                    .setLogStartIndex(currentLogStartIndex)
                    .setPrevious(getPrevious(server, currentLogStartIndex))
                    .setNextIndex(currentLogStartIndex));
            LOG.debug("{}: readCommittedEntries reply {}", server.getMemberId(),
                toReadCommittedEntriesReplyString(unavailable));
            return unavailable;
          }
        }

        final long replyNextIndex = entries.isEmpty()
            ? Math.min(startIndex, nextIndex)
            : entries.get(entries.size() - 1).getIndex() + 1;
        reply = toReadCommittedEntriesReplyProto(requestorId, server.getMemberId(),
            ServerProtoUtils.ReadCommittedEntriesReplyContext.newBuilder(callId,
                    ReadCommittedEntriesReplyProto.Result.SUCCESS, currentTerm)
                .setLeaderId(leaderId)
                .setCommitIndex(commitIndex)
                .setLogStartIndex(logStartIndex)
                .setPrevious(getPrevious(server, entries.isEmpty() ? replyNextIndex : entries.get(0).getIndex()))
                .setNextIndex(replyNextIndex)
                .setEntries(entries));
      }
    }
    LOG.debug("{}: readCommittedEntries reply {}", server.getMemberId(), toReadCommittedEntriesReplyString(reply));
    return reply;
  }

  private static void updateListenerLeaderMetadata(
      RaftServerImpl server, RaftPeerId leaderId, long leaderTerm, Object op) throws IOException {
    synchronized (server) {
      final ServerState state = server.getState();
      Preconditions.assertTrue(server.getInfo().isListener(), () -> server.getMemberId() + " is not a listener");
      if (leaderId != null) {
        Preconditions.assertTrue(state.recognizeLeader(op, leaderId, leaderTerm),
            () -> server.getMemberId() + ": Failed to recognize " + leaderId + " for " + op
                + " at term " + leaderTerm);
      } else {
        Preconditions.assertTrue(leaderTerm >= state.getCurrentTerm(),
            () -> server.getMemberId() + ": Received stale listener metadata update for " + op
                + ": leaderTerm=" + leaderTerm + " < currentTerm=" + state.getCurrentTerm());
      }

      final boolean metadataUpdated = state.updateCurrentTerm(leaderTerm);
      state.setLeader(leaderId, op);
      if (metadataUpdated) {
        state.persistMetadata();
      }
    }
  }

  private static long getFirstAvailableLogIndex(RaftServerImpl server) {
    final long logStartIndex = server.getState().getLog().getStartIndex();
    return logStartIndex > RaftLog.INVALID_LOG_INDEX ? logStartIndex : server.getState().getSnapshotIndex() + 1;
  }

  private static TermIndex getPrevious(RaftServerImpl server, long nextIndex) {
    if (nextIndex == RaftLog.LEAST_VALID_LOG_INDEX) {
      return null;
    }

    final long previousIndex = nextIndex - 1;
    final TermIndex previous = server.getState().getLog().getTermIndex(previousIndex);
    if (previous != null) {
      return previous;
    }

    final SnapshotInfo snapshot = server.getStateMachine().getLatestSnapshot();
    if (snapshot != null) {
      final TermIndex snapshotTermIndex = snapshot.getTermIndex();
      if (snapshotTermIndex.getIndex() == previousIndex) {
        return snapshotTermIndex;
      }
    }
    return null;
  }

  private static List<LogEntryProto> readCommittedEntriesFromLog(
      RaftServerImpl server, long startIndex, long endExclusive) throws IOException {
    final int elementLimit =
        RaftServerConfigKeys.Log.Appender.bufferElementLimit(server.getRaftServer().getProperties());
    final int byteLimit =
        RaftServerConfigKeys.Log.Appender.bufferByteLimit(server.getRaftServer().getProperties()).getSizeInt();
    final TimeDuration readTimeout =
        RaftServerConfigKeys.Log.StateMachineData.readTimeout(server.getRaftServer().getProperties());

    final List<LogEntryProto> entries = new ArrayList<>();
    long totalSize = 0L;
    for (long index = startIndex; index < endExclusive; index++) {
      final RaftLog.EntryWithData entryWithData = server.getState().getLog().getEntryWithData(index);
      if (entryWithData == null) {
        if (startIndex < getFirstAvailableLogIndex(server)) {
          return null;
        }
        throw new RaftLogIOException(server.getMemberId() + ": Missing committed log entry at index " + index);
      }

      final int serializedSize = entryWithData.getSerializedSize();
      if (!entries.isEmpty()) {
        if (elementLimit > 0 && entries.size() >= elementLimit) {
          break;
        }
        if (byteLimit > 0 && totalSize + serializedSize > byteLimit) {
          break;
        }
      }

      try {
        entries.add(entryWithData.getEntry(readTimeout));
      } catch (TimeoutException e) {
        throw new IOException(server.getMemberId() + ": Timed out reading committed log entry " + index, e);
      }
      totalSize += serializedSize;
    }
    return entries;
  }
}
