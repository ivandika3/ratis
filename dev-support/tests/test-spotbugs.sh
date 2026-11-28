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

set -eu

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT
export MVN="${TEST_DIR}/mvn"

cat > "$MVN" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$MAVEN_OUTPUT"
exit "$MAVEN_EXIT_CODE"
EOF
chmod +x "$MVN"

check_version() (
  local expected=$1
  SPOTBUGS_VERSION=${2:-}
  _install_tool() { :; }
  source "${DIR}/../checks/install/spotbugs.sh"
  if [[ "$SPOTBUGS_VERSION" != "$expected" ]]; then
    printf 'Expected SpotBugs version %s, got %s\n' "$expected" "$SPOTBUGS_VERSION" >&2
    exit 1
  fi
)

export MAVEN_EXIT_CODE=1
export MAVEN_OUTPUT='[ERROR] Dependency resolution failed'
check_version 4.8.6

export MAVEN_EXIT_CODE=0
export MAVEN_OUTPUT=4.9.8
check_version 4.9.8

export MAVEN_EXIT_CODE=1
check_version 4.10.0 4.10.0

echo 'SpotBugs installer tests passed'
