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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestLinearizabilityChecker {
  @TempDir
  private Path directory;

  private void record(ClientHistory history, String function, long value) {
    history.record(0, "client", function, "s0", () -> CompletableFuture.completedFuture(RaftClientReply.newBuilder()
        .setClientId(ClientId.randomId()).setServerId(RaftPeerId.valueOf("s0")).setGroupId(RaftGroupId.randomId())
        .setSuccess().setMessage(Message.valueOf(Long.toString(value))).build())).join();
  }

  @Test
  public void testMissingExecutableFailsClearly() throws Exception {
    final ClientHistory history = new ClientHistory();
    record(history, "query", 0);
    history.write(directory);
    final IOException exception = assertThrows(IOException.class,
        () -> new LinearizabilityChecker(directory.resolve("missing"), 1000).check(directory));
    assertTrue(exception.getMessage().contains("executable"), exception.toString());
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  public void testMalformedCheckerOutputFails() throws Exception {
    final Path executable = directory.resolve("fake-checker");
    final String script = "#!/bin/sh\ncd \"$4\" || exit 3\nprintf valid > verdict.txt\n"
        + "printf 'not JSON' > result.json\nprintf 'not JSON' > normalized.json\nprintf 'not HTML' > history.html\n";
    Files.write(executable, script.getBytes(StandardCharsets.UTF_8));
    assertTrue(executable.toFile().setExecutable(true));
    final IOException exception = assertThrows(IOException.class,
        () -> new LinearizabilityChecker(executable, 1000).check(directory));
    assertTrue(exception.getMessage().contains("result"), exception.toString());
  }

  @Test
  @EnabledIfSystemProperty(named = "ratis.robustness.enabled", matches = "true")
  public void testRecordedHistoryAndOfflineReplay() throws Exception {
    final ClientHistory history = new ClientHistory();
    record(history, "increment", 1);
    record(history, "query", 1);
    history.write(directory);
    final LinearizabilityChecker checker = LinearizabilityChecker.fromSystemProperties();
    assertEquals(LinearizabilityChecker.Verdict.VALID, checker.check(directory));
    assertTrue(Files.size(directory.resolve("history.html")) > 0);
    assertEquals(LinearizabilityChecker.Verdict.VALID, checker.check(directory));
  }

  @Test
  @EnabledIfSystemProperty(named = "ratis.robustness.enabled", matches = "true")
  public void testStaleReadIsInvalid() throws Exception {
    final ClientHistory history = new ClientHistory();
    record(history, "increment", 1);
    record(history, "query", 0);
    history.write(directory);
    assertEquals(LinearizabilityChecker.Verdict.INVALID, LinearizabilityChecker.fromSystemProperties().check(directory));
  }

  @Test
  @EnabledIfSystemProperty(named = "ratis.robustness.enabled", matches = "true")
  public void testMalformedInputIsToolError() throws Exception {
    Files.write(directory.resolve("history.json"), "{}".getBytes(StandardCharsets.UTF_8));
    final IOException exception = assertThrows(IOException.class,
        () -> LinearizabilityChecker.fromSystemProperties().check(directory));
    assertTrue(exception.getMessage().contains("exit 3"), exception.toString());
    assertTrue(Files.exists(directory.resolve("checker-stdout.json")));
  }
}
