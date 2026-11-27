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

import org.apache.ratis.BaseTest;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.proto.RaftProtos.RaftPeerRole;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.util.JavaUtils;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual process termination and suspension, with the recorder surviving independently of the Raft servers. */
@Timeout(180)
@EnabledIfSystemProperty(named = "ratis.robustness.enabled", matches = "true")
public class TestRobustnessWithProcesses extends BaseTest {
  @ParameterizedTest(name = "crash, lease={0}")
  @ValueSource(booleans = {false, true})
  public void testCrashAndRecover(boolean lease) throws Exception {
    run(false, lease);
  }

  @ParameterizedTest(name = "pause, lease={0}")
  @ValueSource(booleans = {false, true})
  @EnabledOnOs({OS.LINUX, OS.MAC})
  public void testPausedLeaderLease(boolean lease) throws Exception {
    run(true, lease);
  }

  private static CompletableFuture<Long> send(ClientHistory history, int worker, RaftClient client, boolean write,
      RaftPeerId peer) {
    return history.record(worker, client.getId().toString(), write? "increment" : "query",
        peer == null? "leader" : peer.toString(), () -> write? client.async().send(CounterStateMachine.INCREMENT)
            : client.async().sendReadOnly(CounterStateMachine.QUERY, peer));
  }

  private void run(boolean pause, boolean lease) throws Exception {
    final LinearizabilityChecker checker = LinearizabilityChecker.fromSystemProperties();
    final long seed = Long.getLong("ratis.robustness.seed", 1);
    final int count = Integer.getInteger("ratis.robustness.operations", 30);
    assertTrue(count >= 10 && count <= 1000, "operations per worker must be between 10 and 1000");
    final Path report = Paths.get(System.getProperty("ratis.robustness.reports", "target/robustness/histories"))
        .resolve("PROCESS-" + (pause? "PAUSE" : "CRASH") + "-lease" + lease + "-seed" + seed + "-" + UUID.randomUUID());
    final ClientHistory history = new ClientHistory();
    history.putMetadata("seed", Long.toString(seed));
    history.putMetadata("scenario", pause? "process-pause" : "process-crash");
    history.putMetadata("leaderLease", Boolean.toString(lease));
    history.putMetadata("readIndex", "COMMIT_INDEX");
    history.putMetadata("operationsPerWorker", Integer.toString(count));
    history.putMetadata("revision", System.getProperty("ratis.robustness.revision", "unknown"));
    history.putMetadata("workingTreeDirty", System.getProperty("ratis.robustness.dirty", "unknown"));
    try (ProcessCluster cluster = new ProcessCluster(report, lease)) {
      history.putMetadata("group", cluster.group.toString());
      cluster.start(true);
      final RaftPeerId leader = writeUntilReady(cluster, history, null);
      exercise(cluster, history, leader, seed, count, pause);
      if (!pause) {
        final Map<RaftPeerId, Long> snapshots = new LinkedHashMap<>();
        if (lease) {
          try (RaftClient client = cluster.client(null)) {
            for (RaftPeer peer : cluster.group.getPeers()) {
              history.fault("snapshot", peer.getId().toString());
              final org.apache.ratis.protocol.RaftClientReply reply = client.getSnapshotManagementApi(peer.getId())
                  .create(true, 10000);
              assertTrue(reply.isSuccess());
              snapshots.put(peer.getId(), reply.getLogIndex());
            }
          }
        }
        history.fault("crash-all", cluster.group.toString());
        for (RaftPeer peer : cluster.group.getPeers()) {
          cluster.crash(peer.getId());
        }
        history.fault("recover-all", cluster.group.toString());
        cluster.start(false);
        for (RaftPeer peer : cluster.group.getPeers()) {
          final long loaded = cluster.snapshotIndex(peer.getId());
          assertTrue(loaded >= snapshots.getOrDefault(peer.getId(), -1L));
          history.putMetadata("loadedSnapshot." + peer.getId(), Long.toString(loaded));
        }
        writeUntilReady(cluster, history, null);
        finalReads(cluster, history);
      }
    } catch (Throwable failure) {
      history.putMetadata("harnessFailure", failure.toString());
      throw failure;
    } finally {
      history.write(report);
    }
    assertTrue(history.countSuccessful("increment") >= 8, "Insufficient write coverage; " + report);
    assertTrue(history.countSuccessful("query") >= 3, "Insufficient read coverage; " + report);
    assertEquals(LinearizabilityChecker.Verdict.VALID, checker.check(report), "See " + report);
  }

  private RaftPeerId writeUntilReady(ProcessCluster cluster, ClientHistory history, RaftPeerId preferred) throws Exception {
    return JavaUtils.attempt(() -> {
      try (RaftClient client = cluster.client(preferred)) {
        send(history, 10, client, true, null).get(5, TimeUnit.SECONDS);
        return client.getLeaderId();
      }
    }, 20, HUNDRED_MILLIS, () -> "successful write to process cluster", LOG);
  }

  private void finalReads(ProcessCluster cluster, ClientHistory history) throws Exception {
    for (RaftPeer peer : cluster.group.getPeers()) {
      JavaUtils.attempt(() -> {
        try (RaftClient client = cluster.client(null)) {
          send(history, 11, client, false, peer.getId()).get(5, TimeUnit.SECONDS);
        }
      }, 100, HUNDRED_MILLIS, "final read " + peer.getId(), LOG);
    }
  }

  private void exercise(ProcessCluster cluster, ClientHistory history, RaftPeerId leader, long seed, int count, boolean pause)
      throws Exception {
    final ExecutorService workers = Executors.newFixedThreadPool(3);
    final CountDownLatch issued = new CountDownLatch(3);
    final CountDownLatch disrupted = new CountDownLatch(1);
    final List<Future<?>> tasks = new ArrayList<>();
    final List<RaftPeer> peers = new ArrayList<>(cluster.group.getPeers());
    try {
      for (int worker = 0; worker < 3; worker++) {
        final int id = worker;
        tasks.add(workers.submit(() -> {
          RaftClient client = cluster.client(leader);
          final Random random = new Random(seed + id);
          try {
            for (int n = 0; n < count; n++) {
              final boolean write = n % 2 == 0;
              final CompletableFuture<Long> request = send(history, id, client, write,
                  write? null : peers.get(random.nextInt(peers.size())).getId());
              if (n == 0) {
                if (pause) {
                  request.get(10, TimeUnit.SECONDS);
                }
                issued.countDown();
                disrupted.await();
              }
              try {
                request.get(10, TimeUnit.SECONDS);
              } catch (ExecutionException | TimeoutException e) {
                request.cancel(false);
                client.close();
                client = cluster.client(null);
              }
            }
          } finally {
            client.close();
          }
          return null;
        }));
      }
      assertTrue(issued.await(10, TimeUnit.SECONDS), "Workers did not issue operations");
      if (pause) {
        try (RaftClient reader = cluster.client(leader)) {
          send(history, 13, reader, false, leader).get(5, TimeUnit.SECONDS);
        }
      }
      history.fault(pause? "pause" : "crash", leader.toString());
      if (pause) {
        cluster.signal(leader, "-STOP");
      } else {
        cluster.crash(leader);
      }
      disrupted.countDown();
      final RaftPeerId candidate = JavaUtils.attempt(() -> {
        try (RaftClient admin = cluster.client(null)) {
          for (RaftPeer peer : peers) {
            if (!peer.getId().equals(leader)
                && admin.getGroupManagementApi(peer.getId()).info(cluster.group.getGroupId())
                    .getRoleInfoProto().getRole() == RaftPeerRole.LEADER) {
              return peer.getId();
            }
          }
        }
        throw new IllegalStateException("No replacement leader yet");
      }, 100, HUNDRED_MILLIS, () -> "replacement process leader", LOG);
      final RaftPeerId next = writeUntilReady(cluster, history, candidate);
      assertNotEquals(leader, next, "Disrupted leader still acknowledged a newly invoked write");
      if (pause) {
        try (RaftClient reader = cluster.client(next)) {
          final CompletableFuture<Long> read = send(history, 12, reader, false, leader);
          history.fault("resume", leader.toString());
          cluster.signal(leader, "-CONT");
          try {
            read.get(10, TimeUnit.SECONDS);
          } catch (ExecutionException e) {
            LOG.info("Read to resumed leader failed: {}", e.getCause().toString());
          }
        }
      } else {
        history.fault("restart", leader.toString());
        cluster.start(leader, false);
      }
      for (Future<?> task : tasks) {
        task.get(40, TimeUnit.SECONDS);
      }
      writeUntilReady(cluster, history, next);
      finalReads(cluster, history);
    } finally {
      disrupted.countDown();
      try {
        if (pause) {
          cluster.signal(leader, "-CONT");
        }
      } finally {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Workload threads did not stop");
      }
    }
  }
}
