package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const (
	routingGroupHeader = "X-Trino-Routing-Group"
	sessionHeader      = "X-Trino-Session"
	catalogHeader      = "X-Trino-Catalog"
	schemaHeader       = "X-Trino-Schema"
	envoyCheckPrefix   = "/envoy/check/"
	// Upper bound for the SQL text that the gate reads. Larger bodies are treated as unknown.
	maxBodyBytes = 4 << 20
)

type config struct {
	groups           map[string]string // group name -> predictor base URL
	defaultGroup     string
	predictorTimeout time.Duration
	delay            time.Duration
	port             int
	// Wait modes (Contract L6)
	defaultMode       mode
	groupModes        map[string]mode   // GATE_MODE_<GROUP>, only the groups that set a value the gate understands
	maxBoundedSeconds int               // GATE_MAX_BOUNDED_SECONDS
	nodes             map[string]string // GATE_NODES_<GROUP>: coordinator URL of the group, for the worker count
	nodePoll          time.Duration     // time between two worker counts during a hold (1 s)
	nodesTimeout      time.Duration     // time limit of one worker count (1 s)
}

func (c config) groupNames() []string {
	names := make([]string, 0, len(c.groups))
	for name := range c.groups {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func configFromEnv(getenv func(string) string) (config, error) {
	env := func(name, fallback string) string {
		if value := strings.TrimSpace(getenv(name)); value != "" {
			return value
		}
		return fallback
	}
	cfg := config{groups: map[string]string{}}
	for _, entry := range strings.Split(env("GATE_GROUPS", "a=http://predictor-a:8090"), ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		name, target, ok := strings.Cut(entry, "=")
		if !ok || strings.TrimSpace(name) == "" || strings.TrimSpace(target) == "" {
			return cfg, fmt.Errorf("GATE_GROUPS entry %q is not name=url", entry)
		}
		cfg.groups[strings.TrimSpace(name)] = strings.TrimRight(strings.TrimSpace(target), "/")
	}
	if len(cfg.groups) == 0 {
		return cfg, errors.New("GATE_GROUPS is empty")
	}
	cfg.defaultGroup = env("GATE_DEFAULT_GROUP", "a")
	if _, ok := cfg.groups[cfg.defaultGroup]; !ok {
		return cfg, fmt.Errorf("GATE_DEFAULT_GROUP %q is not in GATE_GROUPS", cfg.defaultGroup)
	}
	timeoutMs, err := strconv.Atoi(env("GATE_PREDICTOR_TIMEOUT_MS", "200"))
	if err != nil || timeoutMs <= 0 {
		return cfg, fmt.Errorf("GATE_PREDICTOR_TIMEOUT_MS must be a positive integer")
	}
	cfg.predictorTimeout = time.Duration(timeoutMs) * time.Millisecond
	delayMs, err := strconv.Atoi(env("GATE_DELAY_MS", "0"))
	if err != nil || delayMs < 0 {
		return cfg, fmt.Errorf("GATE_DELAY_MS must be a non-negative integer")
	}
	cfg.delay = time.Duration(delayMs) * time.Millisecond
	cfg.port, err = strconv.Atoi(env("PORT", "8095"))
	if err != nil || cfg.port <= 0 {
		return cfg, fmt.Errorf("PORT must be a positive integer")
	}
	cfg.maxBoundedSeconds, err = strconv.Atoi(env("GATE_MAX_BOUNDED_SECONDS", "60"))
	if err != nil || cfg.maxBoundedSeconds < 0 {
		return cfg, fmt.Errorf("GATE_MAX_BOUNDED_SECONDS must be a non-negative integer")
	}
	// A mode value that the gate does not understand is the same as no value (Contract L6).
	cfg.defaultMode = mode{kind: modeWait}
	if value := env("GATE_DEFAULT_MODE", ""); value != "" {
		if m, ok := parseMode(value, cfg.maxBoundedSeconds); ok {
			cfg.defaultMode = m
		} else {
			log.Printf("GATE_DEFAULT_MODE %q is not a mode; using wait", value)
		}
	}
	cfg.groupModes = map[string]mode{}
	cfg.nodes = map[string]string{}
	for group := range cfg.groups {
		name := groupEnvName(group)
		if value := env("GATE_MODE_"+name, ""); value != "" {
			if m, ok := parseMode(value, cfg.maxBoundedSeconds); ok {
				cfg.groupModes[group] = m
			} else {
				log.Printf("GATE_MODE_%s %q is not a mode; ignored", name, value)
			}
		}
		if value := env("GATE_NODES_"+name, ""); value != "" {
			cfg.nodes[group] = strings.TrimRight(value, "/")
		}
	}
	cfg.nodePoll = time.Second
	cfg.nodesTimeout = time.Second
	return cfg, nil
}

type stats struct {
	requests          atomic.Int64
	known             atomic.Int64
	unknown           atomic.Int64
	predictorErrors   atomic.Int64
	predictorTimeouts atomic.Int64
	skipped           atomic.Int64
	callerGone        atomic.Int64
	// Reservations that the predictor made for a caller that had already gone, and that the gate deleted
	reservationsDeleted     atomic.Int64
	reservationDeleteErrors atomic.Int64
	// Wait modes: decisions for each mode, and how each hold of bounded ended
	modeWait         atomic.Int64
	modeSignal       atomic.Int64
	modeBounded      atomic.Int64
	holdWorkersReady atomic.Int64
	holdTimePassed   atomic.Int64
	holdNodesError   atomic.Int64
	holdCallerGone   atomic.Int64
	holdMillis       atomic.Int64
}

type gate struct {
	cfg    config
	client *http.Client
	stats  stats
}

func newGate(cfg config) *gate {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.MaxIdleConns = 256
	transport.MaxIdleConnsPerHost = 64
	return &gate{cfg: cfg, client: &http.Client{Transport: transport}}
}

func (g *gate) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("/stats", g.handleStats)
	mux.HandleFunc("/gateway/route", g.handleRoute)
	mux.HandleFunc(envoyCheckPrefix, g.handleEnvoyCheck)
	return mux
}

func (g *gate) handleStats(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, map[string]int64{
		"requests":           g.stats.requests.Load(),
		"known":              g.stats.known.Load(),
		"unknown":            g.stats.unknown.Load(),
		"predictor_errors":   g.stats.predictorErrors.Load(),
		"predictor_timeouts": g.stats.predictorTimeouts.Load(),
		"skipped":            g.stats.skipped.Load(),
		"caller_gone":        g.stats.callerGone.Load(),
		// Added counters; the counters above do not change
		"reservations_deleted":      g.stats.reservationsDeleted.Load(),
		"reservation_delete_errors": g.stats.reservationDeleteErrors.Load(),
		// Wait modes (Contract L6)
		"mode_wait":          g.stats.modeWait.Load(),
		"mode_signal":        g.stats.modeSignal.Load(),
		"mode_bounded":       g.stats.modeBounded.Load(),
		"hold_workers_ready": g.stats.holdWorkersReady.Load(),
		"hold_time_passed":   g.stats.holdTimePassed.Load(),
		"hold_nodes_error":   g.stats.holdNodesError.Load(),
		"hold_caller_gone":   g.stats.holdCallerGone.Load(),
		"hold_ms_total":      g.stats.holdMillis.Load(),
	})
}

// routeRequest is the part of the Trino Gateway external routing body that the gate reads.
type routeRequest struct {
	TrinoQueryProperties struct {
		Body                 string          `json:"body"`
		DefaultCatalog       json.RawMessage `json:"defaultCatalog"`
		DefaultSchema        json.RawMessage `json:"defaultSchema"`
		IsNewQuerySubmission *bool           `json:"isNewQuerySubmission"`
	} `json:"trinoQueryProperties"`
}

type routeResponse struct {
	RoutingGroup    string            `json:"routingGroup"`
	Errors          []string          `json:"errors"`
	ExternalHeaders map[string]string `json:"externalHeaders"`
}

// handleRoute answers the external routing call of Trino Gateway. It never returns an error status.
func (g *gate) handleRoute(w http.ResponseWriter, r *http.Request) {
	g.stats.requests.Add(1)
	g.sleep(r.Context())
	group := g.selectGroup(r.Header.Get(routingGroupHeader))
	response := routeResponse{RoutingGroup: group, Errors: []string{}, ExternalHeaders: map[string]string{}}

	var request routeRequest
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil || len(body) > maxBodyBytes || json.Unmarshal(body, &request) != nil {
		g.stats.skipped.Add(1)
		writeJSON(w, response)
		return
	}
	properties := request.TrinoQueryProperties
	if strings.TrimSpace(properties.Body) == "" || (properties.IsNewQuerySubmission != nil && !*properties.IsNewQuerySubmission) {
		g.stats.skipped.Add(1)
		writeJSON(w, response)
		return
	}
	m, _ := g.selectMode(r.Header, group)
	session := g.decide(r.Context(), group, m, properties.Body, optionalString(properties.DefaultCatalog), optionalString(properties.DefaultSchema))
	if session != "" {
		response.ExternalHeaders[sessionHeader] = mergeSession(r.Header.Values(sessionHeader), session)
	}
	writeJSON(w, response)
}

// handleEnvoyCheck answers the ext_authz HTTP call of Envoy. The path is
// /envoy/check/<group><original path>. It always answers 200.
func (g *gate) handleEnvoyCheck(w http.ResponseWriter, r *http.Request) {
	g.stats.requests.Add(1)
	g.sleep(r.Context())
	group, originalPath, _ := strings.Cut(strings.TrimPrefix(r.URL.Path, envoyCheckPrefix), "/")
	originalPath = "/" + originalPath
	_, configured := g.cfg.groups[group]
	if r.Method != http.MethodPost || originalPath != "/v1/statement" || !configured {
		g.stats.skipped.Add(1)
		w.WriteHeader(http.StatusOK)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil || len(body) > maxBodyBytes || strings.TrimSpace(string(body)) == "" {
		g.stats.skipped.Add(1)
		w.WriteHeader(http.StatusOK)
		return
	}
	m, _ := g.selectMode(r.Header, group)
	session := g.decide(r.Context(), group, m, string(body), r.Header.Get(catalogHeader), r.Header.Get(schemaHeader))
	if session != "" {
		w.Header().Set("x-trino-session", session)
	}
	w.WriteHeader(http.StatusOK)
}

func (g *gate) selectGroup(requested string) string {
	requested = strings.TrimSpace(requested)
	if _, ok := g.cfg.groups[requested]; ok {
		return requested
	}
	return g.cfg.defaultGroup
}

func (g *gate) sleep(ctx context.Context) {
	if g.cfg.delay <= 0 {
		return
	}
	select {
	case <-time.After(g.cfg.delay):
	case <-ctx.Done():
	}
}

type gateRequest struct {
	SQL     string `json:"sql"`
	Catalog string `json:"catalog,omitempty"`
	Schema  string `json:"schema,omitempty"`
}

type gateResponse struct {
	ReservationID     *string           `json:"reservationId"`
	SessionProperties map[string]string `json:"sessionProperties"`
}

// decide calls POST /v1/gate on the predictor of the group. It returns the session properties
// to add, encoded as an X-Trino-Session value, or "" for an unknown query and for every error.
// For a known query the predictor makes the reservation in every mode; only `wait` returns the properties.
func (g *gate) decide(parent context.Context, group string, m mode, sql string, catalog string, schema string) string {
	switch m.kind {
	case modeSignal:
		g.stats.modeSignal.Add(1)
	case modeBounded:
		g.stats.modeBounded.Add(1)
	default:
		g.stats.modeWait.Add(1)
	}
	if parent.Err() != nil {
		// The caller (Trino Gateway or Envoy) gave up already. Do not make a reservation for it.
		g.stats.callerGone.Add(1)
		return ""
	}
	// The predictor call does not stop when the caller goes away: the gate must see the answer, so it can
	// delete a reservation that was made for a caller that is gone. The predictor timeout still applies.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(parent), g.cfg.predictorTimeout)
	defer cancel()
	payload, _ := json.Marshal(gateRequest{SQL: sql, Catalog: catalog, Schema: schema})
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, g.cfg.groups[group]+"/v1/gate", bytes.NewReader(payload))
	if err != nil {
		g.predictorFailed(ctx, parent, group, err)
		return ""
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := g.client.Do(request)
	if err != nil {
		g.predictorFailed(ctx, parent, group, err)
		return ""
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		g.predictorFailed(ctx, parent, group, err)
		return ""
	}
	if response.StatusCode != http.StatusOK {
		g.predictorFailed(ctx, parent, group, fmt.Errorf("status %d: %s", response.StatusCode, strings.TrimSpace(string(body))))
		return ""
	}
	var decision gateResponse
	if err := json.Unmarshal(body, &decision); err != nil {
		g.predictorFailed(ctx, parent, group, fmt.Errorf("invalid response: %w", err))
		return ""
	}
	if parent.Err() != nil {
		// The caller gave up while the predictor answered. Nobody gets the session properties, so the
		// reservation would stay until its time limit or until a query of this fingerprint completes.
		g.stats.callerGone.Add(1)
		if decision.ReservationID != nil && *decision.ReservationID != "" {
			g.deleteReservation(group, *decision.ReservationID)
		}
		return ""
	}
	session := encodeSession(decision.SessionProperties)
	if session == "" {
		g.stats.unknown.Add(1)
		return ""
	}
	switch m.kind {
	case modeWait:
		g.stats.known.Add(1)
		return session
	case modeSignal:
		g.stats.known.Add(1)
		return ""
	}
	// bounded: hold the answer until the workers are there or the time passed, then add nothing.
	required, err := strconv.Atoi(decision.SessionProperties["required_workers_count"])
	if err != nil {
		log.Printf("group %s: no valid required_workers_count in %v; the query passes now", group, decision.SessionProperties)
		g.stats.known.Add(1)
		return ""
	}
	start := time.Now()
	end, active := g.hold(parent, group, required, time.Duration(m.seconds)*time.Second)
	held := time.Since(start)
	g.stats.holdMillis.Add(held.Milliseconds())
	log.Printf("group %s mode %s: required %d workers, active %d, held %d ms: %s", group, m, required, active, held.Milliseconds(), end)
	switch end {
	case holdWorkersReady:
		g.stats.holdWorkersReady.Add(1)
	case holdTimePassed:
		g.stats.holdTimePassed.Add(1)
	case holdNodesError:
		g.stats.holdNodesError.Add(1)
	case holdCallerGone:
		// Nobody gets the answer, and the query may run on another path. Delete the reservation, as above.
		g.stats.holdCallerGone.Add(1)
		g.stats.callerGone.Add(1)
		if decision.ReservationID != nil && *decision.ReservationID != "" {
			g.deleteReservation(group, *decision.ReservationID)
		}
		return ""
	}
	g.stats.known.Add(1)
	return ""
}

// deleteReservation calls DELETE /v1/reservations/{id} on the predictor of the group. Best effort:
// a failure is counted and logged, and the time limit of the reservation remains.
func (g *gate) deleteReservation(group string, id string) {
	ctx, cancel := context.WithTimeout(context.Background(), g.cfg.predictorTimeout)
	defer cancel()
	target := g.cfg.groups[group] + "/v1/reservations/" + url.PathEscape(id)
	request, err := http.NewRequestWithContext(ctx, http.MethodDelete, target, nil)
	if err == nil {
		var response *http.Response
		response, err = g.client.Do(request)
		if err == nil {
			_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<16))
			response.Body.Close()
			if response.StatusCode/100 != 2 {
				err = fmt.Errorf("status %d", response.StatusCode)
			}
		}
	}
	if err != nil {
		g.stats.reservationDeleteErrors.Add(1)
		log.Printf("predictor %s: deleting reservation %s of a gone caller failed: %v", group, id, err)
		return
	}
	g.stats.reservationsDeleted.Add(1)
}

func (g *gate) predictorFailed(ctx context.Context, parent context.Context, group string, err error) {
	if parent.Err() != nil {
		g.stats.callerGone.Add(1)
		return
	}
	var netErr net.Error
	if ctx.Err() != nil || errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &netErr) && netErr.Timeout()) {
		g.stats.predictorTimeouts.Add(1)
		log.Printf("predictor %s: timeout after %s; query passes unchanged", group, g.cfg.predictorTimeout)
		return
	}
	g.stats.predictorErrors.Add(1)
	log.Printf("predictor %s: %v; query passes unchanged", group, err)
}

// encodeSession returns "name=value,..." with the values URL-encoded, sorted by name.
func encodeSession(properties map[string]string) string {
	names := make([]string, 0, len(properties))
	for name := range properties {
		if strings.TrimSpace(name) != "" {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	entries := make([]string, 0, len(names))
	for _, name := range names {
		entries = append(entries, name+"="+url.QueryEscape(properties[name]))
	}
	return strings.Join(entries, ",")
}

// mergeSession keeps every session entry that the client sent, unchanged, and appends the
// entries of the gate. For a name that is in both, the value of the gate wins: the client
// entry with that name is dropped, so the merged value has no duplicate names.
func mergeSession(client []string, added string) string {
	addedNames := map[string]bool{}
	for _, entry := range strings.Split(added, ",") {
		name, _, _ := strings.Cut(entry, "=")
		addedNames[strings.TrimSpace(name)] = true
	}
	parts := make([]string, 0, len(client)+1)
	for _, value := range client {
		for _, entry := range strings.Split(value, ",") {
			entry = strings.TrimSpace(entry)
			name, _, _ := strings.Cut(entry, "=")
			if entry != "" && !addedNames[strings.TrimSpace(name)] {
				parts = append(parts, entry)
			}
		}
	}
	if added != "" {
		parts = append(parts, added)
	}
	return strings.Join(parts, ",")
}

// optionalString reads a JSON string. Trino Gateway serializes Optional<String> as a string or null.
func optionalString(raw json.RawMessage) string {
	var value string
	if len(raw) > 0 && json.Unmarshal(raw, &value) == nil {
		return value
	}
	return ""
}

func writeJSON(w http.ResponseWriter, value any) {
	body, _ := json.Marshal(value)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
}
