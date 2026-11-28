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

import org.apache.ratis.BaseTest;
import org.apache.ratis.protocol.exceptions.AlreadyClosedException;
import org.apache.ratis.thirdparty.io.netty.buffer.Unpooled;
import org.apache.ratis.thirdparty.io.netty.channel.ChannelHandlerContext;
import org.apache.ratis.thirdparty.io.netty.channel.ChannelInitializer;
import org.apache.ratis.thirdparty.io.netty.channel.ChannelOutboundHandlerAdapter;
import org.apache.ratis.thirdparty.io.netty.channel.ChannelPromise;
import org.apache.ratis.thirdparty.io.netty.channel.EventLoopGroup;
import org.apache.ratis.thirdparty.io.netty.channel.socket.SocketChannel;
import org.apache.ratis.util.NettyUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class TestNettyClient extends BaseTest {
  @Test
  public void testConnectAndWrite() throws Exception {
    final EventLoopGroup group = NettyUtils.newEventLoopGroup("test-netty-client", 1, false);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
         NettyClient client = new NettyClient("localhost:" + server.getLocalPort())) {
      server.setSoTimeout(5000);
      client.connect(group, new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) {
        }
      });

      final byte[] message = {1, 2, 3, 4};
      client.writeAndFlush(Unpooled.wrappedBuffer(message)).sync();
      try (Socket accepted = server.accept()) {
        accepted.setSoTimeout(5000);
        final byte[] received = new byte[message.length];
        new DataInputStream(accepted.getInputStream()).readFully(received);
        assertArrayEquals(message, received);
      }
    } finally {
      group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
    }
  }

  @Test
  public void testCloseBeforeConnect() {
    final NettyClient client = new NettyClient("localhost:1");
    client.close();
    client.close();
    assertThrows(AlreadyClosedException.class, () -> client.writeAndFlush(new Object()));
  }

  @Test
  public void testCloseDuringConnect() throws Exception {
    final EventLoopGroup group = NettyUtils.newEventLoopGroup("test-netty-client", 1, false);
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CompletableFuture<SocketChannel> channel = new CompletableFuture<>();
    final CompletableFuture<Runnable> pendingConnect = new CompletableFuture<>();
    Runnable resume = null;
    boolean resumed = false;
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
         NettyClient client = new NettyClient("localhost:" + server.getLocalPort())) {
      final Future<?> connecting = executor.submit(() -> {
        client.connect(group, new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            channel.complete(ch);
            ch.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
              @Override
              public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                  SocketAddress localAddress, ChannelPromise promise) {
                // Leave the connect promise pending without blocking the event loop.
                pendingConnect.complete(() -> ctx.connect(remoteAddress, localAddress, promise));
              }
            });
          }
        });
        return null;
      });

      resume = pendingConnect.get(5, TimeUnit.SECONDS);
      final SocketChannel ch = channel.get(5, TimeUnit.SECONDS);
      // Match NettyRpcProxy's inbound exception callback, which closes on the event loop.
      final Throwable closeFailure = getFailure(ch.eventLoop().submit(client::close));
      ch.eventLoop().execute(resume);
      resumed = true;
      final Throwable connectFailure = getFailure(connecting);

      assertAll(
          () -> assertNull(closeFailure, "Closing while connecting must not fail"),
          () -> assertTrue(connectFailure == null || connectFailure instanceof IOException,
              "Connect must not make an invalid lifecycle transition: " + connectFailure),
          () -> assertTrue(ch.closeFuture().await(5, TimeUnit.SECONDS), "The connecting channel must be closed"),
          () -> assertThrows(AlreadyClosedException.class, () -> client.writeAndFlush(new Object())));
    } finally {
      if (resume != null && !resumed) {
        channel.getNow(null).eventLoop().execute(resume);
      }
      final SocketChannel ch = channel.getNow(null);
      if (ch != null) {
        ch.close().await(5, TimeUnit.SECONDS);
      }
      executor.shutdownNow();
      group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  private static Throwable getFailure(Future<?> future) throws Exception {
    try {
      future.get(5, TimeUnit.SECONDS);
      return null;
    } catch (ExecutionException e) {
      return e.getCause();
    }
  }
}
