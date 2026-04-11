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
import org.apache.ratis.proto.RaftProtos.ReadCommittedEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadCommittedEntriesRequestProto;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.util.ServerStringUtils;
import org.apache.ratis.util.Daemon;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Used when the peer is a listener and stable listener pull sync is enabled.
 */
class ListenerState extends Daemon {
  static final Logger LOG = LoggerFactory.getLogger(ListenerState.class);

  private final RaftServerImpl server;
  private volatile boolean isRunning = true;
  private final CompletableFuture<Void> stopped = new CompletableFuture<>();
  private int sourceIndex = 0;

  ListenerState(RaftServerImpl server) {
    super(newBuilder()
        .setName(ServerStringUtils.generateUnifiedName(server.getMemberId(), ListenerState.class))
        .setThreadGroup(server.getThreadGroup()));
    this.server = server;
  }

  CompletableFuture<Void> stopRunning() {
    isRunning = false;
    interrupt();
    return stopped;
  }

  private boolean shouldRun() {
    final boolean run = isRunning && server.getInfo().isListener() && server.isRunning();
    if (!run) {
      LOG.info("{}: stopping now (isRunning? {}, role={})", this, isRunning, server.getInfo().getCurrentRole());
    }
    return run;
  }

  @Override
  public void run() {
    try {
      runImpl();
    } finally {
      stopped.complete(null);
    }
  }

  private TimeDuration getBackoffTime() {
    return TimeDuration.valueOf(Math.max(1, server.properties().minRpcTimeoutMs() / 2), TimeUnit.MILLISECONDS);
  }

  private boolean isPullEnabled(RaftConfigurationImpl conf) {
    return conf != null
        && conf.isStable()
        && conf.containsInConf(server.getId(), RaftPeerRole.LISTENER)
        && !server.getRole().getFollowerState().map(FollowerState::isCurrentLeaderValid).orElse(false)
        && RaftServerConfigKeys.Listener.syncFromFollowerEnabled(server.getRaftServer().getProperties());
  }

  private List<RaftPeer> getFollowerSources(RaftConfigurationImpl conf) {
    final Object leaderId = server.getInfo().getLeaderId();
    return conf.getCurrentPeers(RaftPeerRole.FOLLOWER).stream()
        .filter(peer -> !peer.getId().equals(server.getId()))
        .filter(peer -> leaderId == null || !peer.getId().equals(leaderId))
        .collect(Collectors.toList());
  }

  private RaftPeer getCurrentSource(List<RaftPeer> followers) {
    if (followers.isEmpty()) {
      sourceIndex = 0;
      return null;
    }
    sourceIndex = Math.floorMod(sourceIndex, followers.size());
    return followers.get(sourceIndex);
  }

  private void rotateSource(List<RaftPeer> followers) {
    if (!followers.isEmpty()) {
      sourceIndex = Math.floorMod(sourceIndex + 1, followers.size());
    }
  }

  private void runImpl() {
    while (shouldRun()) {
      final TimeDuration backoff = getBackoffTime();
      try {
        final RaftConfigurationImpl conf = server.getRaftConf();
        if (!isPullEnabled(conf)) {
          backoff.sleep();
          continue;
        }

        final List<RaftPeer> followers = getFollowerSources(conf);
        if (followers.isEmpty()) {
          backoff.sleep();
          continue;
        }

        boolean completedAttempt = false;
        int logUnavailableCount = 0;
        for (int attempts = 0; shouldRun() && attempts < followers.size(); attempts++) {
          final RaftPeer source = getCurrentSource(followers);
          if (source == null) {
            break;
          }

          final ReadCommittedEntriesRequestProto request = ServerProtoUtils.toReadCommittedEntriesRequestProto(
              server.getMemberId(), source.getId(), server.getState().getNextIndex());
          try {
            final ReadCommittedEntriesReplyProto reply = server.getServerRpc().readCommittedEntries(request);
            switch (reply.getResult()) {
              case SUCCESS:
                server.applyReadCommittedEntriesAsync(reply).join();
                completedAttempt = true;
                if (reply.getEntriesCount() == 0) {
                  backoff.sleep();
                }
                break;
              case NOT_LEADER:
                rotateSource(followers);
                break;
              case LOG_UNAVAILABLE:
                logUnavailableCount++;
                rotateSource(followers);
                break;
              default:
                rotateSource(followers);
                break;
            }
          } catch (CompletionException e) {
            final Throwable cause = JavaUtils.unwrapCompletionException(e);
            LOG.info("{}: failed to pull committed entries from {}: {}", this, source.getId(), cause.toString());
            rotateSource(followers);
          } catch (Exception e) {
            LOG.info("{}: failed to pull committed entries from {}: {}", this, source.getId(), e.toString());
            rotateSource(followers);
          }

          if (completedAttempt) {
            break;
          }
        }

        if (!completedAttempt) {
          if (logUnavailableCount == followers.size()) {
            LOG.debug("{}: all follower sources reported log unavailable", this);
          }
          backoff.sleep();
        }
      } catch (InterruptedException e) {
        LOG.info("{} was interrupted", this);
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        LOG.warn("{} caught an exception", this, e);
        try {
          backoff.sleep();
        } catch (InterruptedException interrupted) {
          LOG.info("{} was interrupted", this);
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  @Override
  public String toString() {
    return getName();
  }
}
