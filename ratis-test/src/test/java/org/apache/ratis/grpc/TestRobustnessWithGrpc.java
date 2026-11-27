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

import org.apache.ratis.BaseTest;
import org.apache.ratis.RaftTestUtil;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.robustness.ClientHistory;
import org.apache.ratis.robustness.CounterStateMachine;
import org.apache.ratis.robustness.LinearizabilityChecker;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.RaftServerConfigKeys.Read.ReadIndex.Type;
import org.apache.ratis.server.impl.BlockRequestHandlingInjection;
import org.apache.ratis.server.impl.MiniRaftCluster;
import org.apache.ratis.server.impl.PeerChanges;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.SizeInBytes;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole-history validation under concurrent traffic and controlled cluster disruptions. */
@EnabledIfSystemProperty(named = "ratis.robustness.enabled", matches = "true")
public class TestRobustnessWithGrpc extends BaseTest {
  enum Scenario {
    NONE, LEADER_ISOLATION, FOLLOWER_ISOLATION, QUORUM_LOSS, LEADER_RESTART, SNAPSHOT_RESTART, SNAPSHOT_INSTALLATION,
    MEMBERSHIP_CHANGE
  }

  static Stream<Arguments> readModes() {
    return Stream.of(Type.values()).flatMap(type -> Stream.of(false, true).map(lease -> Arguments.of(type, lease)));
  }

  static Stream<Arguments> faultModes() {
    return Stream.of(Scenario.values()).filter(scenario -> scenario != Scenario.NONE)
        .flatMap(scenario -> Stream.of(Type.values()).flatMap(type ->
            Stream.of(false, true).map(lease -> Arguments.of(scenario, type, lease))));
  }

  @ParameterizedTest(name = "{0}, lease={1}")
  @MethodSource("readModes")
  public void testReadModes(Type type, boolean lease) throws Exception {
    run(Scenario.NONE, type, lease);
  }

  @ParameterizedTest(name = "{0}, {1}, lease={2}")
  @MethodSource("faultModes")
  public void testFaults(Scenario scenario, Type type, boolean lease) throws Exception {
    run(scenario, type, lease);
  }

  private static RaftClient client(MiniRaftCluster cluster) {
    return cluster.createClient(RetryPolicies.retryUpToMaximumCountWithFixedSleep(4, HUNDRED_MILLIS));
  }

  private static CompletableFuture<Long> send(ClientHistory history, int worker, RaftClient client, boolean write,
      RaftPeerId peer) {
    return history.record(worker, client.getId().toString(), write? "increment" : "query",
        peer == null? "leader" : peer.toString(), () -> write? client.async().send(CounterStateMachine.INCREMENT)
            : client.async().sendReadOnly(CounterStateMachine.QUERY, peer));
  }

  private void run(Scenario scenario, Type type, boolean lease) throws Exception {
    final LinearizabilityChecker checker = LinearizabilityChecker.fromSystemProperties();
    final long seed = Long.getLong("ratis.robustness.seed", 1);
    final int count = Integer.getInteger("ratis.robustness.operations", 30);
    assertTrue(count >= 10 && count <= 1000, "operations per worker must be between 10 and 1000");
    final Path report = Paths.get(System.getProperty("ratis.robustness.reports", "target/robustness/histories"))
        .resolve(scenario + "-" + type + "-lease" + lease + "-seed" + seed + "-" + UUID.randomUUID());
    final ClientHistory history = new ClientHistory();
    history.putMetadata("seed", Long.toString(seed));
    history.putMetadata("scenario", scenario.toString());
    history.putMetadata("readIndex", type.toString());
    history.putMetadata("leaderLease", Boolean.toString(lease));
    history.putMetadata("operationsPerWorker", Integer.toString(count));
    history.putMetadata("workers", "3");
    history.putMetadata("retryPolicy", "4 attempts, 100ms sleep, 1s request timeout");
    history.putMetadata("revision", System.getProperty("ratis.robustness.revision", "unknown"));
    history.putMetadata("workingTreeDirty", System.getProperty("ratis.robustness.dirty", "unknown"));
    history.putMetadata("javaVersion", System.getProperty("java.version"));
    final RaftProperties properties = new RaftProperties();
    properties.setClass(MiniRaftCluster.STATEMACHINE_CLASS_KEY, CounterStateMachine.class, StateMachine.class);
    RaftServerConfigKeys.Read.setOption(properties, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
    RaftServerConfigKeys.Read.ReadIndex.setType(properties, type);
    RaftServerConfigKeys.Read.setLeaderLeaseEnabled(properties, lease);
    RaftClientConfigKeys.Async.Experimental.setSendDummyRequest(properties, true);
    RaftClientConfigKeys.Rpc.setRequestTimeout(properties, ONE_SECOND);
    RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(properties, false);
    RaftServerConfigKeys.Snapshot.setTriggerWhenStopEnabled(properties, false);
    RaftServerConfigKeys.Snapshot.setCreationGap(properties, 1);
    RaftServerConfigKeys.Log.setPurgeGap(properties, 1);
    RaftServerConfigKeys.Log.setPurgePreservationLogNum(properties, 0);
    RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex(properties, true);
    RaftServerConfigKeys.Log.setSegmentSizeMax(properties, SizeInBytes.valueOf("1KB"));
    RaftServerConfigKeys.Log.setPreallocatedSize(properties, SizeInBytes.valueOf("1KB"));
    LOG.info("Robustness seed={}, scenario={}, report={}", seed, scenario, report.toAbsolutePath());
    try (MiniRaftClusterWithGrpc cluster = MiniRaftClusterWithGrpc.FACTORY.newCluster(3, properties)) {
      history.putMetadata("group", cluster.getGroup().toString());
      cluster.start();
      exercise(cluster, scenario, count, seed, history);
    } catch (Throwable failure) {
      history.putMetadata("harnessFailure", failure.toString());
      throw failure;
    } finally {
      BlockRequestHandlingInjection.getInstance().unblockAll();
      history.write(report);
    }
    assertTrue(history.countSuccessful("increment") > 3, "Insufficient successful write coverage; " + report);
    assertTrue(history.countSuccessful("query") > 3, "Insufficient successful read coverage; " + report);
    assertEquals(LinearizabilityChecker.Verdict.VALID, checker.check(report), "See robustness report " + report);
  }

  private void exercise(MiniRaftClusterWithGrpc cluster, Scenario scenario, int count, long seed, ClientHistory history)
      throws Exception {
    final RaftPeerId leader = RaftTestUtil.waitForLeader(cluster).getId();
    final RaftPeerId follower = cluster.getFollowers().get(0).getId();
    final List<RaftPeerId> peers = cluster.getGroup().getPeers().stream().map(RaftPeer::getId).collect(Collectors.toList());
    try (RaftClient warmup = client(cluster)) {
      final int writes = scenario == Scenario.SNAPSHOT_INSTALLATION? 20 : 1;
      for (int i = 0; i < writes; i++) {
        send(history, 0, warmup, true, null).get(10, TimeUnit.SECONDS);
      }
      send(history, 0, warmup, false, follower).get(10, TimeUnit.SECONDS);
    }
    final int writesBeforeFault = history.countSuccessful("increment");
    final ExecutorService workers = Executors.newFixedThreadPool(4);
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch failedWrite = new CountDownLatch(1);
    final List<Future<?>> tasks = new ArrayList<>();
    final List<RaftClient> clients = new ArrayList<>();
    final List<RaftPeerId> blocked = new ArrayList<>();
    RaftPeerId stopped = null;
    RaftPeerId isolated = null;
    long stoppedIndex = -1;
    long requiredSnapshotIndex = -1;
    try {
      for (int i = 0; i < 3; i++) {
        final int worker = i;
        final RaftClient client = client(cluster);
        clients.add(client);
        tasks.add(workers.submit(() -> {
          RaftClient current = client;
          final Random random = new Random(seed + worker);
          try {
            start.await();
            for (int n = 0; n < count; n++) {
              final boolean write = n % 3 == 0 || random.nextBoolean();
              final RaftPeerId peer = write? null : peers.get(random.nextInt(peers.size()));
              final CompletableFuture<Long> request = send(history, worker + 1, current, write, peer);
              if (write) {
                request.whenComplete((value, error) -> {
                  if (error != null) {
                    failedWrite.countDown();
                  }
                });
              }
              try {
                request.get(10, TimeUnit.SECONDS);
              } catch (ExecutionException | TimeoutException e) {
                // Exhausting the retry policy closes an ordered window. Continue with a new client and operation ID.
                request.cancel(false);
                LOG.info("Worker {} request failed: {}", worker, e.toString());
                current.close();
                current = client(cluster);
              }
            }
          } finally {
            current.close();
          }
          return null;
        }));
      }
      if (scenario == Scenario.QUORUM_LOSS) {
        for (RaftPeerId peer : peers) {
          if (!peer.equals(leader)) {
            blocked.add(peer);
            BlockRequestHandlingInjection.getInstance().blockRequestor(peer.toString());
            BlockRequestHandlingInjection.getInstance().blockReplier(peer.toString());
          }
        }
        history.fault("lose-quorum", blocked.toString());
      } else if (scenario == Scenario.LEADER_ISOLATION || scenario == Scenario.FOLLOWER_ISOLATION) {
        isolated = scenario == Scenario.LEADER_ISOLATION? leader : follower;
        history.fault("isolate", isolated.toString());
        BlockRequestHandlingInjection.getInstance().blockRequestor(isolated.toString());
        BlockRequestHandlingInjection.getInstance().blockReplier(isolated.toString());
      } else if (scenario == Scenario.LEADER_RESTART || scenario == Scenario.SNAPSHOT_RESTART
          || scenario == Scenario.SNAPSHOT_INSTALLATION) {
        stopped = scenario == Scenario.SNAPSHOT_INSTALLATION? follower : leader;
        if (scenario == Scenario.SNAPSHOT_RESTART) {
          requiredSnapshotIndex = snapshot(cluster, history, leader);
        }
        history.fault("stop", stopped.toString());
        stoppedIndex = cluster.getDivision(stopped).getRaftLog().getLastCommittedIndex();
        cluster.killServer(stopped);
      }
      start.countDown();
      if (scenario == Scenario.QUORUM_LOSS) {
        assertTrue(failedWrite.await(15, TimeUnit.SECONDS), "No failed/uncertain write was observed without quorum");
        for (RaftPeerId peer : blocked) {
          BlockRequestHandlingInjection.getInstance().unblockRequestor(peer.toString());
          BlockRequestHandlingInjection.getInstance().unblockReplier(peer.toString());
        }
        history.fault("restore-quorum", blocked.toString());
      }
      if (scenario == Scenario.LEADER_ISOLATION || scenario == Scenario.LEADER_RESTART
          || scenario == Scenario.SNAPSHOT_RESTART) {
        JavaUtils.attempt(() -> {
          final RaftServer.Division next = cluster.getLeader();
          assertNotNull(next);
          assertTrue(!leader.equals(next.getId()) && next.getInfo().isLeaderReady());
        }, 100, HUNDRED_MILLIS, "new ready leader", LOG);
        history.fault("leader-elected", cluster.getLeader().getId().toString());
      }
      JavaUtils.attempt(() -> {
        assertTrue(history.countSuccessful("increment") >= writesBeforeFault + 5);
      }, 100, HUNDRED_MILLIS, "writes during disruption", LOG);
      if (scenario != Scenario.NONE) {
        readPeers(cluster, history, isolated != null? isolated : stopped);
      }
      if (isolated != null) {
        BlockRequestHandlingInjection.getInstance().unblockRequestor(isolated.toString());
        BlockRequestHandlingInjection.getInstance().unblockReplier(isolated.toString());
        history.fault("heal", isolated.toString());
        isolated = null;
      }
      if (scenario == Scenario.SNAPSHOT_INSTALLATION) {
        final RaftServer.Division current = RaftTestUtil.waitForLeader(cluster);
        try (RaftClient writer = client(cluster)) {
          for (int i = 0; i < 20; i++) {
            send(history, 5, writer, true, null).get(10, TimeUnit.SECONDS);
          }
        }
        final long snapshotIndex = snapshot(cluster, history, current.getId());
        requiredSnapshotIndex = snapshotIndex;
        current.getRaftLog().purge(snapshotIndex).get(10, TimeUnit.SECONDS);
        final long previousIndex = stoppedIndex;
        JavaUtils.attempt(() -> {
          assertTrue(current.getRaftLog().getStartIndex() > previousIndex + 1);
        }, 100, HUNDRED_MILLIS, "snapshot log purge", LOG);
      }
      if (stopped != null) {
        history.fault("restart", stopped + ", format=false");
        cluster.restartServer(stopped, false);
        final RaftPeerId restarted = stopped;
        JavaUtils.attempt(() -> {
          assertNotNull(cluster.getDivision(restarted).getInfo().getLeaderId());
        }, 100, HUNDRED_MILLIS, "restarted server knows leader", LOG);
        if (scenario == Scenario.SNAPSHOT_RESTART || scenario == Scenario.SNAPSHOT_INSTALLATION) {
          final long expectedSnapshotIndex = requiredSnapshotIndex;
          JavaUtils.attempt(() -> {
            assertNotNull(cluster.getDivision(restarted).getStateMachine().getLatestSnapshot());
            assertTrue(cluster.getDivision(restarted).getStateMachine().getLatestSnapshot().getIndex() >= expectedSnapshotIndex);
          }, 100, HUNDRED_MILLIS, "snapshot recovered/installed", LOG);
        }
        stopped = null;
      }
      if (scenario == Scenario.MEMBERSHIP_CHANGE) {
        changeConfiguration(cluster, history, workers);
      }
      for (Future<?> task : tasks) {
        task.get(40, TimeUnit.SECONDS);
      }
      JavaUtils.attempt(() -> {
        try (RaftClient recovered = client(cluster)) {
          send(history, 8, recovered, true, null).get(5, TimeUnit.SECONDS);
        }
      }, 20, HUNDRED_MILLIS, "write after recovery", LOG);
      readPeers(cluster, history, null);
    } finally {
      start.countDown();
      BlockRequestHandlingInjection.getInstance().unblockAll();
      workers.shutdownNow();
      for (RaftClient client : clients) {
        client.close();
      }
      assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Workload threads did not stop");
    }
  }

  private void readPeers(MiniRaftCluster cluster, ClientHistory history, RaftPeerId excluded) throws Exception {
    for (RaftPeer peer : cluster.getGroup().getPeers()) {
      if (peer.getId().equals(excluded)) {
        continue;
      }
      JavaUtils.attempt(() -> {
        try (RaftClient reader = client(cluster)) {
          send(history, 4, reader, false, peer.getId()).get(5, TimeUnit.SECONDS);
        }
      }, 20, HUNDRED_MILLIS, "read from " + peer.getId(), LOG);
    }
  }

  private static void changeConfiguration(MiniRaftCluster cluster, ClientHistory history, ExecutorService workers)
      throws Exception {
    try (RaftClient writer = client(cluster)) {
      final PeerChanges changes = cluster.addNewPeers(2, true);
      for (RaftPeer peer : changes.getAddedPeers()) {
        BlockRequestHandlingInjection.getInstance().blockReplier(peer.getId().toString());
      }
      final CountDownLatch started = new CountDownLatch(1);
      final Future<?> configuration = workers.submit(() -> {
        history.fault("set-configuration", changes.getPeersInNewConf().toString());
        started.countDown();
        cluster.setConfiguration(changes.getPeersInNewConf());
        return null;
      });
      try {
        assertTrue(started.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) {
          send(history, 6, writer, true, null).get(10, TimeUnit.SECONDS);
        }
        send(history, 6, writer, false, null).get(10, TimeUnit.SECONDS);
        assertFalse(configuration.isDone(), "New peer catch-up must still be blocked while traffic progresses");
      } finally {
        for (RaftPeer peer : changes.getAddedPeers()) {
          BlockRequestHandlingInjection.getInstance().unblockReplier(peer.getId().toString());
        }
      }
      configuration.get(10, TimeUnit.SECONDS);
      history.fault("configuration-committed", cluster.getGroup().toString());
      for (int i = 0; i < 5; i++) {
        send(history, 6, writer, true, null).get(10, TimeUnit.SECONDS);
      }
    }
  }

  private static long snapshot(MiniRaftCluster cluster, ClientHistory history, RaftPeerId peer) throws Exception {
    history.fault("snapshot", peer.toString());
    try (RaftClient client = client(cluster)) {
      final RaftClientReply reply = client.getSnapshotManagementApi(peer).create(true, 10000);
      assertTrue(reply.isSuccess());
      return reply.getLogIndex();
    }
  }
}
