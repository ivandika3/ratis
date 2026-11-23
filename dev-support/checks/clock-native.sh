#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Requires a Linux x86_64/aarch64 host, C compiler, rustup, git and Maven.
set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
cd "$DIR/../.."
source "$DIR/../find_maven.sh"

[[ "$(uname -s)" == Linux ]] || { echo "ClockBound client qualification requires Linux" >&2; exit 1; }
case "$(uname -m)" in
  x86_64|aarch64) ;;
  *) echo "Unsupported ClockBound test architecture" >&2; exit 1 ;;
esac
for command in cc rustup git; do
  command -v "$command" >/dev/null || { echo "Missing prerequisite: $command" >&2; exit 1; }
done

# ScyllaDB's ClockBound 3.0.0-rc.0 client pin; never test a moving branch.
COMMIT=371058f8f6a1786d4a4ab3eed9f5f2f16eea7777
TOOLCHAIN=1.93.1
REPORT_DIR="${OUTPUT_DIR:-$PWD/target/clock-native}"
mkdir -p "$REPORT_DIR"
REPORT_DIR="$(cd "$REPORT_DIR" && pwd)"
WORK="$(mktemp -d "$PWD/target/clock-native-build.XXXXXX")"
trap 'for module in ratis-clock ratis-clock-clockbound; do mkdir -p "$REPORT_DIR/$module"; cp -R "$module/target/surefire-reports" "$REPORT_DIR/$module/" 2>/dev/null || true; done' EXIT

rustup toolchain install "$TOOLCHAIN" --profile minimal
git init -q "$WORK/upstream"
git -C "$WORK/upstream" remote add origin https://github.com/aws/clock-bound.git
git -C "$WORK/upstream" fetch -q --depth=1 origin "$COMMIT"
git -C "$WORK/upstream" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$WORK/upstream" rev-parse HEAD)" == "$COMMIT" ]]
cargo "+$TOOLCHAIN" build --locked --release --manifest-path "$WORK/upstream/Cargo.toml" \
  --target-dir "$WORK/cargo-target" -p clock-bound-ffi \
  2>&1 | tee "$REPORT_DIR/native-build.log"

cc -std=c11 -Wall -Wextra -Werror -fPIC -shared -pthread -DRATIS_HAVE_CLOCKBOUND_HEADER \
  -I"$WORK/upstream/clock-bound-ffi/include" \
  ratis-clock-clockbound/src/test/native/clockbound_fixture.c -o "$WORK/libclockbound_fixture.so"
cc -std=c11 -Wall -Wextra -Werror -fPIC -shared -pthread -DRATIS_LEGACY_API \
  ratis-clock-clockbound/src/test/native/clockbound_fixture.c -o "$WORK/libclockbound_legacy.so"

# Run Java tests too; selecting the native profile alone intentionally selects only native-tagged tests.
"${MVN}" -B -pl ratis-clock-clockbound -am -Djacoco.skip test "$@" \
  2>&1 | tee "$REPORT_DIR/java-tests.log"
"${MVN}" -B -pl ratis-clock-clockbound -am -Pclock-native-tests -Djacoco.skip \
  "-Dclockbound.fixture.library=$WORK/libclockbound_fixture.so" \
  "-Dclockbound.legacy.library=$WORK/libclockbound_legacy.so" \
  "-Dclockbound.native.library=$WORK/cargo-target/release/libclockbound.so" test "$@" \
  2>&1 | tee "$REPORT_DIR/native-tests.log"

