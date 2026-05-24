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

import org.apache.ratis.security.TlsConf;
import org.apache.ratis.security.TlsConf.CertificatesConf;
import org.apache.ratis.security.TlsConf.KeyManagerConf;
import org.apache.ratis.security.TlsConf.PrivateKeyConf;
import org.apache.ratis.security.TlsConf.TrustManagerConf;
import org.apache.ratis.thirdparty.io.netty.channel.Channel;
import org.apache.ratis.thirdparty.io.netty.channel.ChannelFuture;
import org.apache.ratis.thirdparty.io.netty.channel.EventLoopGroup;
import org.apache.ratis.thirdparty.io.netty.channel.IoHandlerFactory;
import org.apache.ratis.thirdparty.io.netty.channel.MultiThreadIoEventLoopGroup;
import org.apache.ratis.thirdparty.io.netty.channel.ServerChannel;
import org.apache.ratis.thirdparty.io.netty.channel.epoll.Epoll;
import org.apache.ratis.thirdparty.io.netty.channel.epoll.EpollIoHandler;
import org.apache.ratis.thirdparty.io.netty.channel.epoll.EpollServerSocketChannel;
import org.apache.ratis.thirdparty.io.netty.channel.epoll.EpollSocketChannel;
import org.apache.ratis.thirdparty.io.netty.channel.nio.NioIoHandler;
import org.apache.ratis.thirdparty.io.netty.channel.socket.SocketChannel;
import org.apache.ratis.thirdparty.io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.ratis.thirdparty.io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.ratis.thirdparty.io.netty.channel.uring.IoUring;
import org.apache.ratis.thirdparty.io.netty.handler.ssl.SslContext;
import org.apache.ratis.thirdparty.io.netty.handler.ssl.SslContextBuilder;
import org.apache.ratis.util.ConcurrentUtils;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public interface NettyUtils {
  Logger LOG = LoggerFactory.getLogger(NettyUtils.class);
  TimeDuration CLOSE_TIMEOUT = TimeDuration.valueOf(5, TimeUnit.SECONDS);
  String EPOLL_EVENT_LOOP_GROUP_CLASS = "org.apache.ratis.thirdparty.io.netty.channel.epoll.EpollEventLoopGroup";
  String IO_URING_IO_HANDLER_CLASS = "org.apache.ratis.thirdparty.io.netty.channel.uring.IoUringIoHandler";
  String IO_URING_SOCKET_CHANNEL_CLASS = "org.apache.ratis.thirdparty.io.netty.channel.uring.IoUringSocketChannel";
  String IO_URING_SERVER_SOCKET_CHANNEL_CLASS =
      "org.apache.ratis.thirdparty.io.netty.channel.uring.IoUringServerSocketChannel";

  class Print {
    private static final AtomicBoolean PRINTED_EPOLL_UNAVAILABILITY_CAUSE = new AtomicBoolean();

    private Print() {}

    static void epollUnavailability(String message) {
      if (!LOG.isWarnEnabled()) {
        return;
      }
      if (PRINTED_EPOLL_UNAVAILABILITY_CAUSE.compareAndSet(false, true)) {
        LOG.warn(message, new IllegalStateException("Epoll is unavailable.", Epoll.unavailabilityCause()));
      } else {
        LOG.warn(message);
      }
    }
  }

  static EventLoopGroup newEventLoopGroup(String name, int size, boolean useEpoll) {
    return newEventLoopGroup(name, size, NettyConfigKeys.IoMode.DEFAULT, useEpoll);
  }

  static EventLoopGroup newEventLoopGroup(String name, int size, NettyConfigKeys.IoMode ioMode, boolean useEpoll) {
    final NettyConfigKeys.IoMode resolved = resolveIoMode(Objects.requireNonNull(ioMode, "ioMode == null"), useEpoll);
    switch (resolved) {
      case NIO:
        return newEventLoopGroup(name, size, "NIO", NioIoHandler.newFactory(),
            NioSocketChannel.class, NioServerSocketChannel.class);
      case EPOLL:
        if (Epoll.isAvailable()) {
          return newEventLoopGroup(name, size, "EPOLL", EpollIoHandler.newFactory(),
              EpollSocketChannel.class, EpollServerSocketChannel.class);
        } else if (ioMode == NettyConfigKeys.IoMode.DEFAULT) {
          Print.epollUnavailability("Failed to create EPOLL event loop group for " + name
              + "; fall back on NIO event loop group.");
          return newEventLoopGroup(name, size, "NIO", NioIoHandler.newFactory(),
              NioSocketChannel.class, NioServerSocketChannel.class);
        }
        throw unavailable("EPOLL", name, Epoll.unavailabilityCause());
      case IO_URING:
        if (IoUring.isAvailable()) {
          return newEventLoopGroup(name, size, "IO_URING", newIoUringIoHandlerFactory(),
              loadIoUringClass(IO_URING_SOCKET_CHANNEL_CLASS, SocketChannel.class),
              loadIoUringClass(IO_URING_SERVER_SOCKET_CHANNEL_CLASS, ServerChannel.class));
        }
        throw unavailable("IO_URING", name, IoUring.unavailabilityCause());
      case DEFAULT:
      default:
        throw new IllegalStateException("Unexpected resolved ioMode: " + resolved);
    }
  }

  static IoHandlerFactory newIoUringIoHandlerFactory() {
    try {
      return (IoHandlerFactory) Class.forName(IO_URING_IO_HANDLER_CLASS).getMethod("newFactory").invoke(null);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("Failed to create IO_URING IoHandlerFactory.", e.getCause());
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalStateException("Failed to load IO_URING IoHandlerFactory.", e);
    }
  }

  @SuppressWarnings("unchecked")
  static <T> Class<? extends T> loadIoUringClass(String className, Class<T> expectedType) {
    try {
      final Class<?> clazz = Class.forName(className);
      if (!expectedType.isAssignableFrom(clazz)) {
        throw new IllegalStateException(className + " is not a " + expectedType.getName());
      }
      return (Class<? extends T>) clazz;
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalStateException("Failed to load " + className + ".", e);
    }
  }

  static NettyConfigKeys.IoMode resolveIoMode(NettyConfigKeys.IoMode ioMode, boolean useEpoll) {
    return ioMode != NettyConfigKeys.IoMode.DEFAULT ? ioMode : useEpoll ? NettyConfigKeys.IoMode.EPOLL
        : NettyConfigKeys.IoMode.NIO;
  }

  static EventLoopGroup newEventLoopGroup(String name, int size, String transportName,
      IoHandlerFactory ioHandlerFactory, Class<? extends SocketChannel> socketChannelClass,
      Class<? extends ServerChannel> serverChannelClass) {
    LOG.info("Create {} event loop group for {}; Thread size is {}.", transportName, name, size);
    return new TransportEventLoopGroup(size, ConcurrentUtils.newThreadFactory(name + "-"), ioHandlerFactory,
        socketChannelClass, serverChannelClass);
  }

  static IllegalStateException unavailable(String transportName, String name, Throwable cause) {
    return new IllegalStateException("Failed to create " + transportName + " event loop group for " + name
        + " because " + transportName + " is unavailable.", cause);
  }

  class TransportEventLoopGroup extends MultiThreadIoEventLoopGroup {
    private final Class<? extends SocketChannel> socketChannelClass;
    private final Class<? extends ServerChannel> serverChannelClass;

    TransportEventLoopGroup(int size, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory,
        Class<? extends SocketChannel> socketChannelClass, Class<? extends ServerChannel> serverChannelClass) {
      super(size, threadFactory, ioHandlerFactory);
      this.socketChannelClass = socketChannelClass;
      this.serverChannelClass = serverChannelClass;
    }

    Class<? extends SocketChannel> getSocketChannelClass() {
      return socketChannelClass;
    }

    Class<? extends ServerChannel> getServerChannelClass() {
      return serverChannelClass;
    }
  }

  static void setTrustManager(SslContextBuilder b, TrustManagerConf trustManagerConfig) {
    if (trustManagerConfig == null) {
      return;
    }
    final TrustManager trustManager = trustManagerConfig.getTrustManager();
    if (trustManager != null) {
      b.trustManager(trustManager);
      return;
    }
    final CertificatesConf certificates = trustManagerConfig.getTrustCertificates();
    if (certificates.isFileBased()) {
      b.trustManager(certificates.getFile());
    } else {
      b.trustManager(certificates.get());
    }
  }

  static void setKeyManager(SslContextBuilder b, KeyManagerConf keyManagerConfig) {
    if (keyManagerConfig == null) {
      return;
    }
    final KeyManager keyManager = keyManagerConfig.getKeyManager();
    if (keyManager != null) {
      b.keyManager(keyManager);
      return;
    }
    final PrivateKeyConf privateKey = keyManagerConfig.getPrivateKey();
    final CertificatesConf certificates = keyManagerConfig.getKeyCertificates();

    if (keyManagerConfig.isFileBased()) {
      b.keyManager(certificates.getFile(), privateKey.getFile());
    } else {
      b.keyManager(privateKey.get(), certificates.get());
    }
  }

  static SslContextBuilder initSslContextBuilderForServer(KeyManagerConf keyManagerConfig) {
    final KeyManager keyManager = keyManagerConfig.getKeyManager();
    if (keyManager != null) {
      return SslContextBuilder.forServer(keyManager);
    }
    final PrivateKeyConf privateKey = keyManagerConfig.getPrivateKey();
    final CertificatesConf certificates = keyManagerConfig.getKeyCertificates();

    if (keyManagerConfig.isFileBased()) {
      return SslContextBuilder.forServer(certificates.getFile(), privateKey.getFile());
    } else {
      return SslContextBuilder.forServer(privateKey.get(), certificates.get());
    }
  }

  static SslContextBuilder initSslContextBuilderForServer(TlsConf tlsConf) {
    final SslContextBuilder b = initSslContextBuilderForServer(tlsConf.getKeyManager());
    if (tlsConf.isMutualTls()) {
      setTrustManager(b, tlsConf.getTrustManager());
    }
    return b;
  }

  static SslContext buildSslContextForServer(TlsConf tlsConf) {
    return buildSslContext("server", tlsConf, NettyUtils::initSslContextBuilderForServer);
  }

  static SslContextBuilder initSslContextBuilderForClient(TlsConf tlsConf) {
    final SslContextBuilder b = SslContextBuilder.forClient();
    setTrustManager(b, tlsConf.getTrustManager());
    if (tlsConf.isMutualTls()) {
      setKeyManager(b, tlsConf.getKeyManager());
    }
    return b;
  }

  static SslContext buildSslContextForClient(TlsConf tlsConf) {
    return buildSslContext("client", tlsConf, NettyUtils::initSslContextBuilderForClient);
  }

  static SslContext buildSslContext(String name, TlsConf tlsConf, Function<TlsConf, SslContextBuilder> builder) {
    if (tlsConf == null) {
      return null;
    }
    final SslContext sslContext;
    try {
      sslContext = builder.apply(tlsConf).build();
    } catch (Exception e) {
      final String message = "Failed to buildSslContext for " + name + " from " + tlsConf;
      throw new IllegalArgumentException(message, e);
    }
    LOG.debug("buildSslContext for {} from {} returns {}", name, tlsConf, sslContext.getClass().getName());
    return sslContext;
  }

  static Class<? extends SocketChannel> getSocketChannelClass(EventLoopGroup eventLoopGroup) {
    if (eventLoopGroup instanceof TransportEventLoopGroup) {
      return ((TransportEventLoopGroup) eventLoopGroup).getSocketChannelClass();
    }
    return isEpollEventLoopGroup(eventLoopGroup) ? EpollSocketChannel.class : NioSocketChannel.class;
  }

  static Class<? extends ServerChannel> getServerChannelClass(EventLoopGroup eventLoopGroup) {
    if (eventLoopGroup instanceof TransportEventLoopGroup) {
      return ((TransportEventLoopGroup) eventLoopGroup).getServerChannelClass();
    }
    return isEpollEventLoopGroup(eventLoopGroup) ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
  }

  static boolean isEpollEventLoopGroup(EventLoopGroup eventLoopGroup) {
    return EPOLL_EVENT_LOOP_GROUP_CLASS.equals(eventLoopGroup.getClass().getName());
  }

  static void closeChannel(Channel channel, String name) {
    final ChannelFuture f = channel.close();
    final boolean completed;
    try {
      completed = f.await(CLOSE_TIMEOUT.getDuration(), CLOSE_TIMEOUT.getUnit());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.info("Interrupted closeChannel {} ", name, e);
      return;
    }
    if (!completed) {
      LOG.warn("closeChannel {} is not yet completed in {}", name, CLOSE_TIMEOUT);
    }
  }
}
