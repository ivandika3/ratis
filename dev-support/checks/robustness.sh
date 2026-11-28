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

set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
cd "$ROOT"
source "$DIR/../find_maven.sh"

REPORT_DIR="${OUTPUT_DIR:-$ROOT/target/robustness}"
mkdir -p "$REPORT_DIR"
REPORT_DIR="$(cd "$REPORT_DIR" && pwd)"
trap 'rc=$?; if [[ $rc -ne 0 ]]; then printf "Robustness checks failed; see output.log, go-output.log and histories.\n" > "$REPORT_DIR/summary.txt"; fi' EXIT
rm -f "$REPORT_DIR/summary.txt"

(
  cd "$ROOT/dev-support/linearizability-checker"
  go version
  go test -race ./...
  go build -trimpath -o "$REPORT_DIR/linearizability-checker" .
  porcupine_dir="$(go list -m -f '{{.Dir}}' github.com/anishathalye/porcupine)"
  cp -f "$porcupine_dir/LICENSE.md" "$REPORT_DIR/PORCUPINE-LICENSE.txt"
  go_root="$(go env GOROOT)"
  cp -f "$go_root/LICENSE" "$REPORT_DIR/GO-LICENSE.txt"
) 2>&1 | tee "$REPORT_DIR/go-output.log"
cp -f "$ROOT/LICENSE" "$ROOT/NOTICE" "$REPORT_DIR/"

revision="$(git rev-parse HEAD 2>/dev/null || true)"
dirty=unknown
if [[ -n "$revision" ]]; then
  dirty=false
  if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
    dirty=true
  fi
fi
set +e
"$MVN" -B --fail-at-end -pl ratis-test -am -Probustness-tests \
  -Dratis.robustness.checker="$REPORT_DIR/linearizability-checker" \
  -Dratis.robustness.reports="$REPORT_DIR/histories" \
  -Dratis.robustness.revision="${revision:-unknown}" -Dratis.robustness.dirty="$dirty" "$@" test 2>&1 | tee "$REPORT_DIR/output.log"
rc=${PIPESTATUS[0]}
set -e

mkdir -p "$REPORT_DIR/surefire-reports"
for report in "$ROOT"/ratis-test/target/surefire-reports/*robustness* "$ROOT"/ratis-test/target/surefire-reports/*TestRobustnessWithGrpc*; do
  if [[ -f "$report" ]]; then
    cp "$report" "$REPORT_DIR/surefire-reports/"
  fi
done
exit "$rc"
