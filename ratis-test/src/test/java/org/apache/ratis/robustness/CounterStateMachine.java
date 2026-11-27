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

import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.util.AtomicFileOutputStream;
import org.apache.ratis.util.MD5FileUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

/** Persistent counter fixture for histories spanning restart and snapshot installation. */
public class CounterStateMachine extends BaseStateMachine {
  public static final Message INCREMENT = Message.valueOf("increment");
  public static final Message QUERY = Message.valueOf("query");

  private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();
  private long counter;

  @Override
  public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
    super.initialize(server, groupId, raftStorage);
    storage.init(raftStorage);
    reinitialize();
  }

  @Override
  public synchronized void reinitialize() throws IOException {
    final SingleFileSnapshotInfo snapshot = storage.loadLatestSnapshot();
    counter = 0;
    if (snapshot != null) {
      final File file = snapshot.getFile().getPath().toFile();
      final MD5Hash digest = snapshot.getFile().getFileDigest();
      if (digest != null) {
        MD5FileUtil.verifySavedMD5(file, digest);
      }
      try (DataInputStream in = new DataInputStream(Files.newInputStream(file.toPath()))) {
        counter = in.readLong();
        if (counter < 0 || in.read() != -1) {
          throw new IOException("Invalid counter snapshot " + file);
        }
      }
      setLastAppliedTermIndex(snapshot.getTermIndex());
    }
    super.reinitialize();
  }

  @Override
  public SimpleStateMachineStorage getStateMachineStorage() {
    return storage;
  }

  @Override
  public synchronized CompletableFuture<Message> applyTransaction(TransactionContext transaction) {
    if (!INCREMENT.getContent().equals(transaction.getLogEntry().getStateMachineLogEntry().getLogData())) {
      throw new IllegalArgumentException("Unexpected counter command");
    }
    counter++;
    updateLastAppliedTermIndex(TermIndex.valueOf(transaction.getLogEntry()));
    return CompletableFuture.completedFuture(Message.valueOf(Long.toString(counter)));
  }

  @Override
  public synchronized CompletableFuture<Message> query(Message request) {
    if (!QUERY.getContent().equals(request.getContent())) {
      throw new IllegalArgumentException("Unexpected counter query");
    }
    return CompletableFuture.completedFuture(Message.valueOf(Long.toString(counter)));
  }

  @Override
  public synchronized long takeSnapshot() throws IOException {
    final TermIndex applied = getLastAppliedTermIndex();
    final File file = storage.getSnapshotFile(applied.getTerm(), applied.getIndex());
    try (DataOutputStream out = new DataOutputStream(new AtomicFileOutputStream(file))) {
      out.writeLong(counter);
    }
    final MD5Hash digest = MD5FileUtil.computeAndSaveMd5ForFile(file);
    storage.updateLatestSnapshot(new SingleFileSnapshotInfo(new FileInfo(file.toPath(), digest), applied));
    return applied.getIndex();
  }
}
