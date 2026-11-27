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
	"context"
	"fmt"
	"math"

	"github.com/anishathalye/porcupine"
)

// History uses globally ordered observation indices from a single recorder.
// Return zero denotes an invocation still pending at the observation boundary.
type History struct {
	Version    int               `json:"version"`
	Model      string            `json:"model"`
	Initial    *int64            `json:"initial"`
	Metadata   map[string]string `json:"metadata,omitempty"`
	Operations []Operation       `json:"operations"`
	Faults     []Fault           `json:"faults,omitempty"`
}

type Operation struct {
	ID       int64  `json:"id"`
	Client   int    `json:"client"`
	ClientID string `json:"clientId,omitempty"`
	CallID   *int64 `json:"callId,omitempty"`
	Peer     string `json:"peer,omitempty"`
	Server   string `json:"server,omitempty"`
	Function string `json:"function"`
	Call     int64  `json:"call"`
	Return   int64  `json:"return"`
	Outcome  string `json:"outcome"`
	Value    *int64 `json:"value,omitempty"`
	Error    string `json:"error,omitempty"`
}

type Fault struct {
	Index  int64  `json:"index"`
	Action string `json:"action"`
	Detail string `json:"detail"`
}

func (h History) normalize() ([]porcupine.Operation, error) {
	if h.Version != 1 || h.Model != "counter" || h.Initial == nil || *h.Initial != 0 {
		return nil, fmt.Errorf("expected version 1 counter history with initial value zero")
	}
	if len(h.Operations) == 0 || len(h.Operations) > 10000 {
		return nil, fmt.Errorf("expected between 1 and 10000 operations")
	}
	ids, indices := make(map[int64]bool), make(map[int64]bool)
	end := int64(0)
	addIndex := func(index int64) error {
		if index <= 0 || index == math.MaxInt64 || indices[index] {
			return fmt.Errorf("invalid or duplicate observation index %d", index)
		}
		indices[index] = true
		if index > end {
			end = index
		}
		return nil
	}
	clients := make(map[int]int)
	for _, op := range h.Operations {
		if op.ID <= 0 || ids[op.ID] || op.Client < 0 {
			return nil, fmt.Errorf("invalid/duplicate operation id or client: %+v", op)
		}
		ids[op.ID] = true
		if _, ok := clients[op.Client]; !ok {
			clients[op.Client] = len(clients)
		}
		if op.Function != "increment" && op.Function != "query" {
			return nil, fmt.Errorf("operation %d: unknown function %q", op.ID, op.Function)
		}
		if op.Outcome != "ok" && op.Outcome != "fail" && op.Outcome != "unknown" {
			return nil, fmt.Errorf("operation %d: unknown outcome %q", op.ID, op.Outcome)
		}
		if (op.Outcome == "ok") != (op.Value != nil) || (op.Value != nil && *op.Value < 0) {
			return nil, fmt.Errorf("operation %d: only successful operations require a nonnegative value", op.ID)
		}
		if err := addIndex(op.Call); err != nil {
			return nil, err
		}
		if op.Return == 0 {
			if op.Outcome != "unknown" {
				return nil, fmt.Errorf("operation %d: pending operation must be unknown", op.ID)
			}
		} else {
			if op.Return <= op.Call {
				return nil, fmt.Errorf("operation %d: response precedes invocation", op.ID)
			}
			if err := addIndex(op.Return); err != nil {
				return nil, err
			}
		}
	}
	for _, fault := range h.Faults {
		if fault.Action == "" {
			return nil, fmt.Errorf("fault action is required")
		}
		if err := addIndex(fault.Index); err != nil {
			return nil, err
		}
	}
	operations := make([]porcupine.Operation, 0, len(h.Operations))
	for _, op := range h.Operations {
		ret := op.Return
		if op.Outcome == "unknown" {
			// The caller's timeout does not establish when execution stopped.
			ret = end + 1
		}
		operations = append(operations, porcupine.Operation{
			ClientId: clients[op.Client], Input: op.Function, Output: op,
			Call: op.Call, Return: ret, Metadata: op,
		})
	}
	return operations, nil
}

func counterModel(initial int64) porcupine.Model {
	model := porcupine.NondeterministicModel{
		Init: func() []interface{} { return []interface{}{initial} },
		StepContext: func(ctx context.Context, state, input, output interface{}) []interface{} {
			if ctx.Err() != nil {
				return nil
			}
			n, function, op := state.(int64), input.(string), output.(Operation)
			if op.Outcome == "fail" || (op.Outcome == "unknown" && function == "query") {
				return []interface{}{n}
			}
			if op.Outcome == "unknown" {
				if n == math.MaxInt64 {
					return []interface{}{n}
				}
				return []interface{}{n, n + 1}
			}
			if function == "query" && *op.Value == n {
				return []interface{}{n}
			}
			if function == "increment" && n < math.MaxInt64 && *op.Value == n+1 {
				return []interface{}{n + 1}
			}
			return nil
		},
		Equal: func(a, b interface{}) bool { return a.(int64) == b.(int64) },
		Hash:  func(state interface{}) uint64 { return uint64(state.(int64)) },
		DescribeOperation: func(input, output interface{}) string {
			op := output.(Operation)
			result := op.Outcome
			if op.Value != nil {
				result = fmt.Sprint(*op.Value)
			}
			return fmt.Sprintf("%s → %s (id %d)", input, result, op.ID)
		},
		DescribeState: func(state interface{}) string { return fmt.Sprint(state) },
		DescribeOperationMetadata: func(metadata interface{}) string {
			op := metadata.(Operation)
			callID := "unknown"
			if op.CallID != nil {
				callID = fmt.Sprint(*op.CallID)
			}
			return fmt.Sprintf("client=%s peer=%s server=%s callId=%v error=%s", op.ClientID, op.Peer, op.Server, callID, op.Error)
		},
	}
	return model.ToModel()
}
