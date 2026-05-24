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
package org.apache.ratis.netty;

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.thirdparty.io.netty.channel.EventLoopGroup;
import org.apache.ratis.thirdparty.io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.ratis.thirdparty.io.netty.channel.uring.IoUring;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestNettyUtils {
  @Test
  void testClientIoModeDefaultsToLegacyEpollConfig() {
    final RaftProperties properties = new RaftProperties();
    NettyConfigKeys.Client.setUseEpoll(properties, false);
    Assertions.assertEquals(NettyConfigKeys.IoMode.DEFAULT, NettyConfigKeys.Client.ioMode(properties));

    final EventLoopGroup group = NettyUtils.newEventLoopGroup("test-client", 1,
        NettyConfigKeys.Client.ioMode(properties), NettyConfigKeys.Client.useEpoll(properties));
    try {
      Assertions.assertEquals(NioSocketChannel.class, NettyUtils.getSocketChannelClass(group));
    } finally {
      group.shutdownGracefully();
    }
  }

  @Test
  void testClientIoModeParsesIoUring() {
    final RaftProperties properties = new RaftProperties();
    NettyConfigKeys.Client.setIoMode(properties, NettyConfigKeys.IoMode.IO_URING);
    Assertions.assertEquals(NettyConfigKeys.IoMode.IO_URING, NettyConfigKeys.Client.ioMode(properties));
  }

  @Test
  void testExplicitIoUringDoesNotFallback() {
    if (IoUring.isAvailable()) {
      final EventLoopGroup group = NettyUtils.newEventLoopGroup("test-io-uring", 1,
          NettyConfigKeys.IoMode.IO_URING, false);
      try {
        Assertions.assertEquals(NettyUtils.IO_URING_SOCKET_CHANNEL_CLASS, NettyUtils.getSocketChannelClass(group).getName());
      } finally {
        group.shutdownGracefully();
      }
    } else {
      Assertions.assertThrows(IllegalStateException.class,
          () -> NettyUtils.newEventLoopGroup("test-io-uring", 1, NettyConfigKeys.IoMode.IO_URING, false));
    }
  }
}
