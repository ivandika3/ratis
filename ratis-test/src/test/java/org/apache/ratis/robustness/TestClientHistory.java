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
package org.apache.ratis.robustness;

import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestClientHistory {
  @TempDir
  private Path directory;

  private static RaftClientReply reply(String value) {
    return RaftClientReply.newBuilder().setClientId(ClientId.randomId()).setServerId(RaftPeerId.valueOf("s0"))
        .setGroupId(RaftGroupId.randomId()).setCallId(42).setSuccess().setMessage(Message.valueOf(value)).build();
  }

  private String save(ClientHistory history) throws IOException {
    history.write(directory);
    return new String(Files.readAllBytes(directory.resolve("history.json")), StandardCharsets.UTF_8);
  }

  @Test
  public void testRecordsCompletionBeforeDependentInvocation() throws Exception {
    final ClientHistory history = new ClientHistory();
    final CompletableFuture<RaftClientReply> request = new CompletableFuture<>();
    final CompletableFuture<Long> observed = history.record(0, "client", "increment", "s0", () -> request);
    request.complete(reply("1"));
    assertEquals(1L, observed.join().longValue());
    history.record(1, "other", "query", "s1", () -> CompletableFuture.completedFuture(reply("1"))).join();
    final String json = save(history);
    assertTrue(json.contains("\"call\":1,\"return\":2"), json);
    assertTrue(json.contains("\"call\":3,\"return\":4"), json);
    assertTrue(json.contains("\"callId\":42"), json);
    assertEquals(1, history.countSuccessful("increment"));
    assertEquals(1, history.countSuccessful("query"));
  }

  @Test
  public void testPendingAndFailedRequestsRemainUnknown() throws Exception {
    final ClientHistory history = new ClientHistory();
    history.record(0, "client", "increment", "s0", CompletableFuture::new);
    final CompletableFuture<Long> failed = history.record(1, "client", "increment", "s0", () -> {
      throw new IllegalStateException("connection lost");
    });
    assertThrows(CompletionException.class, failed::join);
    final String json = save(history);
    assertTrue(json.contains("\"call\":1,\"return\":0,\"outcome\":\"unknown\""), json);
    assertTrue(json.contains("\"call\":2,\"return\":3,\"outcome\":\"unknown\""), json);
    assertTrue(json.contains("connection lost"), json);
    assertEquals(0, history.countSuccessful("increment"));
  }

  @Test
  public void testOutOfOrderAsyncCompletionIsPreserved() throws Exception {
    final ClientHistory history = new ClientHistory();
    final CompletableFuture<RaftClientReply> first = new CompletableFuture<>();
    final CompletableFuture<RaftClientReply> second = new CompletableFuture<>();
    final CompletableFuture<Long> firstObserved = history.record(0, "client", "increment", "s0", () -> first);
    final CompletableFuture<Long> secondObserved = history.record(0, "client", "increment", "s0", () -> second);
    second.complete(reply("2"));
    first.complete(reply("1"));
    assertEquals(2L, secondObserved.join().longValue());
    assertEquals(1L, firstObserved.join().longValue());
    final String json = save(history);
    assertTrue(json.contains("\"call\":1,\"return\":4"), json);
    assertTrue(json.contains("\"call\":2,\"return\":3"), json);
  }

  @Test
  public void testMalformedSuccessfulReplyCannotBecomeUnknown() throws Exception {
    final ClientHistory history = new ClientHistory();
    final CompletableFuture<Long> observed = history.record(0, "client", "query", "s0",
        () -> CompletableFuture.completedFuture(reply("malformed")));
    assertThrows(CompletionException.class, observed::join);
    final String json = save(history);
    assertTrue(json.contains("\"outcome\":\"error\""), json);
  }

  @Test
  public void testCancellationRemainsUnknownAfterLateReply() throws Exception {
    final ClientHistory history = new ClientHistory();
    final CompletableFuture<RaftClientReply> request = new CompletableFuture<>();
    final CompletableFuture<Long> observed = history.record(0, "client", "increment", "s0", () -> request);
    assertTrue(observed.cancel(false));
    request.complete(reply("1"));
    final String json = save(history);
    assertTrue(json.contains("\"call\":1,\"return\":2,\"outcome\":\"unknown\""), json);
    assertEquals(0, history.countSuccessful("increment"));
  }

  @Test
  public void testMetadataFaultsAndEscaping() throws Exception {
    final ClientHistory history = new ClientHistory();
    history.putMetadata("seed", "123");
    history.putMetadata("message", "quote\"\n\t\\");
    history.fault("isolate", "s0");
    history.record(0, "client", "query", "s0", () -> CompletableFuture.completedFuture(reply("0"))).join();
    final String json = save(history);
    assertTrue(json.contains("\"seed\":\"123\""), json);
    assertTrue(json.contains("quote\\\"\\n\\t\\\\"), json);
    assertTrue(json.contains("\"index\":1,\"action\":\"isolate\""), json);
    assertTrue(json.contains("\"call\":2,\"return\":3"), json);
  }
}
