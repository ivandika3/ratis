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
package org.apache.ratis.server.raftlog.segmented;

import org.apache.ratis.BaseTest;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.server.storage.RaftStorageTestUtils;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.util.CodeInjectionForTesting;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.TimeDuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestReadIndexCommit extends BaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(30)
  public void testCommitWaitsForTruncationOfFlushedSuffix(boolean readLockEnabled) throws Exception {
    final File directory = new File(getTestDir(), Boolean.toString(readLockEnabled));
    final RaftProperties properties = new RaftProperties();
    RaftServerConfigKeys.Log.setReadLockEnabled(properties, readLockEnabled);
    final RaftGroupMemberId memberId = RaftGroupMemberId.valueOf(RaftPeerId.valueOf("s0"), RaftGroupId.randomId());
    try (SegmentedRaftLog log = newLog(directory, memberId, properties, null)) {
      log.open(RaftLog.INVALID_LOG_INDEX, null);
      log.append(Arrays.asList(entry(1, 0), entry(1, 1))).forEach(CompletableFuture::join);
      Assertions.assertEquals(1, log.getFlushIndex());
    }

    final CountDownLatch blocked = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    CodeInjectionForTesting.put(SegmentedRaftLogWorker.RUN_WORKER, (local, remote, args) -> {
      blocked.countDown();
      try {
        Assertions.assertTrue(release.await(10, TimeUnit.SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
      return true;
    });
    try (SegmentedRaftLog log = newLog(directory, memberId, properties, null)) {
      log.open(RaftLog.INVALID_LOG_INDEX, null);
      final List<CompletableFuture<Long>> append;
      try {
        Assertions.assertTrue(blocked.await(10, TimeUnit.SECONDS));
        append = log.append(Collections.singletonList(entry(2, 1)));
        Assertions.assertEquals(TermIndex.valueOf(2, 1), log.getTermIndex(1));
        Assertions.assertEquals(1, log.getFlushIndex(), "flush index still describes the old suffix");
        Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(2, 1)),
            "the replacement is cached but not yet durable");
        Assertions.assertEquals(-1, log.getLastCommittedIndex());
      } finally {
        release.countDown();
      }
      CompletableFuture.allOf(append.toArray(new CompletableFuture<?>[0])).get(10, TimeUnit.SECONDS);
      Assertions.assertTrue(log.updateCommitIndex(TermIndex.valueOf(2, 1)));
      Assertions.assertEquals(1, log.getLastCommittedIndex());
    } finally {
      release.countDown();
      CodeInjectionForTesting.remove(SegmentedRaftLogWorker.RUN_WORKER);
    }
  }

  @Test
  @Timeout(30)
  public void testCommitWaitsForReplacementFlushAfterOldAsyncFlush() throws Exception {
    final RaftProperties properties = new RaftProperties();
    RaftServerConfigKeys.Log.setAsyncFlushEnabled(properties, true);
    RaftServerConfigKeys.Log.setForceSyncNum(properties, 1);
    RaftServerConfigKeys.Log.StateMachineData.setSync(properties, false);
    final CompletableFuture<Void> oldFlush = new CompletableFuture<>();
    final CompletableFuture<Void> replacementFlush = new CompletableFuture<>();
    final CountDownLatch oldFlushScheduled = new CountDownLatch(1);
    final CountDownLatch replacementFlushScheduled = new CountDownLatch(1);
    final AtomicInteger flushCount = new AtomicInteger();
    final StateMachine.DataApi data = new StateMachine.DataApi() {
      @Override
      public CompletableFuture<Void> flush(long index) {
        if (index != 1) {
          return CompletableFuture.completedFuture(null);
        }
        if (flushCount.incrementAndGet() == 1) {
          oldFlushScheduled.countDown();
          return oldFlush;
        }
        replacementFlushScheduled.countDown();
        return replacementFlush;
      }
    };
    final StateMachine stateMachine = new BaseStateMachine() {
      @Override
      public DataApi data() {
        return data;
      }
    };
    final RaftGroupMemberId memberId = RaftGroupMemberId.valueOf(RaftPeerId.valueOf("s0"), RaftGroupId.randomId());
    try (SegmentedRaftLog log = newLog(getTestDir(), memberId, properties, stateMachine)) {
      log.open(RaftLog.INVALID_LOG_INDEX, null);
      log.append(Collections.singletonList(entry(1, 0))).forEach(CompletableFuture::join);
      log.append(Collections.singletonList(entry(1, 1)));
      Assertions.assertTrue(oldFlushScheduled.await(10, TimeUnit.SECONDS));
      final List<CompletableFuture<Long>> append = log.append(Collections.singletonList(entry(2, 1)));
      append.get(0).get(10, TimeUnit.SECONDS);
      Assertions.assertTrue(replacementFlushScheduled.await(10, TimeUnit.SECONDS));
      Assertions.assertFalse(oldFlush.isDone(), "the old state-machine flush survives truncation");
      Assertions.assertFalse(replacementFlush.isDone());
      Assertions.assertEquals(TermIndex.valueOf(2, 1), log.getTermIndex(1));

      oldFlush.complete(null);
      JavaUtils.attempt(() -> Assertions.assertEquals(1, log.getFlushIndex()),
          100, TimeDuration.valueOf(100, TimeUnit.MILLISECONDS), "old async flush completed", LOG);
      Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(2, 1)),
          "the old flush must not establish durability of the replacement");
      Assertions.assertEquals(-1, log.getLastCommittedIndex());

      replacementFlush.complete(null);
      JavaUtils.attempt(() -> Assertions.assertTrue(log.updateCommitIndex(TermIndex.valueOf(2, 1))),
          100, TimeDuration.valueOf(100, TimeUnit.MILLISECONDS), "replacement async flush completed", LOG);
      Assertions.assertEquals(1, log.getLastCommittedIndex());
    } finally {
      oldFlush.complete(null);
      replacementFlush.complete(null);
    }
  }

  private static SegmentedRaftLog newLog(File directory, RaftGroupMemberId memberId,
      RaftProperties properties, StateMachine stateMachine) throws Exception {
    final RaftStorage storage = RaftStorageTestUtils.newRaftStorage(directory);
    return SegmentedRaftLog.newBuilder().setMemberId(memberId).setStorage(storage).setStateMachine(stateMachine)
        .setProperties(properties).build();
  }

  private static LogEntryProto entry(long term, long index) {
    return LogEntryProto.newBuilder().setTerm(term).setIndex(index).build();
  }
}
