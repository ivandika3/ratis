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
package org.apache.ratis.server.raftlog;

import org.apache.ratis.BaseTest;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.memory.MemoryRaftLog;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class TestRaftLogCommitIndex extends BaseTest {
  @Test
  public void testCommitIndexRequiresMatchingFlushedEntry() throws Exception {
    final AtomicLong flushIndex = new AtomicLong(0);
    try (MemoryRaftLog log = new MemoryRaftLog(RaftGroupMemberId.valueOf(
        RaftPeerId.valueOf("s0"), RaftGroupId.randomId()), () -> -1, new RaftProperties()) {
      @Override
      public long getFlushIndex() {
        return flushIndex.get();
      }
    }) {
      log.open(RaftLog.INVALID_LOG_INDEX, null);
      log.append(Arrays.asList(entry(1, 0), entry(1, 1), entry(2, 2))).forEach(CompletableFuture::join);
      Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(1, 1)), "entry is not flushed");
      Assertions.assertEquals(-1, log.getLastCommittedIndex(), "do not partially commit an unflushed hint");
      flushIndex.set(2);
      Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(2, 1)), "term does not match");
      Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(2, 3)), "entry is missing");
      Assertions.assertEquals(-1, log.getLastCommittedIndex());
      Assertions.assertTrue(log.updateCommitIndex(TermIndex.valueOf(1, 1)));
      Assertions.assertEquals(1, log.getLastCommittedIndex(), "a matching older-term entry can be committed");
      Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(1, 1)));
      Assertions.assertTrue(log.updateCommitIndex(TermIndex.valueOf(2, 2)));
      Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(1, 0)));
      Assertions.assertEquals(2, log.getLastCommittedIndex(), "commit index never decreases");
    }
  }

  @Test
  public void testDelayedCommitIndexAfterLogReplacement() throws Exception {
    try (MemoryRaftLog log = new MemoryRaftLog(RaftGroupMemberId.valueOf(
        RaftPeerId.valueOf("s0"), RaftGroupId.randomId()), () -> -1, new RaftProperties())) {
      log.open(RaftLog.INVALID_LOG_INDEX, null);
      log.append(Arrays.asList(entry(1, 0), entry(1, 1))).forEach(CompletableFuture::join);
      log.append(Collections.singletonList(entry(2, 1))).forEach(CompletableFuture::join);
      Assertions.assertFalse(log.updateCommitIndex(TermIndex.valueOf(1, 1)));
      Assertions.assertEquals(-1, log.getLastCommittedIndex());
      Assertions.assertTrue(log.updateCommitIndex(TermIndex.valueOf(2, 1)));
      Assertions.assertEquals(1, log.getLastCommittedIndex());
    }
  }

  private static LogEntryProto entry(long term, long index) {
    return LogEntryProto.newBuilder().setTerm(term).setIndex(index).build();
  }
}
