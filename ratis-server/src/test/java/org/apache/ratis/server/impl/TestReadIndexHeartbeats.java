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

import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.RaftRpcReplyProto;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.rpc.CallId;
import org.apache.ratis.server.impl.ReadIndexHeartbeats.AppendEntriesListener;
import org.apache.ratis.server.leader.LogAppender;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(10)
class TestReadIndexHeartbeats {
  private static final long COMMIT_INDEX = 5;
  private static final RaftPeerId FOLLOWER = RaftPeerId.valueOf("follower");

  @Test
  void newReadAtAcknowledgedIndexRequiresFreshHeartbeat() {
    final ReadIndexHeartbeats heartbeats = new ReadIndexHeartbeats();
    final AtomicLong nextCallId = new AtomicLong(1);
    final LogAppender appender = newAppender(nextCallId);
    final AppendEntriesListener first = addRead(heartbeats, appender);
    acknowledge(heartbeats, appender, nextCallId.getAndIncrement());
    Assertions.assertEquals(COMMIT_INDEX, first.getFuture().join().longValue());

    final AppendEntriesListener later = addRead(heartbeats, appender);

    Assertions.assertNotNull(later, "An earlier read's acknowledgement cannot establish leadership for a new read");
    Assertions.assertFalse(later.getFuture().isDone());
    acknowledge(heartbeats, appender, nextCallId.getAndIncrement());
    Assertions.assertEquals(COMMIT_INDEX, later.getFuture().join().longValue());
  }

  @Test
  void concurrentReadCannotUseHeartbeatSentBeforeItsInvocation() {
    final ReadIndexHeartbeats heartbeats = new ReadIndexHeartbeats();
    final AtomicLong nextCallId = new AtomicLong(1);
    final LogAppender appender = newAppender(nextCallId);
    final AppendEntriesListener first = addRead(heartbeats, appender);
    final long earlierHeartbeat = nextCallId.getAndIncrement();

    final AppendEntriesListener later = addRead(heartbeats, appender);
    acknowledge(heartbeats, appender, earlierHeartbeat);

    Assertions.assertTrue(first.getFuture().isDone());
    Assertions.assertFalse(later.getFuture().isDone(),
        "A heartbeat sent before the later read began cannot establish leadership for that read");
    acknowledge(heartbeats, appender, nextCallId.getAndIncrement());
    Assertions.assertEquals(COMMIT_INDEX, later.getFuture().join().longValue());
  }

  @Test
  void completionCallbackCanSubmitNewReadAtSameIndex() {
    final ReadIndexHeartbeats heartbeats = new ReadIndexHeartbeats();
    final AtomicLong nextCallId = new AtomicLong(1);
    final LogAppender appender = newAppender(nextCallId);
    final AppendEntriesListener first = addRead(heartbeats, appender);
    final long earlierHeartbeat = nextCallId.getAndIncrement();
    final CompletableFuture<AppendEntriesListener> submitted = first.getFuture()
        .thenApply(index -> addRead(heartbeats, appender));

    Assertions.assertDoesNotThrow(() -> acknowledge(heartbeats, appender, earlierHeartbeat));

    final AppendEntriesListener later = Assertions.assertDoesNotThrow(submitted::join);
    Assertions.assertFalse(later.getFuture().isDone(),
        "A read submitted by a completion callback needs a fresh heartbeat");
    acknowledge(heartbeats, appender, nextCallId.getAndIncrement());
    Assertions.assertEquals(COMMIT_INDEX, later.getFuture().join().longValue());
  }

  @Test
  void failureCallbackCanSubmitNewRead() {
    final ReadIndexHeartbeats heartbeats = new ReadIndexHeartbeats();
    final LogAppender appender = newAppender(new AtomicLong(1));
    final AppendEntriesListener first = addRead(heartbeats, appender);
    final AppendEntriesListener other = heartbeats.addAppendEntriesListener(COMMIT_INDEX + 1,
        index -> new AppendEntriesListener(index, Collections.singletonList(appender)));
    final CompletableFuture<AppendEntriesListener> submitted = first.getFuture()
        .handle((index, failure) -> addRead(heartbeats, appender));
    final IOException failure = new IOException("Leadership lost");

    Assertions.assertDoesNotThrow(() -> heartbeats.failListeners(failure));

    final AppendEntriesListener later = Assertions.assertDoesNotThrow(submitted::join);
    for (AppendEntriesListener listener : new AppendEntriesListener[]{first, other, later}) {
      final CompletionException exception = Assertions.assertThrows(
          CompletionException.class, listener.getFuture()::join);
      Assertions.assertSame(failure, exception.getCause());
    }
  }

  private static LogAppender newAppender(AtomicLong nextCallId) {
    final LogAppender appender = mock(LogAppender.class);
    when(appender.getFollowerId()).thenReturn(FOLLOWER);
    when(appender.getCallId()).thenAnswer(invocation -> nextCallId.get());
    when(appender.getCallIdComparator()).thenReturn(CallId.getComparator());
    return appender;
  }

  private static AppendEntriesListener addRead(ReadIndexHeartbeats heartbeats, LogAppender appender) {
    return heartbeats.addAppendEntriesListener(COMMIT_INDEX,
        index -> new AppendEntriesListener(index, Collections.singletonList(appender)));
  }

  private static void acknowledge(ReadIndexHeartbeats heartbeats, LogAppender appender, long callId) {
    final AppendEntriesReplyProto reply = AppendEntriesReplyProto.newBuilder().setFollowerCommit(COMMIT_INDEX)
        .setResult(AppendEntriesReplyProto.AppendResult.SUCCESS)
        .setServerReply(RaftRpcReplyProto.newBuilder().setCallId(callId).setSuccess(true)).build();
    heartbeats.onAppendEntriesReply(appender, reply, acknowledged -> acknowledged.test(FOLLOWER));
  }
}
