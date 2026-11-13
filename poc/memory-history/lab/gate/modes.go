package main

// Wait modes (Contract L6). For a known query the gate always makes the reservation. The mode decides
// what the gate answers:
//   wait               the session properties required_workers_count and required_workers_max_wait_time
//   signal             nothing; the query starts at once with the workers that are there
//   bounded:<seconds>  nothing, but only after the cluster has the required workers or <seconds> passed

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	modeHeader       = "X-Memory-Gate-Mode"
	clientTagsHeader = "X-Trino-Client-Tags"
	modeTagPrefix    = "gate_mode="
	// The statement that the gate sends to the coordinator for the worker count. system.runtime.nodes has
	// the node state (active, shutting down, ...); only active workers get new tasks.
	activeWorkersSQL = "SELECT count(*) FROM system.runtime.nodes WHERE coordinator = false AND state = 'active'"
	nodesUser        = "memory-gate"
)

type modeKind int

const (
	modeWait modeKind = iota
	modeSignal
	modeBounded
)

type mode struct {
	kind    modeKind
	seconds int // bounded only, already limited by GATE_MAX_BOUNDED_SECONDS
}

func (m mode) String() string {
	switch m.kind {
	case modeSignal:
		return "signal"
	case modeBounded:
		return "bounded:" + strconv.Itoa(m.seconds)
	default:
		return "wait"
	}
}

// parseMode reads "wait", "signal" or "bounded:<seconds>" (case and spaces do not matter). <seconds> is a
// non-negative integer; a larger value than maxSeconds becomes maxSeconds. ok is false for any other value.
func parseMode(value string, maxSeconds int) (mode, bool) {
	value = strings.ToLower(strings.TrimSpace(value))
	switch value {
	case "wait":
		return mode{kind: modeWait}, true
	case "signal":
		return mode{kind: modeSignal}, true
	}
	text, found := strings.CutPrefix(value, "bounded:")
	if !found {
		return mode{}, false
	}
	seconds, err := strconv.Atoi(strings.TrimSpace(text))
	if err != nil || seconds < 0 {
		return mode{}, false
	}
	return mode{kind: modeBounded, seconds: min(seconds, maxSeconds)}, true
}

// groupEnvName gives the suffix of GATE_MODE_<GROUP> and GATE_NODES_<GROUP>: upper case, other characters as "_".
func groupEnvName(group string) string {
	var name strings.Builder
	for _, r := range strings.ToUpper(group) {
		if (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			name.WriteRune(r)
		} else {
			name.WriteRune('_')
		}
	}
	return name.String()
}

// selectMode returns the mode and where it came from. The first value that the gate understands wins:
// the header X-Memory-Gate-Mode, a client tag gate_mode=<mode>, GATE_MODE_<GROUP>, GATE_DEFAULT_MODE.
func (g *gate) selectMode(headers http.Header, group string) (mode, string) {
	for _, value := range headers.Values(modeHeader) {
		if m, ok := parseMode(value, g.cfg.maxBoundedSeconds); ok {
			return m, "header"
		}
	}
	for _, value := range headers.Values(clientTagsHeader) {
		for _, tag := range strings.Split(value, ",") {
			if text, found := strings.CutPrefix(strings.TrimSpace(tag), modeTagPrefix); found {
				if m, ok := parseMode(text, g.cfg.maxBoundedSeconds); ok {
					return m, "client tag"
				}
			}
		}
	}
	if m, ok := g.cfg.groupModes[group]; ok {
		return m, "group"
	}
	return g.cfg.defaultMode, "default"
}

type holdEnd int

const (
	holdWorkersReady holdEnd = iota
	holdTimePassed
	holdNodesError
	holdCallerGone
)

func (h holdEnd) String() string {
	return [...]string{"workers ready", "time passed", "worker count not readable", "caller gone"}[h]
}

// hold waits until the coordinator of the group has at least `required` active workers, or until the time
// limit of the mode passed. It reads the count at once and then every second. If it cannot read the
// count, it stops at once. It stops when the caller goes away.
func (g *gate) hold(parent context.Context, group string, required int, limit time.Duration) (holdEnd, int) {
	deadline := time.Now().Add(limit)
	active := -1
	for {
		count, err := g.activeWorkers(parent, group)
		if parent.Err() != nil {
			return holdCallerGone, active
		}
		if err != nil {
			log.Printf("group %s: worker count not readable: %v; the query passes now", group, err)
			return holdNodesError, active
		}
		active = count
		if active >= required {
			return holdWorkersReady, active
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return holdTimePassed, active
		}
		select {
		case <-parent.Done():
			return holdCallerGone, active
		case <-time.After(min(g.cfg.nodePoll, remaining)):
		}
		if time.Until(deadline) <= 0 {
			return holdTimePassed, active
		}
	}
}

type statementResult struct {
	NextURI string  `json:"nextUri"`
	Data    [][]any `json:"data"`
	Error   *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// activeWorkers runs activeWorkersSQL on the coordinator of the group with the REST protocol
// (POST /v1/statement, then each nextUri). The lab has no authentication: the header X-Trino-User is enough.
func (g *gate) activeWorkers(parent context.Context, group string) (int, error) {
	base, ok := g.cfg.nodes[group]
	if !ok {
		return 0, fmt.Errorf("GATE_NODES_%s is not set", groupEnvName(group))
	}
	ctx, cancel := context.WithTimeout(parent, g.cfg.nodesTimeout)
	defer cancel()
	method, target, body := http.MethodPost, base+"/v1/statement", []byte(activeWorkersSQL)
	count := -1
	for {
		request, err := http.NewRequestWithContext(ctx, method, target, bytes.NewReader(body))
		if err != nil {
			return 0, err
		}
		request.Header.Set("X-Trino-User", nodesUser)
		request.Header.Set("X-Trino-Source", nodesUser)
		response, err := g.client.Do(request)
		if err != nil {
			return 0, err
		}
		data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
		response.Body.Close()
		if err != nil {
			return 0, err
		}
		if response.StatusCode != http.StatusOK {
			return 0, fmt.Errorf("status %d: %s", response.StatusCode, strings.TrimSpace(string(data)))
		}
		var result statementResult
		if err := json.Unmarshal(data, &result); err != nil {
			return 0, fmt.Errorf("invalid response: %w", err)
		}
		if result.Error != nil {
			return 0, fmt.Errorf("query failed: %s", result.Error.Message)
		}
		if len(result.Data) > 0 && len(result.Data[0]) > 0 {
			value, ok := result.Data[0][0].(float64)
			if !ok {
				return 0, fmt.Errorf("unexpected value %v", result.Data[0][0])
			}
			count = int(value)
		}
		if result.NextURI == "" {
			break
		}
		method, target, body = http.MethodGet, result.NextURI, nil
	}
	if count < 0 {
		return 0, errors.New("no row")
	}
	return count, nil
}
