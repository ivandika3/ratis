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

import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.ReadOnlyRequestTests.CounterStateMachine;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys.Read.ReadIndex.Type;
import org.apache.ratis.server.impl.MiniRaftCluster;
import org.apache.ratis.server.impl.ReplyFlusher;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.CodeInjectionForTesting;
import org.apache.ratis.util.JavaUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.apache.ratis.ReadOnlyRequestTests.INCREMENT;
import static org.apache.ratis.ReadOnlyRequestTests.QUERY;
import static org.apache.ratis.ReadOnlyRequestTests.assertReplyExact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestLinearizableReadRepliedIndexWithGrpc
  extends TestLinearizableReadWithGrpc {

  @Override
  public Type readIndexType() {
    return Type.REPLIED_INDEX;
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void testLeaderReadPreservesFollowerReadOrder(boolean local) throws Exception {
    runWithNewCluster(cluster -> {
      final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
      final CounterStateMachine stateMachine = (CounterStateMachine)leader.getStateMachine();
      final RaftPeerId follower = cluster.getFollowers().get(0).getId();
      final BlockingCode blockingReplyFlusher = new BlockingCode();
      try (RaftClient writer = cluster.createClient(leader.getId());
           RaftClient leaderReader = cluster.createClient(leader.getId());
           RaftClient followerReader = cluster.createClient(follower)) {
        assertReplyExact(0, leaderReader.async().sendReadOnly(QUERY, leader.getId()).get(10, TimeUnit.SECONDS));
        assertReplyExact(0, followerReader.async().sendReadOnly(QUERY, follower).get(10, TimeUnit.SECONDS));
        CodeInjectionForTesting.put(ReplyFlusher.FLUSH, blockingReplyFlusher);
        try {
          blockingReplyFlusher.entered.get(10, TimeUnit.SECONDS);
          final CompletableFuture<RaftClientReply> write = writer.async().send(INCREMENT);
          JavaUtils.attempt(() -> assertEquals(1, stateMachine.getCount()),
              100, HUNDRED_MILLIS, "leader applied increment while write reply is held", LOG);
          assertFalse(write.isDone(), "Write reply must remain held while the leader exposes its applied state");

          if (local) {
            assertEquals("1", leader.readOnlyAsync(QUERY).get(10, TimeUnit.SECONDS).getContent().toStringUtf8());
          } else {
            assertReplyExact(1, leaderReader.async().sendReadOnly(QUERY, leader.getId()).get(10, TimeUnit.SECONDS));
          }
          assertReplyExact(1, followerReader.async().sendReadOnly(QUERY, follower).get(10, TimeUnit.SECONDS));
          assertFalse(write.isDone(), "Read ordering must not require flushing the held write reply");
        } finally {
          blockingReplyFlusher.complete();
          CodeInjectionForTesting.remove(ReplyFlusher.FLUSH);
        }
      }
    });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  public void testFollowerReadPreservesReadOrderAfterBecomingLeader(boolean local) throws Exception {
    getProperties().setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY,
        BlockingQueryStateMachine.class, StateMachine.class);
    try {
      runWithNewCluster(cluster -> {
        final RaftServer.Division previousLeader = RaftTestUtil.waitForLeader(cluster);
        final RaftServer.Division nextLeader = cluster.getFollowers().get(0);
        final BlockingQueryStateMachine stateMachine = (BlockingQueryStateMachine)nextLeader.getStateMachine();
        final CompletableFuture<Void> queryGate = new CompletableFuture<>();
        final BlockingCode blockingReplyFlusher = new BlockingCode();
        try (RaftClient writer = cluster.createClient(nextLeader.getId());
             RaftClient nextLeaderReader = cluster.createClient(nextLeader.getId());
             RaftClient followerReader = cluster.createClient(previousLeader.getId())) {
          // Bootstrap the clients before delaying a query so no later dummy read can publish the increment.
          assertReplyExact(0, writer.async().sendReadOnly(QUERY, nextLeader.getId()).get(10, TimeUnit.SECONDS));
          assertReplyExact(0, nextLeaderReader.async().sendReadOnly(QUERY, nextLeader.getId())
              .get(10, TimeUnit.SECONDS));
          assertReplyExact(0, followerReader.async().sendReadOnly(QUERY, previousLeader.getId())
              .get(10, TimeUnit.SECONDS));
          stateMachine.queryGate = queryGate;
          try {
            final CompletableFuture<Message> read = local ? nextLeader.readOnlyAsync(QUERY)
                : nextLeaderReader.async().sendReadOnly(QUERY, nextLeader.getId()).thenApply(reply -> {
                  assertTrue(reply.isSuccess());
                  return reply.getMessage();
                });
            stateMachine.queryEntered.get(10, TimeUnit.SECONDS);
            assertFalse(nextLeader.getInfo().isLeader(), "Query must start while its server is still a follower");
            assertTrue(followerReader.admin().transferLeadership(nextLeader.getId(), 10_000).isSuccess());
            assertEquals(nextLeader.getId(), RaftTestUtil.waitForLeader(cluster).getId());

            CodeInjectionForTesting.put(ReplyFlusher.FLUSH, blockingReplyFlusher);
            blockingReplyFlusher.entered.get(10, TimeUnit.SECONDS);
            final CompletableFuture<RaftClientReply> write = writer.async().send(INCREMENT);
            JavaUtils.attempt(() -> assertEquals(1, stateMachine.getCount()),
                100, HUNDRED_MILLIS, "new leader applied increment while write reply is held", LOG);
            assertFalse(write.isDone(), "Write reply must remain held while the promoted server exposes its state");

            // The query started on a follower but observes a write applied after that server became leader.
            queryGate.complete(null);
            assertEquals("1", read.get(10, TimeUnit.SECONDS).getContent().toStringUtf8());
            assertReplyExact(1, followerReader.async().sendReadOnly(QUERY, previousLeader.getId())
                .get(10, TimeUnit.SECONDS));
            assertFalse(write.isDone(), "Read ordering must not require flushing the held write reply");
          } finally {
            queryGate.complete(null);
            blockingReplyFlusher.complete();
            CodeInjectionForTesting.remove(ReplyFlusher.FLUSH);
          }
        }
      });
    } finally {
      getProperties().setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY, CounterStateMachine.class, StateMachine.class);
    }
  }

  @Test
  @Override
  public void testFollowerLinearizableReadParallel() throws Exception {
    runWithNewCluster(TestLinearizableReadRepliedIndexWithGrpc::runTestFollowerReadOnlyParallelRepliedIndex);
  }

  static <C extends MiniRaftCluster> void runTestFollowerReadOnlyParallelRepliedIndex(C cluster)
      throws Exception {
    final RaftServer.Division leader = RaftTestUtil.waitForLeader(cluster);
    final CounterStateMachine leaderStateMachine = (CounterStateMachine)leader.getStateMachine();

    final List<RaftServer.Division> followers = cluster.getFollowers();
    Assertions.assertEquals(2, followers.size());
    final RaftPeerId f0 = followers.get(0).getId();
    final RaftPeerId f1 = followers.get(1).getId();

    final BlockingCode blockingReplyFlusher = new BlockingCode();

    try (RaftClient leaderClient = cluster.createClient(leader.getId());
         RaftClient f0Client = cluster.createClient(f0);
         RaftClient f1Client = cluster.createClient(f1)) {
      // Warm up the clients first before blocking the reply flusher
      assertReplyExact(0, leaderClient.async().sendReadOnly(QUERY).get());
      assertReplyExact(0, f0Client.async().sendReadOnly(QUERY, f0).get());
      assertReplyExact(0, f1Client.async().sendReadOnly(QUERY, f1).get());

      CodeInjectionForTesting.put(ReplyFlusher.FLUSH, blockingReplyFlusher);

      final int n = 10;
      final List<Reply> writeReplies = new ArrayList<>(n);
      final List<Reply> f0Replies = new ArrayList<>(n);
      final List<Reply> f1Replies = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        final int count = i + 1;
        writeReplies.add(new Reply(count, leaderClient.async().send(INCREMENT)));

        // Read reply returns immediately, but they all should return 0 since the repliedIndex has not been updated
        // and write operations should not been applied by the followers
        f0Replies.add(new Reply(0, f0Client.async().sendReadOnly(QUERY, f0)));
        f1Replies.add(new Reply(0, f1Client.async().sendReadOnly(QUERY, f1)));

        // Wait until the leader state machine has applied the write while the ReplyFlusher remains blocked.
        JavaUtils.attempt(() -> assertEquals(count, leaderStateMachine.getCount()),
            10, HUNDRED_MILLIS, "leaderStateMachine count " + count, null);
      }

      for (int i = 0; i < n; i++) {
        // Write reply should not yet complete since ReplyFlusher remains blocked.
        assertFalse(writeReplies.get(i).isDone(), "Received unexpected Write reply " + writeReplies.get(i));

        // Follower reads should be immediately served, but the read value should return the value before the
        // replyFlusher is blocked
        assertTrue(f0Replies.get(i).isDone(), "Follower read should return immediately");
        f0Replies.get(i).assertExact();
        assertTrue(f1Replies.get(i).isDone(), "Follower read should return immediately");
        f1Replies.get(i).assertExact();
      }

      // unblock ReplyFlusher
      blockingReplyFlusher.complete();
      assertReplyExact(n, f0Client.io().sendReadOnly(QUERY, f0));
      assertReplyExact(n, f1Client.io().sendReadOnly(QUERY, f0));

      for (int i = 0; i < n; i++) {
        //write reply should get the exact count at the write time
        writeReplies.get(i).assertExact();
      }
    }
  }

  public static class BlockingQueryStateMachine extends CounterStateMachine {
    private final CompletableFuture<Void> queryEntered = new CompletableFuture<>();
    private volatile CompletableFuture<Void> queryGate;

    @Override
    public CompletableFuture<Message> query(Message request) {
      final CompletableFuture<Void> gate = queryGate;
      if (gate == null) {
        return super.query(request);
      }
      queryEntered.complete(null);
      return gate.thenCompose(ignored -> super.query(request));
    }
  }

  static class BlockingCode implements CodeInjectionForTesting.Code {
    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private final CompletableFuture<Void> entered = new CompletableFuture<>();

    void complete() {
      future.complete(null);
    }

    @Override
    public boolean execute(Object localId, Object remoteId, Object... args) {
      entered.complete(null);
      final boolean blocked = !future.isDone();
      if (blocked) {
        LOG.info("{}: ReplyFlusher is blocked", localId, new Throwable());
      }
      future.join();
      if (blocked) {
        LOG.info("{}: ReplyFlusher is unblocked", localId);
      }
      return true;
    }
  }


}
