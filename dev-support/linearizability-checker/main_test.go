// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//	http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/anishathalye/porcupine"
)

func value(n int64) *int64 { return &n }

func operation(id int64, function string, call, ret int64, outcome string, result *int64) Operation {
	return Operation{ID: id, Client: int(id), Function: function, Call: call, Return: ret, Outcome: outcome, Value: result}
}

func TestCounterHistories(t *testing.T) {
	tests := []struct {
		name       string
		operations []Operation
		want       porcupine.CheckResult
	}{
		{"sequential", []Operation{
			operation(1, "increment", 1, 2, "ok", value(1)),
			operation(2, "query", 3, 4, "ok", value(1)),
		}, porcupine.Ok},
		{"stale read", []Operation{
			operation(1, "increment", 1, 2, "ok", value(1)),
			operation(2, "query", 3, 4, "ok", value(0)),
		}, porcupine.Illegal},
		{"overlapping old read", []Operation{
			operation(1, "increment", 1, 4, "ok", value(1)),
			operation(2, "query", 2, 3, "ok", value(0)),
		}, porcupine.Ok},
		{"contradictory reads", []Operation{
			operation(1, "increment", 1, 6, "ok", value(1)),
			operation(2, "query", 2, 3, "ok", value(1)),
			operation(3, "query", 4, 5, "ok", value(0)),
		}, porcupine.Illegal},
		{"unknown write takes effect after timeout", []Operation{
			operation(1, "increment", 1, 2, "unknown", nil),
			operation(2, "query", 3, 4, "ok", value(0)),
			operation(3, "query", 5, 6, "ok", value(1)),
		}, porcupine.Ok},
		{"unknown write absent", []Operation{
			operation(1, "increment", 1, 2, "unknown", nil),
			operation(2, "query", 3, 4, "ok", value(0)),
		}, porcupine.Ok},
		{"unknown cannot execute twice", []Operation{
			operation(1, "increment", 1, 2, "unknown", nil),
			operation(2, "query", 3, 4, "ok", value(2)),
		}, porcupine.Illegal},
		{"pending write", []Operation{
			operation(1, "increment", 1, 0, "unknown", nil),
			operation(2, "query", 2, 3, "ok", value(1)),
		}, porcupine.Ok},
		{"definite failure has no effect", []Operation{
			operation(1, "increment", 1, 2, "fail", nil),
			operation(2, "query", 3, 4, "ok", value(1)),
		}, porcupine.Illegal},
		{"duplicate successful increments", []Operation{
			operation(1, "increment", 1, 4, "ok", value(1)),
			operation(2, "increment", 2, 3, "ok", value(1)),
		}, porcupine.Illegal},
		{"unknown read does not change state", []Operation{
			operation(1, "query", 1, 2, "unknown", nil),
			operation(2, "query", 3, 4, "ok", value(1)),
		}, porcupine.Illegal},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			h := History{Version: 1, Model: "counter", Initial: value(0), Operations: tt.operations}
			operations, err := h.normalize()
			if err != nil {
				t.Fatal(err)
			}
			got, _ := porcupine.CheckOperationsVerbose(counterModel(*h.Initial), operations, time.Second)
			if got != tt.want {
				t.Fatalf("got %v, want %v", got, tt.want)
			}
		})
	}
}

func TestInvalidHistories(t *testing.T) {
	tests := []string{
		`{"version":1,"model":"counter","initial":5,"operations":[{"id":1,"function":"query","call":1,"return":2,"outcome":"ok","value":5}]}`,
		`{}`,
		`{"version":2,"model":"counter","initial":0,"operations":[]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"query","call":1,"return":2,"outcome":"ok"}]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"query","call":2,"return":1,"outcome":"ok","value":0}]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"query","call":1,"outcome":"ok","value":0}]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"typo","call":1,"return":2,"outcome":"ok","value":0}]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"query","call":1,"return":2,"outcome":"typo"}]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"query","call":1,"return":2,"outcome":"unknown","value":0}]}`,
		`{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"query","call":1,"return":2,"outcome":"ok","value":0},{"id":1,"function":"query","call":3,"return":4,"outcome":"ok","value":0}]}`,
	}
	for _, input := range tests {
		t.Run(input, func(t *testing.T) {
			var h History
			if err := json.Unmarshal([]byte(input), &h); err != nil {
				t.Fatal(err)
			}
			if _, err := h.normalize(); err == nil {
				t.Fatal("accepted malformed history")
			}
		})
	}
}

func TestCommandReportsAndReplay(t *testing.T) {
	dir := t.TempDir()
	history := filepath.Join(dir, "input.json")
	input := `{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"increment","call":1,"return":2,"outcome":"ok","value":1},{"id":2,"function":"query","call":3,"return":4,"outcome":"ok","value":0}]}`
	if err := os.WriteFile(history, []byte(input), 0600); err != nil {
		t.Fatal(err)
	}
	output := filepath.Join(dir, "report")
	var stdout bytes.Buffer
	if code := run([]string{"-history", history, "-output", output}, &stdout); code != 1 {
		t.Fatalf("exit %d: %s", code, stdout.String())
	}
	for _, file := range []string{"history.json", "normalized.json", "result.json", "history.html"} {
		data, err := os.ReadFile(filepath.Join(output, file))
		if err != nil || len(data) == 0 {
			t.Fatalf("missing %s: %v", file, err)
		}
	}
	var result Result
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Result != "invalid" || result.Operations != 2 {
		t.Fatalf("%+v", result)
	}
	stdout.Reset()
	if code := run([]string{"-history", filepath.Join(output, "history.json"), "-output", filepath.Join(dir, "replay")}, &stdout); code != 1 {
		t.Fatalf("replay exit %d: %s", code, stdout.String())
	}
}

func TestCommandRejectsMalformedInput(t *testing.T) {
	for _, input := range []string{`{}`, `{} {}`, `{"version":1,"unexpected":true}`, `{"version":1.0}`} {
		dir := t.TempDir()
		path := filepath.Join(dir, "input.json")
		if err := os.WriteFile(path, []byte(input), 0600); err != nil {
			t.Fatal(err)
		}
		var stdout bytes.Buffer
		if code := run([]string{"-history", path, "-output", filepath.Join(dir, "report")}, &stdout); code != 3 {
			t.Fatalf("accepted %q: exit %d, %s", input, code, stdout.String())
		}
	}
}

func TestCheckerTimeoutIsInconclusive(t *testing.T) {
	h := History{Version: 1, Model: "counter", Initial: value(0)}
	for i := int64(1); i <= 2000; i++ {
		h.Operations = append(h.Operations, operation(i, "increment", i*2-1, i*2, "ok", value(i)))
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "input.json")
	data, _ := json.Marshal(h)
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	var stdout bytes.Buffer
	if code := run([]string{"-history", path, "-output", filepath.Join(dir, "report"), "-timeout", "1ns"}, &stdout); code != 2 {
		t.Fatalf("exit %d: %s", code, stdout.String())
	}
	var result Result
	if err := json.Unmarshal(stdout.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result.Result != "inconclusive" {
		t.Fatalf("%+v", result)
	}
}

func TestReportFailureCannotLeaveValidResult(t *testing.T) {
	dir := t.TempDir()
	input := filepath.Join(dir, "input.json")
	data := `{"version":1,"model":"counter","initial":0,"operations":[{"id":1,"function":"query","call":1,"return":2,"outcome":"ok","value":0}]}`
	if err := os.WriteFile(input, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	output := filepath.Join(dir, "report")
	if err := os.MkdirAll(filepath.Join(output, "verdict.txt"), 0700); err != nil {
		t.Fatal(err)
	}
	var stdout bytes.Buffer
	if code := run([]string{"-history", input, "-output", output}, &stdout); code != 3 {
		t.Fatalf("exit %d: %s", code, stdout.String())
	}
	saved, err := os.ReadFile(filepath.Join(output, "result.json"))
	if err != nil {
		t.Fatal(err)
	}
	var result Result
	if err := json.Unmarshal(saved, &result); err != nil {
		t.Fatal(err)
	}
	if result.Result != "error" {
		t.Fatalf("saved a misleading verdict: %+v", result)
	}
}

func TestVisualizationShowsCallIDValue(t *testing.T) {
	id := int64(42)
	description := counterModel(0).DescribeOperationMetadata(Operation{CallID: &id})
	if !strings.Contains(description, "callId=42") {
		t.Fatalf("%s", description)
	}
}
