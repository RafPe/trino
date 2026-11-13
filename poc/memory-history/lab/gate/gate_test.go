package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

const knownSQL = "SELECT count(*) FROM orders WHERE orderkey > 100"

// fakePredictor answers POST /v1/gate. A SQL text that contains "orders" is known.
type fakePredictor struct {
	mu       sync.Mutex
	requests []gateRequest
	status   int
	delay    time.Duration
}

func (p *fakePredictor) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || r.URL.Path != "/v1/gate" {
		http.NotFound(w, r)
		return
	}
	var request gateRequest
	_ = json.NewDecoder(r.Body).Decode(&request)
	p.mu.Lock()
	p.requests = append(p.requests, request)
	status, delay := p.status, p.delay
	p.mu.Unlock()
	if delay > 0 {
		select {
		case <-time.After(delay):
		case <-r.Context().Done():
			return
		}
	}
	if status != 0 && status != http.StatusOK {
		http.Error(w, `{"error":"boom"}`, status)
		return
	}
	response := map[string]any{"known": false, "reservationId": nil, "sessionProperties": map[string]string{}}
	if strings.Contains(request.SQL, "orders") {
		response = map[string]any{
			"known":         true,
			"reservationId": "r-1",
			"sessionProperties": map[string]string{
				"required_workers_count":         "3",
				"required_workers_max_wait_time": "10m",
			},
		}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(response)
}

func (p *fakePredictor) calls() []gateRequest {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]gateRequest(nil), p.requests...)
}

type fixture struct {
	gate *gate
	a, b *fakePredictor
	srv  *httptest.Server
}

func newFixture(t *testing.T, timeout time.Duration) *fixture {
	t.Helper()
	a, b := &fakePredictor{}, &fakePredictor{}
	pa, pb := httptest.NewServer(a), httptest.NewServer(b)
	t.Cleanup(pa.Close)
	t.Cleanup(pb.Close)
	cfg, err := configFromEnv(mapEnv(map[string]string{
		"GATE_GROUPS":               "a=" + pa.URL + ",b=" + pb.URL,
		"GATE_DEFAULT_GROUP":        "a",
		"GATE_PREDICTOR_TIMEOUT_MS": itoa(timeout.Milliseconds()),
	}))
	if err != nil {
		t.Fatal(err)
	}
	g := newGate(cfg)
	srv := httptest.NewServer(g.handler())
	t.Cleanup(srv.Close)
	return &fixture{gate: g, a: a, b: b, srv: srv}
}

func mapEnv(values map[string]string) func(string) string {
	return func(name string) string { return values[name] }
}

func itoa(v int64) string {
	return strconv.FormatInt(v, 10)
}

func (p *fakePredictor) set(status int, delay time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.status, p.delay = status, delay
}

func routeBody(sql string) string {
	body, _ := json.Marshal(map[string]any{
		"trinoQueryProperties": map[string]any{
			"body":                 sql,
			"defaultCatalog":       "tpch",
			"defaultSchema":        "tiny",
			"isNewQuerySubmission": true,
		},
		"trinoRequestUser": map[string]any{"user": "bench"},
	})
	return string(body)
}

func (f *fixture) route(t *testing.T, sql string, headers http.Header) routeResponse {
	t.Helper()
	request, _ := http.NewRequest(http.MethodPost, f.srv.URL+"/gateway/route", strings.NewReader(routeBody(sql)))
	for name, values := range headers {
		for _, value := range values {
			request.Header.Add(name, value)
		}
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status %d", response.StatusCode)
	}
	var decoded routeResponse
	if err := json.NewDecoder(response.Body).Decode(&decoded); err != nil {
		t.Fatal(err)
	}
	return decoded
}

func (f *fixture) envoyCheck(t *testing.T, method string, path string, sql string, headers http.Header) *http.Response {
	t.Helper()
	request, _ := http.NewRequest(method, f.srv.URL+path, strings.NewReader(sql))
	for name, values := range headers {
		for _, value := range values {
			request.Header.Add(name, value)
		}
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	response.Body.Close()
	return response
}

func (f *fixture) stats(t *testing.T) map[string]int64 {
	t.Helper()
	response, err := http.Get(f.srv.URL + "/stats")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var decoded map[string]int64
	if err := json.NewDecoder(response.Body).Decode(&decoded); err != nil {
		t.Fatal(err)
	}
	return decoded
}

func TestRouteKnownQueryAddsSessionProperties(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	got := f.route(t, knownSQL, nil)
	if got.RoutingGroup != "a" {
		t.Errorf("routingGroup = %q, want a", got.RoutingGroup)
	}
	if got.Errors == nil || len(got.Errors) != 0 {
		t.Errorf("errors = %v, want []", got.Errors)
	}
	want := "required_workers_count=3,required_workers_max_wait_time=10m"
	if got.ExternalHeaders[sessionHeader] != want {
		t.Errorf("X-Trino-Session = %q, want %q", got.ExternalHeaders[sessionHeader], want)
	}
	calls := f.a.calls()
	if len(calls) != 1 || calls[0].SQL != knownSQL || calls[0].Catalog != "tpch" || calls[0].Schema != "tiny" {
		t.Errorf("predictor calls = %+v", calls)
	}
}

func TestRouteUnknownQueryAddsNothing(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	got := f.route(t, "SELECT 1", nil)
	if got.RoutingGroup != "a" || len(got.ExternalHeaders) != 0 || got.ExternalHeaders == nil {
		t.Errorf("response = %+v, want group a and empty externalHeaders", got)
	}
	if s := f.stats(t); s["unknown"] != 1 || s["known"] != 0 {
		t.Errorf("stats = %v", s)
	}
}

func TestRouteKeepsAndMergesClientSessionProperties(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	headers := http.Header{}
	headers.Add(sessionHeader, "query_max_run_time=10m, join_distribution_type=BROADCAST")
	headers.Add(sessionHeader, "tpch.splits_per_node=8,required_workers_count=1")
	got := f.route(t, knownSQL, headers)
	want := "query_max_run_time=10m,join_distribution_type=BROADCAST,tpch.splits_per_node=8," +
		"required_workers_count=3,required_workers_max_wait_time=10m"
	if got.ExternalHeaders[sessionHeader] != want {
		t.Errorf("X-Trino-Session = %q, want %q", got.ExternalHeaders[sessionHeader], want)
	}
}

func TestRouteLowerCaseClientHeaderIsMerged(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	request, _ := http.NewRequest(http.MethodPost, f.srv.URL+"/gateway/route", strings.NewReader(routeBody(knownSQL)))
	// Go reads header names case-insensitively, so a lower-case client header is also found.
	request.Header["x-trino-session"] = []string{"query_max_run_time=10m"}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var got routeResponse
	_ = json.NewDecoder(response.Body).Decode(&got)
	want := "query_max_run_time=10m,required_workers_count=3,required_workers_max_wait_time=10m"
	if got.ExternalHeaders[sessionHeader] != want {
		t.Errorf("X-Trino-Session = %q, want %q", got.ExternalHeaders[sessionHeader], want)
	}
}

func TestRouteSelectsGroupFromHeader(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	got := f.route(t, knownSQL, http.Header{routingGroupHeader: {"b"}})
	if got.RoutingGroup != "b" {
		t.Errorf("routingGroup = %q, want b", got.RoutingGroup)
	}
	if len(f.b.calls()) != 1 || len(f.a.calls()) != 0 {
		t.Errorf("calls a=%d b=%d, want a=0 b=1", len(f.a.calls()), len(f.b.calls()))
	}
	got = f.route(t, knownSQL, http.Header{routingGroupHeader: {"nope"}})
	if got.RoutingGroup != "a" {
		t.Errorf("unknown group: routingGroup = %q, want default a", got.RoutingGroup)
	}
}

func TestRouteNotNewSubmissionSkipsPredictor(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	body := `{"trinoQueryProperties":{"body":"SELECT * FROM orders","isNewQuerySubmission":false}}`
	response, err := http.Post(f.srv.URL+"/gateway/route", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if len(f.a.calls()) != 0 {
		t.Errorf("predictor was called for a follow-up request")
	}
}

func TestRouteFailsOpenOnPredictorTimeout(t *testing.T) {
	f := newFixture(t, 100*time.Millisecond)
	f.a.set(0, 2*time.Second)
	start := time.Now()
	got := f.route(t, knownSQL, http.Header{sessionHeader: {"query_max_run_time=10m"}})
	elapsed := time.Since(start)
	if elapsed > 500*time.Millisecond {
		t.Errorf("answer took %s, want about the predictor timeout (100ms)", elapsed)
	}
	if got.RoutingGroup != "a" || len(got.ExternalHeaders) != 0 {
		t.Errorf("response = %+v, want group a and no headers", got)
	}
	if s := f.stats(t); s["predictor_timeouts"] != 1 || s["predictor_errors"] != 0 {
		t.Errorf("stats = %v", s)
	}
}

func TestRouteFailsOpenOnPredictorError(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	f.a.set(http.StatusInternalServerError, 0)
	got := f.route(t, knownSQL, nil)
	if got.RoutingGroup != "a" || len(got.ExternalHeaders) != 0 {
		t.Errorf("response = %+v, want group a and no headers", got)
	}
	if s := f.stats(t); s["predictor_errors"] != 1 {
		t.Errorf("stats = %v", s)
	}
}

func TestRouteFailsOpenOnPredictorDown(t *testing.T) {
	cfg, _ := configFromEnv(mapEnv(map[string]string{"GATE_GROUPS": "a=http://127.0.0.1:1"}))
	srv := httptest.NewServer(newGate(cfg).handler())
	defer srv.Close()
	response, err := http.Post(srv.URL+"/gateway/route", "application/json", strings.NewReader(routeBody(knownSQL)))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var got routeResponse
	_ = json.NewDecoder(response.Body).Decode(&got)
	if response.StatusCode != 200 || got.RoutingGroup != "a" || len(got.ExternalHeaders) != 0 {
		t.Errorf("status %d response %+v", response.StatusCode, got)
	}
}

func TestRouteInvalidBodyFailsOpen(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	response, err := http.Post(f.srv.URL+"/gateway/route", "application/json", strings.NewReader("not json"))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var got routeResponse
	_ = json.NewDecoder(response.Body).Decode(&got)
	if response.StatusCode != 200 || got.RoutingGroup != "a" || len(got.ExternalHeaders) != 0 {
		t.Errorf("status %d response %+v", response.StatusCode, got)
	}
}

func TestEnvoyKnownStatementGetsHeader(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	response := f.envoyCheck(t, http.MethodPost, "/envoy/check/b/v1/statement", knownSQL,
		http.Header{catalogHeader: {"tpch"}, schemaHeader: {"sf1"}, sessionHeader: {"query_max_run_time=10m"}})
	if response.StatusCode != 200 {
		t.Fatalf("status %d", response.StatusCode)
	}
	want := "required_workers_count=3,required_workers_max_wait_time=10m"
	if got := response.Header.Get("x-trino-session"); got != want {
		t.Errorf("x-trino-session = %q, want %q", got, want)
	}
	calls := f.b.calls()
	if len(calls) != 1 || calls[0].SQL != knownSQL || calls[0].Catalog != "tpch" || calls[0].Schema != "sf1" {
		t.Errorf("predictor b calls = %+v", calls)
	}
}

func TestEnvoyUnknownAndOtherRequestsGetNoHeader(t *testing.T) {
	f := newFixture(t, 200*time.Millisecond)
	cases := []struct{ method, path, body string }{
		{http.MethodPost, "/envoy/check/a/v1/statement", "SELECT 1"},
		{http.MethodGet, "/envoy/check/a/v1/statement/queued/x/y/1", ""},
		{http.MethodGet, "/envoy/check/a/v1/info", ""},
		{http.MethodPost, "/envoy/check/zz/v1/statement", knownSQL},
		{http.MethodDelete, "/envoy/check/a/v1/statement/executing/x/y/1", ""},
	}
	for _, c := range cases {
		response := f.envoyCheck(t, c.method, c.path, c.body, nil)
		if response.StatusCode != 200 || response.Header.Get("x-trino-session") != "" {
			t.Errorf("%s %s: status %d header %q", c.method, c.path, response.StatusCode, response.Header.Get("x-trino-session"))
		}
	}
	if len(f.a.calls()) != 1 {
		t.Errorf("predictor a calls = %d, want 1 (only the POST statement)", len(f.a.calls()))
	}
}

func TestEnvoyFailsOpenOnPredictorTimeoutAndError(t *testing.T) {
	f := newFixture(t, 100*time.Millisecond)
	f.a.set(0, 2*time.Second)
	start := time.Now()
	response := f.envoyCheck(t, http.MethodPost, "/envoy/check/a/v1/statement", knownSQL, nil)
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Errorf("answer took %s", elapsed)
	}
	if response.StatusCode != 200 || response.Header.Get("x-trino-session") != "" {
		t.Errorf("timeout: status %d header %q", response.StatusCode, response.Header.Get("x-trino-session"))
	}
	f.a.set(http.StatusInternalServerError, 0)
	response = f.envoyCheck(t, http.MethodPost, "/envoy/check/a/v1/statement", knownSQL, nil)
	if response.StatusCode != 200 || response.Header.Get("x-trino-session") != "" {
		t.Errorf("error: status %d header %q", response.StatusCode, response.Header.Get("x-trino-session"))
	}
}

func TestStatsCounters(t *testing.T) {
	f := newFixture(t, 100*time.Millisecond)
	f.route(t, knownSQL, nil)
	f.route(t, "SELECT 1", nil)
	f.envoyCheck(t, http.MethodGet, "/envoy/check/a/v1/info", "", nil)
	f.b.set(http.StatusInternalServerError, 0)
	f.route(t, knownSQL, http.Header{routingGroupHeader: {"b"}})
	f.a.set(0, time.Second)
	f.route(t, knownSQL, nil)
	got := f.stats(t)
	want := map[string]int64{"requests": 5, "known": 1, "unknown": 1, "predictor_errors": 1, "predictor_timeouts": 1, "skipped": 1}
	for name, value := range want {
		if got[name] != value {
			t.Errorf("stats[%s] = %d, want %d (all: %v)", name, got[name], value, got)
		}
	}
}

func TestCallerGoneIsNotAPredictorError(t *testing.T) {
	a := &fakePredictor{}
	pa := httptest.NewServer(a)
	defer pa.Close()
	cfg, _ := configFromEnv(mapEnv(map[string]string{"GATE_GROUPS": "a=" + pa.URL, "GATE_DELAY_MS": "300"}))
	g := newGate(cfg)
	srv := httptest.NewServer(g.handler())
	defer srv.Close()
	client := &http.Client{Timeout: 100 * time.Millisecond}
	_, err := client.Post(srv.URL+"/gateway/route", "application/json", strings.NewReader(routeBody(knownSQL)))
	if err == nil {
		t.Fatal("want a client timeout")
	}
	deadline := time.Now().Add(2 * time.Second)
	for g.stats.callerGone.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if g.stats.callerGone.Load() != 1 || g.stats.predictorErrors.Load() != 0 || len(a.calls()) != 0 {
		t.Errorf("caller_gone=%d predictor_errors=%d predictor calls=%d", g.stats.callerGone.Load(), g.stats.predictorErrors.Load(), len(a.calls()))
	}
}

func TestHealthz(t *testing.T) {
	f := newFixture(t, 100*time.Millisecond)
	response, err := http.Get(f.srv.URL + "/healthz")
	if err != nil || response.StatusCode != 200 {
		t.Fatalf("healthz: %v %v", err, response)
	}
	response.Body.Close()
}

func TestDelayIsApplied(t *testing.T) {
	cfg, _ := configFromEnv(mapEnv(map[string]string{"GATE_GROUPS": "a=http://127.0.0.1:1", "GATE_DELAY_MS": "150"}))
	srv := httptest.NewServer(newGate(cfg).handler())
	defer srv.Close()
	start := time.Now()
	response, err := http.Post(srv.URL+"/envoy/check/a/v1/info", "text/plain", nil)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if elapsed := time.Since(start); elapsed < 150*time.Millisecond {
		t.Errorf("answer took %s, want at least 150ms", elapsed)
	}
}

func TestConfigValidation(t *testing.T) {
	bad := []map[string]string{
		{"GATE_GROUPS": "a"},
		{"GATE_GROUPS": "a=http://x", "GATE_DEFAULT_GROUP": "b"},
		{"GATE_GROUPS": "a=http://x", "GATE_PREDICTOR_TIMEOUT_MS": "0"},
		{"GATE_GROUPS": "a=http://x", "GATE_DELAY_MS": "-1"},
	}
	for _, env := range bad {
		if _, err := configFromEnv(mapEnv(env)); err == nil {
			t.Errorf("config %v: want an error", env)
		}
	}
	cfg, err := configFromEnv(mapEnv(map[string]string{}))
	if err != nil || cfg.defaultGroup != "a" || cfg.port != 8095 || cfg.predictorTimeout != 200*time.Millisecond {
		t.Errorf("defaults: %+v %v", cfg, err)
	}
}

func TestEncodeSessionEscapesValues(t *testing.T) {
	got := encodeSession(map[string]string{"b": "x y,z", "a": "1"})
	if got != "a=1,b=x+y%2Cz" {
		t.Errorf("encodeSession = %q", got)
	}
}
