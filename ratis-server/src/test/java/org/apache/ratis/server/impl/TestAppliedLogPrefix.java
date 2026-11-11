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
import org.apache.ratis.protocol.Message;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

public class TestAppliedLogPrefix {
  @Test
  public void testQueryCannotCompleteAcrossStateReplacement() {
    final AppliedLogPrefix prefix = new AppliedLogPrefix(-1);
    prefix.add(LogEntryProto.newBuilder().setIndex(0).setAppendTimestampMs(0).build(), null);
    Assertions.assertTrue(prefix.canServe(10, 10, 0, 0), "Epoch zero is a present timestamp");
    final CompletableFuture<Message> query = new CompletableFuture<>();
    final CompletableFuture<Message> result = prefix.query(prefix.getGeneration(), () -> query);
    prefix.invalidate();
    prefix.reset(0);
    query.complete(Message.EMPTY);
    Assertions.assertTrue(result.isCompletedExceptionally());
    Assertions.assertFalse(prefix.canServe(10, 10, 0, 0));
  }

  @Test
  public void testOutOfOrderApplicationAndMetadata() {
    final AppliedLogPrefix prefix = new AppliedLogPrefix(0);
    final CompletableFuture<Message> first = new CompletableFuture<>();
    prefix.add(LogEntryProto.newBuilder().setIndex(1).setAppendTimestampMs(100).build(), first);
    prefix.add(LogEntryProto.newBuilder().setIndex(2).setAppendTimestampMs(110).build(), null);
    final CompletableFuture<Long> waiting = prefix.waitToAdvance(2, prefix.getGeneration());
    Assertions.assertFalse(waiting.isDone());
    Assertions.assertFalse(prefix.canServe(120, 30, 5, 0));
    first.complete(Message.EMPTY);
    Assertions.assertEquals(2L, waiting.join());
    Assertions.assertTrue(prefix.canServe(120, 20, 5, 2));
    Assertions.assertFalse(prefix.canServe(120, 19, 5, 2));
    Assertions.assertFalse(prefix.canServe(120, 20, 5, 3));
    Assertions.assertFalse(prefix.canServe(110, 0, 0, 2));
    Assertions.assertFalse(prefix.canServe(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 2));
  }

  @Test
  public void testRecoveryIgnoresOldCompletions() {
    final AppliedLogPrefix prefix = new AppliedLogPrefix(0);
    final CompletableFuture<Message> old = new CompletableFuture<>();
    prefix.add(LogEntryProto.newBuilder().setIndex(1).setAppendTimestampMs(100).build(), old);
    final long generation = prefix.getGeneration();
    final CompletableFuture<Long> waiting = prefix.waitToAdvance(1, generation);
    prefix.invalidate();
    prefix.reset(10);
    old.complete(Message.EMPTY);
    Assertions.assertTrue(waiting.isCompletedExceptionally());
    Assertions.assertTrue(prefix.waitToAdvance(1, generation).isCompletedExceptionally());
    Assertions.assertFalse(prefix.canServe(120, 100, 0, 10));
    prefix.add(LogEntryProto.newBuilder().setIndex(11).build(), null);
    Assertions.assertFalse(prefix.canServe(120, 100, 0, 11));
    prefix.add(LogEntryProto.newBuilder().setIndex(12).setAppendTimestampMs(100).build(), null);
    Assertions.assertTrue(prefix.canServe(120, 20, 0, 12));
    prefix.add(LogEntryProto.newBuilder().setIndex(13).setAppendTimestampMs(90).build(), null);
    Assertions.assertFalse(prefix.canServe(120, 20, 0, 13));
  }

  @Test
  public void testFailedApplyCannotAuthorizeReads() {
    final AppliedLogPrefix prefix = new AppliedLogPrefix(0);
    final CompletableFuture<Message> failed = new CompletableFuture<>();
    prefix.add(LogEntryProto.newBuilder().setIndex(1).setAppendTimestampMs(100).build(), failed);
    final CompletableFuture<Long> waiting = prefix.waitToAdvance(1, prefix.getGeneration());
    failed.completeExceptionally(new IllegalStateException("apply failed"));
    Assertions.assertTrue(waiting.isCompletedExceptionally());
    Assertions.assertFalse(prefix.canServe(100, 100, 0, 1));
  }
}
