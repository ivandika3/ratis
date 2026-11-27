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
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.SizeInBytes;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Child JVM entry point; the parent test owns process termination and preserves its storage. */
public final class RaftServerProcess {
  private RaftServerProcess() {
  }

  public static void main(String[] args) throws Exception {
    final RaftPeerId id = RaftPeerId.valueOf(args[0]);
    final List<RaftPeer> peers = new ArrayList<>();
    for (int i = 6; i < args.length; i++) {
      final String[] peer = args[i].split("=", 2);
      peers.add(RaftPeer.newBuilder().setId(peer[0]).setAddress(peer[1]).build());
    }
    final RaftGroup group = RaftGroup.valueOf(RaftGroupId.valueOf(UUID.fromString(args[1])), peers);
    final RaftProperties properties = new RaftProperties();
    RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
    GrpcConfigKeys.Server.setHost(properties, "127.0.0.1");
    GrpcConfigKeys.Server.setPort(properties, NetUtils.createSocketAddr(group.getPeer(id).getAddress()).getPort());
    RaftServerConfigKeys.setStorageDir(properties, Collections.singletonList(Paths.get(args[2]).toFile()));
    RaftServerConfigKeys.Read.setOption(properties, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
    RaftServerConfigKeys.Read.setLeaderLeaseEnabled(properties, Boolean.parseBoolean(args[4]));
    RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(properties, false);
    RaftServerConfigKeys.Snapshot.setCreationGap(properties, 1);
    RaftServerConfigKeys.Log.setPreallocatedSize(properties, SizeInBytes.valueOf("1KB"));
    final RaftServer server = RaftServer.newBuilder().setServerId(id).setGroup(group).setProperties(properties)
        .setStateMachine(new CounterStateMachine()).setOption(Boolean.parseBoolean(args[3])?
            RaftStorage.StartupOption.FORMAT : RaftStorage.StartupOption.RECOVER).build();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        server.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }));
    server.start();
    final org.apache.ratis.statemachine.SnapshotInfo snapshot = server.getDivision(group.getGroupId())
        .getStateMachine().getLatestSnapshot();
    Files.write(Paths.get(args[5]).resolveSibling(id + ".snapshot"),
        Long.toString(snapshot == null? -1 : snapshot.getIndex()).getBytes(StandardCharsets.UTF_8));
    final String pid = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
    Files.write(Paths.get(args[5]), pid.getBytes(StandardCharsets.UTF_8));
    new CountDownLatch(1).await();
  }
}
