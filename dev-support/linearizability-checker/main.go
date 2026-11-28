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
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/anishathalye/porcupine"
)

// Exit codes: 0 valid, 1 invalid, 2 inconclusive, 3 input/tool/report error.
func main() { os.Exit(run(os.Args[1:], os.Stdout)) }

type Result struct {
	Version       int    `json:"version"`
	Checker       string `json:"checker"`
	Model         string `json:"model"`
	Result        string `json:"result"`
	Operations    int    `json:"operations"`
	Unknown       int    `json:"unknown"`
	DurationNanos int64  `json:"durationNanos"`
	Error         string `json:"error,omitempty"`
}

func run(args []string, stdout io.Writer) int {
	result := Result{Version: 1, Checker: "porcupine/v1.3.1", Model: "counter/v1", Result: "error"}
	flags := flag.NewFlagSet("linearizability-checker", flag.ContinueOnError)
	var usage bytes.Buffer
	flags.SetOutput(&usage)
	history := flags.String("history", "", "version 1 Ratis counter history JSON")
	output := flags.String("output", "", "report directory (required)")
	timeout := flags.Duration("timeout", 10*time.Second, "positive checker time budget")
	emit := func(code int, err error) int {
		fail := func(err error) {
			if err != nil {
				result.Error = err.Error()
				result.Result = "error"
				code = 3
			}
		}
		fail(err)
		if *output != "" {
			if err := os.MkdirAll(*output, 0755); err != nil {
				fail(err)
			} else {
				// Publish the verdict first so a failure is included in the saved JSON.
				fail(os.WriteFile(filepath.Join(*output, "verdict.txt"), []byte(result.Result+"\n"), 0644))
				data, _ := json.MarshalIndent(result, "", "  ")
				fail(os.WriteFile(filepath.Join(*output, "result.json"), append(data, '\n'), 0644))
				if result.Result == "error" {
					// Best effort: report failure must not leave a valid handshake behind.
					_ = os.WriteFile(filepath.Join(*output, "verdict.txt"), []byte("error\n"), 0644)
				}
			}
		}
		if err := json.NewEncoder(stdout).Encode(result); err != nil {
			return 3
		}
		return code
	}
	if err := flags.Parse(args); err != nil {
		return emit(3, fmt.Errorf("%v: %s", err, usage.String()))
	}
	if *history == "" || *output == "" || *timeout <= 0 || flags.NArg() != 0 {
		return emit(3, fmt.Errorf("usage: -history FILE -output DIRECTORY [-timeout 10s]; timeout must be positive"))
	}
	file, err := os.Open(*history)
	if err != nil {
		return emit(3, err)
	}
	data, err := io.ReadAll(io.LimitReader(file, 16*1024*1024+1))
	closeErr := file.Close()
	if err != nil {
		return emit(3, err)
	}
	if closeErr != nil {
		return emit(3, closeErr)
	}
	if len(data) > 16*1024*1024 {
		return emit(3, fmt.Errorf("history exceeds 16 MiB"))
	}
	if err := os.MkdirAll(*output, 0755); err != nil {
		return emit(3, err)
	}
	if err := os.WriteFile(filepath.Join(*output, "history.json"), data, 0644); err != nil {
		return emit(3, err)
	}
	var h History
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&h); err != nil {
		return emit(3, err)
	}
	if err := decoder.Decode(new(interface{})); err != io.EOF {
		return emit(3, fmt.Errorf("expected exactly one history JSON object"))
	}
	operations, err := h.normalize()
	if err != nil {
		return emit(3, err)
	}
	result.Operations = len(operations)
	for _, op := range h.Operations {
		if op.Outcome == "unknown" {
			result.Unknown++
		}
	}

	normalized, err := json.MarshalIndent(operations, "", "  ")
	if err != nil {
		return emit(3, err)
	}
	if err := os.WriteFile(filepath.Join(*output, "normalized.json"), normalized, 0644); err != nil {
		return emit(3, err)
	}
	model := counterModel(*h.Initial)
	start := time.Now()
	verdict, info := porcupine.CheckOperationsVerbose(model, operations, *timeout)
	result.DurationNanos = time.Since(start).Nanoseconds()
	code := 2
	result.Result = "inconclusive"
	if verdict == porcupine.Ok {
		code = 0
		result.Result = "valid"
	}
	if verdict == porcupine.Illegal {
		code = 1
		result.Result = "invalid"
	}
	if err := porcupine.VisualizePath(model, info, filepath.Join(*output, "history.html")); err != nil {
		return emit(3, err)
	}
	return emit(code, nil)
}
