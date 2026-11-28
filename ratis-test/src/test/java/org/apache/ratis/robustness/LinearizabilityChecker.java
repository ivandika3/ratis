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

import org.apache.ratis.thirdparty.com.google.gson.JsonObject;
import org.apache.ratis.thirdparty.com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Runs the offline checker once per history, retaining its output even when checking fails. */
public final class LinearizabilityChecker {
  public enum Verdict {
    VALID, INVALID, INCONCLUSIVE
  }

  private final Path executable;
  private final long timeoutMillis;

  public LinearizabilityChecker(Path executable, long timeoutMillis) {
    if (timeoutMillis <= 0 || timeoutMillis > TimeUnit.MINUTES.toMillis(10)) {
      throw new IllegalArgumentException("Checker timeout must be between 1 ms and 10 minutes");
    }
    this.executable = executable.toAbsolutePath();
    this.timeoutMillis = timeoutMillis;
  }

  public static LinearizabilityChecker fromSystemProperties() throws IOException {
    final String executable = System.getProperty("ratis.robustness.checker");
    if (executable == null || executable.isEmpty()) {
      throw new IOException("Set ratis.robustness.checker to the offline checker executable; see dev-support/linearizability-checker");
    }
    final LinearizabilityChecker checker = new LinearizabilityChecker(Paths.get(executable),
        Long.getLong("ratis.robustness.checker.timeout.ms", 10000));
    checker.requireExecutable();
    return checker;
  }

  private void requireExecutable() throws IOException {
    if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
      throw new IOException("Checker executable is missing or not executable: " + executable);
    }
  }

  public Verdict check(Path directory) throws IOException, InterruptedException {
    requireExecutable();
    final Path report = directory.toAbsolutePath();
    Files.createDirectories(report);
    // A failed/repeated invocation must not accidentally consume an earlier verdict.
    for (String file : new String[]{"verdict.txt", "result.json", "normalized.json", "history.html", "checker-timeout.txt"}) {
      Files.deleteIfExists(report.resolve(file));
    }
    final ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-history", report.resolve("history.json").toString(),
        "-output", report.toString(), "-timeout", timeoutMillis + "ms")
        .redirectOutput(report.resolve("checker-stdout.json").toFile())
        .redirectError(report.resolve("checker-stderr.log").toFile());
    builder.environment().putIfAbsent("GOMEMLIMIT", "256MiB");
    final Process process = builder.start();
    try {
      if (!process.waitFor(timeoutMillis + 10000, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          throw new IOException("Unable to stop checker process; see " + report);
        }
        Files.write(report.resolve("verdict.txt"), "inconclusive\n".getBytes(StandardCharsets.UTF_8));
        Files.write(report.resolve("checker-timeout.txt"),
            "Checker process exceeded its time budget and reporting allowance.\n".getBytes(StandardCharsets.UTF_8));
        return Verdict.INCONCLUSIVE;
      }
      final int exit = process.exitValue();
      if (exit < 0 || exit > 2) {
        throw new IOException("Checker failed with exit " + exit + "; see " + report.resolve("checker-stdout.json"));
      }
      final String expected = exit == 0? "valid" : exit == 1? "invalid" : "inconclusive";
      final Path verdictFile = report.resolve("verdict.txt");
      if (!Files.isRegularFile(verdictFile) || Files.size(verdictFile) > 64
          || !expected.equals(new String(Files.readAllBytes(verdictFile), StandardCharsets.UTF_8).trim())) {
        throw new IOException("Missing or inconsistent checker verdict for exit " + exit + " in " + report);
      }
      for (String file : new String[]{"result.json", "normalized.json", "history.html"}) {
        if (!Files.isRegularFile(report.resolve(file)) || Files.size(report.resolve(file)) == 0) {
          throw new IOException("Checker report is missing " + file + " in " + report);
        }
      }
      validateResult(report, expected);
      return exit == 0? Verdict.VALID : exit == 1? Verdict.INVALID : Verdict.INCONCLUSIVE;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static void validateResult(Path report, String expected) throws IOException {
    final Path path = report.resolve("result.json");
    if (Files.size(path) > 65536) {
      throw new IOException("Checker result exceeds 64 KiB: " + path);
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      final JsonObject result = JsonParser.parseReader(reader).getAsJsonObject();
      if (!result.get("version").getAsJsonPrimitive().isNumber()
          || result.get("version").getAsBigDecimal().intValueExact() != 1
          || !"porcupine/v1.3.1".equals(result.get("checker").getAsString())
          || !"counter/v1".equals(result.get("model").getAsString())
          || !expected.equals(result.get("result").getAsString()) || result.has("error")) {
        throw new IllegalArgumentException("Unexpected result version, checker, model or verdict");
      }
      final int count = result.get("operations").getAsBigDecimal().intValueExact();
      final int unknown = result.get("unknown").getAsBigDecimal().intValueExact();
      if (count <= 0 || count > 10000 || unknown < 0 || unknown > count
          || result.get("durationNanos").getAsBigDecimal().longValueExact() < 0) {
        throw new IllegalArgumentException("Invalid result operation count or duration");
      }
      final Path normalized = report.resolve("normalized.json");
      if (Files.size(normalized) > 64L * 1024 * 1024) {
        throw new IllegalArgumentException("Normalized result exceeds 64 MiB");
      }
      try (Reader operations = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
        if (JsonParser.parseReader(operations).getAsJsonArray().size() != count) {
          throw new IllegalArgumentException("Normalized operation count disagrees with result");
        }
      }
    } catch (RuntimeException e) {
      throw new IOException("Malformed checker result in " + path, e);
    }
  }

}
