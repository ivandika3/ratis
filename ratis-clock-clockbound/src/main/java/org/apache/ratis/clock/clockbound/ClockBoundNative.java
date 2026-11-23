/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.clock.clockbound;

import com.sun.jna.FunctionMapper;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.apache.ratis.clock.ClockReading;
import org.apache.ratis.clock.TimeBounds;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** ClockBound 3.x C ABI. No native library is loaded during provider discovery. */
interface ClockBoundNative extends Library {
  static ClockBoundNative load(String path) {
    if (Native.LONG_SIZE != 8 || Native.POINTER_SIZE != 8) {
      throw new IllegalArgumentException("ClockBound requires a 64-bit LP64 native ABI");
    }
    final FunctionMapper mapper = (library, method) -> {
      switch (method.getName()) {
        case "openWith":
          return "clockbound_open_with";
        case "now":
          return "clockbound_now";
        case "close":
          return "clockbound_close";
        default:
          throw new IllegalArgumentException("Unknown ClockBound function: " + method.getName());
      }
    };
    final Map<String, Object> options = new HashMap<>();
    options.put(Library.OPTION_FUNCTION_MAPPER, mapper);
    options.put(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name());
    final NativeLibrary library = NativeLibrary.getInstance(path, options);
    // open_with is absent in 2.x, whose identically named now/close functions have incompatible signatures.
    for (String symbol : new String[]{"clockbound_open_with", "clockbound_now", "clockbound_close"}) {
      library.getFunction(symbol);
    }
    return Native.load(path, ClockBoundNative.class, options);
  }

  Pointer openWith(String sharedMemoryPath, String vmClockPath, Error error);

  Pointer now(Pointer context, Result result, Error error);

  Pointer close(Pointer context, Error error);

  @Structure.FieldOrder({"seconds", "nanos"})
  @SuppressWarnings("checkstyle:VisibilityModifier") // JNA requires public native structure fields.
  class Timespec extends Structure {
    public NativeLong seconds = new NativeLong();
    public NativeLong nanos = new NativeLong();

    Instant toInstant() {
      final long nano = nanos.longValue();
      if (nano < 0 || nano >= 1_000_000_000L) {
        throw new IllegalArgumentException("ClockBound returned invalid nanoseconds: " + nano);
      }
      return Instant.ofEpochSecond(seconds.longValue(), nano);
    }
  }

  @Structure.FieldOrder({"earliest", "latest", "clockStatus"})
  @SuppressWarnings("checkstyle:VisibilityModifier") // JNA requires public native structure fields.
  class Result extends Structure {
    public Timespec earliest = new Timespec();
    public Timespec latest = new Timespec();
    public int clockStatus;

    ClockReading toReading(String reference) {
      switch (clockStatus) {
        case 0:
          return ClockReading.unavailable(reference, ClockReading.UnavailableReason.UNKNOWN,
              "ClockBound cannot provide a trustworthy bound");
        case 3:
          return ClockReading.unavailable(reference, ClockReading.UnavailableReason.DISRUPTED,
              "ClockBound reports a clock disruption");
        case 1:
        case 2:
          try {
            return ClockReading.available(reference, TimeBounds.valueOf(earliest.toInstant(), latest.toInstant()));
          } catch (RuntimeException e) {
            return ClockReading.unavailable(reference, ClockReading.UnavailableReason.PROVIDER_ERROR,
                "Invalid ClockBound interval: " + e.getMessage());
          }
        default:
          return ClockReading.unavailable(reference, ClockReading.UnavailableReason.PROVIDER_ERROR,
              "Unrecognized ClockBound status: " + clockStatus);
      }
    }
  }

  @Structure.FieldOrder({"kind", "errorNumber", "detail"})
  @SuppressWarnings("checkstyle:VisibilityModifier") // JNA requires public native structure fields.
  class Error extends Structure {
    public int kind;
    public int errorNumber;
    public byte[] detail = new byte[128];

    String describe() {
      int length = 0;
      while (length < detail.length && detail[length] != 0) {
        length++;
      }
      return "kind=" + kind + ", errno=" + errorNumber + ": "
          + new String(detail, 0, length, StandardCharsets.UTF_8);
    }
  }
}
