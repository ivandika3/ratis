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

import org.apache.ratis.protocol.RaftClientReply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Client observations from one JVM, ordered before issuing calls and exposing their completions. */
public final class ClientHistory {
  private static class Operation {
    private final long call;
    private final int client;
    private final String clientId;
    private final String function;
    private final String peer;
    private long returned;
    private String outcome = "unknown";
    private Long value;
    private Long callId;
    private String server;
    private String error;

    Operation(long call, int client, String clientId, String function, String peer) {
      this.call = call;
      this.client = client;
      this.clientId = clientId;
      this.function = function;
      this.peer = peer;
    }

    void append(StringBuilder json) {
      json.append("{\"id\":").append(call).append(",\"client\":").append(client)
          .append(",\"clientId\":").append(quote(clientId)).append(",\"function\":").append(quote(function))
          .append(",\"peer\":").append(quote(peer)).append(",\"call\":").append(call)
          .append(",\"return\":").append(returned).append(",\"outcome\":").append(quote(outcome));
      if (value != null) {
        json.append(",\"value\":").append(value);
      }
      if (callId != null) {
        json.append(",\"callId\":").append(callId).append(",\"server\":").append(quote(server));
      }
      if (error != null) {
        json.append(",\"error\":").append(quote(error));
      }
      json.append('}');
    }
  }

  private final List<Operation> operations = new ArrayList<>();
  private final List<String> faults = new ArrayList<>();
  private final Map<String, String> metadata = new TreeMap<>();
  private long index;

  public synchronized void putMetadata(String key, String value) {
    metadata.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
  }

  public synchronized void fault(String action, String detail) {
    faults.add("{\"index\":" + ++index + ",\"action\":" + quote(action) + ",\"detail\":" + quote(detail) + "}");
  }

  public CompletableFuture<Long> record(int worker, String clientId, String function, String peer,
      Supplier<CompletableFuture<RaftClientReply>> request) {
    if (worker < 0 || !("increment".equals(function) || "query".equals(function))) {
      throw new IllegalArgumentException("Invalid worker or counter operation: " + worker + "/" + function);
    }
    final Operation operation;
    synchronized (this) {
      operation = new Operation(++index, worker, Objects.requireNonNull(clientId, "clientId"), function,
          Objects.requireNonNull(peer, "peer"));
      operations.add(operation);
    }
    final CompletableFuture<Long> observed = new CompletableFuture<Long>() {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (operation) {
          if (operation.returned != 0) {
            return false;
          }
          ClientHistory.this.complete(operation, null, "unknown", null, new CancellationException("Caller cancelled"));
        }
        return super.cancel(mayInterruptIfRunning);
      }
    };
    final CompletableFuture<RaftClientReply> future;
    try {
      future = Objects.requireNonNull(request.get(), "request future");
    } catch (RuntimeException e) {
      complete(operation, null, "unknown", null, e);
      observed.completeExceptionally(e);
      return observed;
    }
    future.whenComplete((reply, failure) -> {
      Long value = null;
      Throwable error = failure;
      String outcome = "unknown";
      if (error == null && reply != null && reply.isSuccess()) {
        try {
          value = Long.parseLong(reply.getMessage().getContent().toString(StandardCharsets.UTF_8));
          outcome = "ok";
        } catch (RuntimeException e) {
          // A malformed successful reply is a harness/model error, never an uncertain write.
          outcome = "error";
          error = e;
        }
      } else if (error == null) {
        error = new IOException("Unsuccessful reply: " + reply);
      }
      synchronized (operation) {
        if (operation.returned != 0) {
          return;
        }
        complete(operation, reply, outcome, value, error);
      }
      // Workers can observe completion only after its event has been recorded.
      if (error == null) {
        observed.complete(value);
      } else {
        observed.completeExceptionally(error);
      }
    });
    return observed;
  }

  private synchronized void complete(Operation operation, RaftClientReply reply, String outcome, Long value,
      Throwable error) {
    operation.returned = ++index;
    operation.outcome = outcome;
    operation.value = value;
    operation.error = error == null? null : error.toString();
    if (reply != null) {
      operation.callId = reply.getCallId();
      operation.server = reply.getReplierId();
    }
  }

  public synchronized int countSuccessful(String function) {
    return (int)operations.stream().filter(op -> "ok".equals(op.outcome) && function.equals(op.function)).count();
  }

  /** Save a consistent prefix, retaining outstanding invocations as unknown with return index zero. */
  public Path write(Path directory) throws IOException {
    final String json;
    synchronized (this) {
      final StringBuilder b = new StringBuilder("{\"version\":1,\"model\":\"counter\",\"initial\":0,\"metadata\":{");
      boolean comma = false;
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        if (comma) {
          b.append(',');
        }
        b.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        comma = true;
      }
      b.append("},\"operations\":[");
      for (int i = 0; i < operations.size(); i++) {
        if (i > 0) {
          b.append(',');
        }
        operations.get(i).append(b);
      }
      b.append("],\"faults\":[").append(String.join(",", faults)).append("]}\n");
      json = b.toString();
    }
    Files.createDirectories(directory);
    return Files.write(directory.resolve("history.json"), json.getBytes(StandardCharsets.UTF_8));
  }

  private static String quote(String value) {
    final StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int)c));
          } else {
            b.append(c);
          }
      }
    }
    return b.append('"').toString();
  }
}
