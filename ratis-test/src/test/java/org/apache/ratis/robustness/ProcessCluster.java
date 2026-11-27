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

import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.TimeDuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Three server JVMs, with logs and durable state retained beside the client history. */
final class ProcessCluster implements AutoCloseable {
  final RaftGroup group;
  private final Path directory;
  private final boolean lease;
  private final Map<RaftPeerId, Process> processes = new LinkedHashMap<>();

  ProcessCluster(Path directory, boolean lease) throws IOException {
    this.directory = directory.toAbsolutePath();
    this.lease = lease;
    Files.createDirectories(this.directory);
    final List<RaftPeer> peers = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      peers.add(RaftPeer.newBuilder().setId("p" + i).setAddress("127.0.0.1:" + NetUtils.getFreePort()).build());
    }
    group = RaftGroup.valueOf(RaftGroupId.randomId(), peers);
  }

  RaftClient client(RaftPeerId leader) {
    final RaftProperties properties = new RaftProperties();
    RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
    RaftClientConfigKeys.Rpc.setRequestTimeout(properties, TimeDuration.ONE_SECOND);
    RaftClientConfigKeys.Async.Experimental.setSendDummyRequest(properties, true);
    return RaftClient.newBuilder().setRaftGroup(group).setLeaderId(leader).setProperties(properties)
        .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(10,
            TimeDuration.valueOf(100, TimeUnit.MILLISECONDS))).build();
  }

  void start(boolean format) throws Exception {
    // Start all three before waiting, so election begins with a quorum available.
    for (RaftPeer peer : group.getPeers()) {
      launch(peer.getId(), format);
    }
    for (RaftPeer peer : group.getPeers()) {
      awaitReady(peer.getId());
    }
  }

  void start(RaftPeerId peer, boolean format) throws Exception {
    launch(peer, format);
    awaitReady(peer);
  }

  private Path ready(RaftPeerId peer) {
    return directory.resolve(peer + ".ready");
  }

  private void launch(RaftPeerId peer, boolean format) throws IOException {
    final Process old = processes.get(peer);
    if (old != null && old.isAlive()) {
      throw new IOException("Server process is still alive: " + peer);
    }
    Files.deleteIfExists(ready(peer));
    Files.deleteIfExists(directory.resolve(peer + ".snapshot"));
    final List<String> command = new ArrayList<>(Arrays.asList(
        Paths.get(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
        "-Djava.net.preferIPv4Stack=true", "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        RaftServerProcess.class.getName(), peer.toString(), group.getGroupId().getUuid().toString(),
        directory.resolve("storage").resolve(peer.toString()).toString(), Boolean.toString(format),
        Boolean.toString(lease), ready(peer).toString()));
    for (RaftPeer server : group.getPeers()) {
      command.add(server.getId() + "=" + server.getAddress());
    }
    final Process process = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve(peer + ".log").toFile())).start();
    processes.put(peer, process);
  }

  long snapshotIndex(RaftPeerId peer) throws IOException {
    return Long.parseLong(new String(Files.readAllBytes(directory.resolve(peer + ".snapshot")), StandardCharsets.UTF_8).trim());
  }

  private void awaitReady(RaftPeerId peer) throws IOException, InterruptedException {
    final Process process = processes.get(peer);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!Files.isRegularFile(ready(peer))) {
      if (!process.isAlive()) {
        throw new IOException("Server " + peer + " exited " + process.exitValue() + "; see " + directory.resolve(peer + ".log"));
      }
      if (System.nanoTime() >= deadline) {
        throw new IOException("Server " + peer + " did not start; see " + directory.resolve(peer + ".log"));
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
  }

  void crash(RaftPeerId peer) throws IOException, InterruptedException {
    final Process process = processes.get(peer);
    process.destroyForcibly();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      throw new IOException("Unable to terminate server " + peer);
    }
  }

  void signal(RaftPeerId peer, String signal) throws IOException, InterruptedException {
    if (!"-STOP".equals(signal) && !"-CONT".equals(signal)) {
      throw new IllegalArgumentException("Unsupported process signal " + signal);
    }
    final Process process = processes.get(peer);
    if (process == null || !process.isAlive()) {
      throw new IOException("Server process is not alive: " + peer);
    }
    final String pid = new String(Files.readAllBytes(ready(peer)), StandardCharsets.UTF_8).trim();
    if (!pid.matches("[1-9][0-9]*")) {
      throw new IOException("Invalid child PID for " + peer);
    }
    final Process command = new ProcessBuilder("kill", signal, pid).start();
    try {
      if (!command.waitFor(5, TimeUnit.SECONDS) || command.exitValue() != 0) {
        throw new IOException("Failed to send " + signal + " to server " + peer);
      }
    } finally {
      if (command.isAlive()) {
        command.destroyForcibly();
      }
    }
  }

  @Override
  public void close() throws IOException, InterruptedException {
    for (Process process : processes.values()) {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    IOException failure = null;
    for (Map.Entry<RaftPeerId, Process> entry : processes.entrySet()) {
      if (!entry.getValue().waitFor(10, TimeUnit.SECONDS)) {
        failure = new IOException("Server process did not terminate: " + entry.getKey());
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
