/**
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
package org.apache.ratis.server.simulation;

import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftRpcMessage;
import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.InstallSnapshotReplyProto;
import org.apache.ratis.proto.RaftProtos.ReadCommittedEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.RequestVoteReplyProto;
import org.apache.ratis.proto.RaftProtos.StartLeaderElectionReplyProto;
import org.apache.ratis.util.ProtoUtils;

import java.util.Objects;

public class RaftServerReply implements RaftRpcMessage {
  private final AppendEntriesReplyProto appendEntries;
  private final RequestVoteReplyProto requestVote;
  private final InstallSnapshotReplyProto installSnapshot;
  private final StartLeaderElectionReplyProto startLeaderElection;
  private final ReadCommittedEntriesReplyProto readCommittedEntries;

  RaftServerReply(AppendEntriesReplyProto a) {
    appendEntries = Objects.requireNonNull(a);
    requestVote = null;
    installSnapshot = null;
    startLeaderElection = null;
    readCommittedEntries = null;
  }

  RaftServerReply(RequestVoteReplyProto r) {
    appendEntries = null;
    requestVote = Objects.requireNonNull(r);
    installSnapshot = null;
    startLeaderElection = null;
    readCommittedEntries = null;
  }

  RaftServerReply(InstallSnapshotReplyProto i) {
    appendEntries = null;
    requestVote = null;
    installSnapshot = Objects.requireNonNull(i);
    startLeaderElection = null;
    readCommittedEntries = null;
  }

  RaftServerReply(StartLeaderElectionReplyProto i) {
    appendEntries = null;
    requestVote = null;
    installSnapshot = null;
    startLeaderElection = Objects.requireNonNull(i);
    readCommittedEntries = null;
  }

  RaftServerReply(ReadCommittedEntriesReplyProto r) {
    appendEntries = null;
    requestVote = null;
    installSnapshot = null;
    startLeaderElection = null;
    readCommittedEntries = Objects.requireNonNull(r);
  }

  boolean isAppendEntries() {
    return appendEntries != null;
  }

  boolean isRequestVote() {
    return requestVote != null;
  }

  boolean isInstallSnapshot() {
    return installSnapshot != null;
  }

  boolean isStartLeaderElection() {
    return startLeaderElection != null;
  }

  boolean isReadCommittedEntries() {
    return readCommittedEntries != null;
  }

  AppendEntriesReplyProto getAppendEntries() {
    return appendEntries;
  }

  RequestVoteReplyProto getRequestVote() {
    return requestVote;
  }

  InstallSnapshotReplyProto getInstallSnapshot() {
    return installSnapshot;
  }

  StartLeaderElectionReplyProto getStartLeaderElection() {
    return startLeaderElection;
  }

  ReadCommittedEntriesReplyProto getReadCommittedEntries() {
    return readCommittedEntries;
  }

  @Override
  public boolean isRequest() {
    return false;
  }

  @Override
  public String getRequestorId() {
    if (isAppendEntries()) {
      return appendEntries.getServerReply().getRequestorId().toStringUtf8();
    } else if (isRequestVote()) {
      return requestVote.getServerReply().getRequestorId().toStringUtf8();
    } else if (isInstallSnapshot()) {
      return installSnapshot.getServerReply().getRequestorId().toStringUtf8();
    } else if (isReadCommittedEntries()) {
      return readCommittedEntries.getServerReply().getRequestorId().toStringUtf8();
    } else {
      return startLeaderElection.getServerReply().getRequestorId().toStringUtf8();
    }
  }

  @Override
  public String getReplierId() {
    if (isAppendEntries()) {
      return appendEntries.getServerReply().getReplyId().toStringUtf8();
    } else if (isRequestVote()) {
      return requestVote.getServerReply().getReplyId().toStringUtf8();
    } else if (isInstallSnapshot()) {
      return installSnapshot.getServerReply().getReplyId().toStringUtf8();
    } else if (isReadCommittedEntries()) {
      return readCommittedEntries.getServerReply().getReplyId().toStringUtf8();
    } else {
      return startLeaderElection.getServerReply().getReplyId().toStringUtf8();
    }
  }

  @Override
  public RaftGroupId getRaftGroupId() {
    if (isAppendEntries()) {
      return ProtoUtils.toRaftGroupId(appendEntries.getServerReply().getRaftGroupId());
    } else if (isRequestVote()) {
      return ProtoUtils.toRaftGroupId(requestVote.getServerReply().getRaftGroupId());
    } else if (isInstallSnapshot()) {
      return ProtoUtils.toRaftGroupId(installSnapshot.getServerReply().getRaftGroupId());
    } else if (isReadCommittedEntries()) {
      return ProtoUtils.toRaftGroupId(readCommittedEntries.getServerReply().getRaftGroupId());
    } else {
      return ProtoUtils.toRaftGroupId(startLeaderElection.getServerReply().getRaftGroupId());
    }
  }
}
