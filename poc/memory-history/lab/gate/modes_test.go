package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fakeCoordinator answers the worker-count statement like Trino: POST /v1/statement gives a nextUri,
// the GET on it gives the row. The fake predictor asks for 3 workers for a known query.
type fakeCoordinator struct {
	workers atomic.Int64
	status  atomic.Int64 // 0 = 200
	delay   atomic.Int64 // nanoseconds before each answer
	reads   atomic.Int64
	mu      sync.Mutex
	users   []string
	sqls    []string
}

func (c *fakeCoordinator) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// Read the body first: then the server sees a closed connection, and r.Context() ends with it.
	body, _ := io.ReadAll(r.Body)
	if delay := time.Duration(c.delay.Load()); delay > 0 {
		select {
		case <-time.After(delay):
		case <-r.Context().Done():
			return
		}
	}
	if status := c.status.Load(); status != 0 {
		http.Error(w, "boom", int(status))
		return
	}
	switch {
	case r.Method == http.MethodPost && r.URL.Path == "/v1/statement":
		c.mu.Lock()
		c.users = append(c.users, r.Header.Get("X-Trino-User"))
		c.sqls = append(c.sqls, string(body))
		c.mu.Unlock()
		writeJSON(w, map[string]any{"id": "q1", "nextUri": "http://" + r.Host + "/v1/statement/executing/q1/x/1"})
	case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/v1/statement/executing/"):
		c.reads.Add(1)
		writeJSON(w, map[string]any{"id": "q1", "columns": []any{}, "data": [][]any{{c.workers.Load()}}})
	default:
		http.NotFound(w, r)
	}
}

type modeFixture struct {
	*fixture
	coordinator *fakeCoordinator
}

// newModeFixture starts two fake predictors, one fake coordinator for group a (and b) and the gate.
// The worker count is read every 100 ms instead of every second, so the tests are short.
func newModeFixture(t *testing.T, env map[string]string) *modeFixture {
	t.Helper()
	a, b := &fakePredictor{}, &fakePredictor{}
	pa, pb := httptest.NewServer(a), httptest.NewServer(b)
	coordinator := &fakeCoordinator{}
	pc := httptest.NewServer(coordinator)
	t.Cleanup(pa.Close)
	t.Cleanup(pb.Close)
	t.Cleanup(pc.Close)
	values := map[string]string{
		"GATE_GROUPS":               "a=" + pa.URL + ",b=" + pb.URL,
		"GATE_PREDICTOR_TIMEOUT_MS": "1000",
		"GATE_NODES_A":              pc.URL,
		"GATE_NODES_B":              pc.URL,
	}
	for name, value := range env {
		values[name] = value
	}
	cfg, err := configFromEnv(mapEnv(values))
	if err != nil {
		t.Fatal(err)
	}
	cfg.nodePoll = 100 * time.Millisecond
	g := newGate(cfg)
	srv := httptest.NewServer(g.handler())
	t.Cleanup(srv.Close)
	return &modeFixture{fixture: &fixture{gate: g, a: a, b: b, srv: srv}, coordinator: coordinator}
}

const wantWaitSession = "required_workers_count=3,required_workers_max_wait_time=10m"

// call sends a known or unknown query on one endpoint and returns the session properties that the gate added.
func (f *modeFixture) call(t *testing.T, endpoint string, sql string, headers http.Header) string {
	t.Helper()
	if endpoint == "route" {
		return f.route(t, sql, headers).ExternalHeaders[sessionHeader]
	}
	response := f.envoyCheck(t, http.MethodPost, "/envoy/check/a/v1/statement", sql, headers)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status %d", response.StatusCode)
	}
	return response.Header.Get("x-trino-session")
}

func modeHeaders(value string) http.Header {
	return http.Header{modeHeader: {value}}
}

func TestParseMode(t *testing.T) {
	cases := []struct {
		value string
		want  string
		ok    bool
	}{
		{"wait", "wait", true},
		{"signal", "signal", true},
		{" Signal ", "signal", true},
		{"bounded:30", "bounded:30", true},
		{"BOUNDED: 5", "bounded:5", true},
		{"bounded:0", "bounded:0", true},
		{"bounded:600", "bounded:60", true}, // limited by GATE_MAX_BOUNDED_SECONDS
		{"bounded", "", false},
		{"bounded:", "", false},
		{"bounded:-1", "", false},
		{"bounded:1.5", "", false},
		{"bounded:x", "", false},
		{"", "", false},
		{"fast", "", false},
	}
	for _, c := range cases {
		got, ok := parseMode(c.value, 60)
		if ok != c.ok || (ok && got.String() != c.want) {
			t.Errorf("parseMode(%q) = %v, %v; want %q, %v", c.value, got, ok, c.want, c.ok)
		}
	}
}

func TestModeSelectionOrder(t *testing.T) {
	f := newModeFixture(t, map[string]string{"GATE_MODE_A": "bounded:20", "GATE_DEFAULT_MODE": "signal"})
	cases := []struct {
		name    string
		group   string
		headers http.Header
		want    string
		source  string
	}{
		{"header wins over tag and group", "a", http.Header{modeHeader: {"wait"}, clientTagsHeader: {"gate_mode=signal"}}, "wait", "header"},
		{"tag wins over group", "a", http.Header{clientTagsHeader: {"team=x, gate_mode=signal"}}, "signal", "client tag"},
		{"tag on a second header line", "a", http.Header{clientTagsHeader: {"team=x", "gate_mode=wait"}}, "wait", "client tag"},
		{"unknown header value is no value", "a", http.Header{modeHeader: {"fast"}, clientTagsHeader: {"gate_mode=wait"}}, "wait", "client tag"},
		{"unknown tag value is no value", "a", http.Header{clientTagsHeader: {"gate_mode=later"}}, "bounded:20", "group"},
		{"group default", "a", http.Header{}, "bounded:20", "group"},
		{"no group default: GATE_DEFAULT_MODE", "b", http.Header{}, "signal", "default"},
		{"header seconds are limited", "b", http.Header{modeHeader: {"bounded:999"}}, "bounded:60", "header"},
	}
	for _, c := range cases {
		m, source := f.gate.selectMode(c.headers, c.group)
		if m.String() != c.want || source != c.source {
			t.Errorf("%s: got %s from %s, want %s from %s", c.name, m, source, c.want, c.source)
		}
	}
}

func TestModeConfigDefaults(t *testing.T) {
	cfg, err := configFromEnv(mapEnv(map[string]string{"GATE_GROUPS": "a=http://x,b=http://y", "GATE_MODE_B": "nope", "GATE_DEFAULT_MODE": "slow"}))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.defaultMode.String() != "wait" || len(cfg.groupModes) != 0 || cfg.maxBoundedSeconds != 60 || len(cfg.nodes) != 0 {
		t.Errorf("defaults: default=%s groupModes=%v max=%d nodes=%v", cfg.defaultMode, cfg.groupModes, cfg.maxBoundedSeconds, cfg.nodes)
	}
	if _, err := configFromEnv(mapEnv(map[string]string{"GATE_MAX_BOUNDED_SECONDS": "x"})); err == nil {
		t.Error("GATE_MAX_BOUNDED_SECONDS=x: want an error")
	}
	cfg, _ = configFromEnv(mapEnv(map[string]string{"GATE_GROUPS": "a-1=http://x", "GATE_DEFAULT_GROUP": "a-1", "GATE_NODES_A_1": "http://c/", "GATE_MODE_A_1": "signal"}))
	if cfg.nodes["a-1"] != "http://c" || cfg.groupModes["a-1"].String() != "signal" {
		t.Errorf("group a-1: nodes=%v modes=%v", cfg.nodes, cfg.groupModes)
	}
}

func TestEveryModeOnBothEndpoints(t *testing.T) {
	for _, endpoint := range []string{"route", "envoy"} {
		for _, c := range []struct{ mode, want string }{{"wait", wantWaitSession}, {"signal", ""}, {"bounded:5", ""}} {
			t.Run(endpoint+"/"+c.mode, func(t *testing.T) {
				f := newModeFixture(t, nil)
				f.coordinator.workers.Store(3) // enough workers: bounded answers at once
				start := time.Now()
				if got := f.call(t, endpoint, knownSQL, modeHeaders(c.mode)); got != c.want {
					t.Errorf("session = %q, want %q", got, c.want)
				}
				if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
					t.Errorf("answer took %s", elapsed)
				}
				// The reservation is made in every mode and is not deleted.
				if len(f.a.calls()) != 1 || len(f.a.deletes()) != 0 {
					t.Errorf("predictor calls %d, deletes %v", len(f.a.calls()), f.a.deletes())
				}
				s := f.stats(t)
				wantReads := int64(0)
				if c.mode == "bounded:5" {
					wantReads = 1
					if s["hold_workers_ready"] != 1 || s["mode_bounded"] != 1 {
						t.Errorf("stats = %v", s)
					}
				}
				if f.coordinator.reads.Load() != wantReads || s["known"] != 1 || s["mode_"+strings.Split(c.mode, ":")[0]] != 1 {
					t.Errorf("coordinator reads %d, stats %v", f.coordinator.reads.Load(), s)
				}
				// An unknown query is not changed in any mode and does not read the worker count.
				if got := f.call(t, endpoint, "SELECT 1", modeHeaders(c.mode)); got != "" {
					t.Errorf("unknown query: session = %q", got)
				}
				if f.coordinator.reads.Load() != wantReads {
					t.Errorf("unknown query read the worker count")
				}
			})
		}
	}
}

func TestModeFromClientTagOnBothEndpoints(t *testing.T) {
	for _, endpoint := range []string{"route", "envoy"} {
		f := newModeFixture(t, nil)
		if got := f.call(t, endpoint, knownSQL, http.Header{clientTagsHeader: {"gate_mode=signal"}}); got != "" {
			t.Errorf("%s: tag signal: session = %q", endpoint, got)
		}
		if got := f.call(t, endpoint, knownSQL, http.Header{clientTagsHeader: {"gate_mode=signal"}, modeHeader: {"wait"}}); got != wantWaitSession {
			t.Errorf("%s: header wait over tag: session = %q", endpoint, got)
		}
	}
}

func TestUnknownModeValueUsesTheDefaults(t *testing.T) {
	f := newModeFixture(t, nil)
	if got := f.call(t, "envoy", knownSQL, modeHeaders("later")); got != wantWaitSession {
		t.Errorf("default wait: session = %q", got)
	}
	f = newModeFixture(t, map[string]string{"GATE_MODE_A": "signal"})
	if got := f.call(t, "route", knownSQL, modeHeaders("bounded:abc")); got != "" {
		t.Errorf("group signal: session = %q", got)
	}
	if s := f.stats(t); s["mode_signal"] != 1 || s["mode_bounded"] != 0 {
		t.Errorf("stats = %v", s)
	}
}

func TestBoundedSecondsAreLimited(t *testing.T) {
	f := newModeFixture(t, map[string]string{"GATE_MAX_BOUNDED_SECONDS": "1"})
	f.coordinator.workers.Store(2)
	start := time.Now()
	got := f.call(t, "envoy", knownSQL, modeHeaders("bounded:30"))
	elapsed := time.Since(start)
	if got != "" || elapsed < 900*time.Millisecond || elapsed > 2*time.Second {
		t.Errorf("session %q after %s, want none after about 1 s", got, elapsed)
	}
	if s := f.stats(t); s["hold_time_passed"] != 1 || s["known"] != 1 {
		t.Errorf("stats = %v", s)
	}
}

func TestHoldEndsWhenWorkersArrive(t *testing.T) {
	for _, endpoint := range []string{"route", "envoy"} {
		t.Run(endpoint, func(t *testing.T) {
			f := newModeFixture(t, nil)
			f.coordinator.workers.Store(2)
			time.AfterFunc(700*time.Millisecond, func() { f.coordinator.workers.Store(3) })
			start := time.Now()
			got := f.call(t, endpoint, knownSQL, modeHeaders("bounded:10"))
			elapsed := time.Since(start)
			if got != "" || elapsed < 700*time.Millisecond || elapsed > 2*time.Second {
				t.Errorf("session %q after %s, want none after about 0.7 s", got, elapsed)
			}
			s := f.stats(t)
			if s["hold_workers_ready"] != 1 || s["hold_time_passed"] != 0 || s["known"] != 1 || len(f.a.deletes()) != 0 {
				t.Errorf("stats = %v, deletes %v", s, f.a.deletes())
			}
			f.coordinator.mu.Lock()
			defer f.coordinator.mu.Unlock()
			if f.coordinator.sqls[0] != activeWorkersSQL || f.coordinator.users[0] != nodesUser {
				t.Errorf("coordinator got %q from %q", f.coordinator.sqls[0], f.coordinator.users[0])
			}
		})
	}
}

func TestHoldEndsOnTime(t *testing.T) {
	for _, endpoint := range []string{"route", "envoy"} {
		t.Run(endpoint, func(t *testing.T) {
			f := newModeFixture(t, nil)
			f.coordinator.workers.Store(2)
			start := time.Now()
			got := f.call(t, endpoint, knownSQL, http.Header{clientTagsHeader: {"gate_mode=bounded:1"}})
			elapsed := time.Since(start)
			if got != "" || elapsed < 950*time.Millisecond || elapsed > 1600*time.Millisecond {
				t.Errorf("session %q after %s, want none after about 1 s", got, elapsed)
			}
			if s := f.stats(t); s["hold_time_passed"] != 1 || s["hold_workers_ready"] != 0 || s["known"] != 1 || len(f.a.deletes()) != 0 {
				t.Errorf("stats = %v, deletes %v", s, f.a.deletes())
			}
			// The count was read about every 100 ms (nodePoll of the fixture)
			if reads := f.coordinator.reads.Load(); reads < 5 || reads > 13 {
				t.Errorf("worker count read %d times", reads)
			}
		})
	}
}

func TestCoordinatorNotReachableAnswersAtOnce(t *testing.T) {
	cases := map[string]map[string]string{
		"refused":        {"GATE_NODES_A": "http://127.0.0.1:1"},
		"not configured": {"GATE_NODES_A": ""},
		"error status":   nil,
	}
	for name, env := range cases {
		for _, endpoint := range []string{"route", "envoy"} {
			t.Run(name+"/"+endpoint, func(t *testing.T) {
				f := newModeFixture(t, env)
				f.coordinator.status.Store(http.StatusServiceUnavailable)
				start := time.Now()
				got := f.call(t, endpoint, knownSQL, modeHeaders("bounded:30"))
				if elapsed := time.Since(start); got != "" || elapsed > 500*time.Millisecond {
					t.Errorf("session %q after %s, want none at once", got, elapsed)
				}
				if s := f.stats(t); s["hold_nodes_error"] != 1 || s["known"] != 1 || len(f.a.deletes()) != 0 {
					t.Errorf("stats = %v, deletes %v", s, f.a.deletes())
				}
			})
		}
	}
}

func TestCallerGoneDuringHoldDeletesReservation(t *testing.T) {
	for _, path := range []string{"/gateway/route", "/envoy/check/a/v1/statement"} {
		t.Run(path, func(t *testing.T) {
			f := newModeFixture(t, nil)
			f.coordinator.workers.Store(2)
			body := knownSQL
			if path == "/gateway/route" {
				body = routeBody(knownSQL)
			}
			request, _ := http.NewRequest(http.MethodPost, f.srv.URL+path, strings.NewReader(body))
			request.Header.Set(modeHeader, "bounded:30")
			// The caller gives up after 300 ms, as Envoy `gated` does after 250 ms.
			client := &http.Client{Timeout: 300 * time.Millisecond}
			if _, err := client.Do(request); err == nil {
				t.Fatal("want a client timeout")
			}
			if !waitFor(func() bool { return f.gate.stats.reservationsDeleted.Load() == 1 }) {
				t.Fatalf("reservation not deleted: stats %v deletes %v", f.stats(t), f.a.deletes())
			}
			if got := f.a.deletes(); len(got) != 1 || got[0] != "r-1" {
				t.Errorf("deletes = %v, want [r-1]", got)
			}
			s := f.stats(t)
			if s["hold_caller_gone"] != 1 || s["caller_gone"] != 1 || s["known"] != 0 || s["hold_time_passed"] != 0 {
				t.Errorf("stats = %v", s)
			}
			// The hold stopped: no more worker counts after the caller left.
			reads := f.coordinator.reads.Load()
			time.Sleep(300 * time.Millisecond)
			if f.coordinator.reads.Load() != reads {
				t.Errorf("the gate still reads the worker count after the caller left")
			}
		})
	}
}

func TestModeStatsCounters(t *testing.T) {
	f := newModeFixture(t, nil)
	f.coordinator.workers.Store(3)
	f.call(t, "route", knownSQL, nil)
	f.call(t, "envoy", knownSQL, modeHeaders("signal"))
	f.call(t, "envoy", "SELECT 1", modeHeaders("signal"))
	f.call(t, "route", knownSQL, modeHeaders("bounded:3"))
	f.envoyCheck(t, http.MethodGet, "/envoy/check/a/v1/info", "", modeHeaders("signal")) // not a decision
	got := f.stats(t)
	want := map[string]int64{"requests": 5, "known": 3, "unknown": 1, "mode_wait": 1, "mode_signal": 2, "mode_bounded": 1,
		"hold_workers_ready": 1, "hold_time_passed": 0, "hold_nodes_error": 0, "hold_caller_gone": 0, "skipped": 1}
	for name, value := range want {
		if got[name] != value {
			t.Errorf("stats[%s] = %d, want %d (all: %v)", name, got[name], value, got)
		}
	}
	if _, ok := got["hold_ms_total"]; !ok {
		t.Errorf("stats has no hold_ms_total: %v", got)
	}
}

func TestActiveWorkersReadsTheRow(t *testing.T) {
	f := newModeFixture(t, nil)
	f.coordinator.workers.Store(4)
	n, err := f.gate.activeWorkers(t.Context(), "a")
	if err != nil || n != 4 {
		t.Errorf("activeWorkers = %d, %v", n, err)
	}
	// A failed query is an error, not a count.
	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"id": "q", "error": map[string]any{"message": "no"}})
	}))
	defer failing.Close()
	f.gate.cfg.nodes["a"] = failing.URL
	if _, err := f.gate.activeWorkers(t.Context(), "a"); err == nil {
		t.Error("want an error for a failed query")
	}
}

func TestSlowCoordinatorAnswersAfterTheReadLimit(t *testing.T) {
	for _, endpoint := range []string{"route", "envoy"} {
		t.Run(endpoint, func(t *testing.T) {
			f := newModeFixture(t, nil)
			f.gate.cfg.nodesTimeout = 200 * time.Millisecond
			f.coordinator.workers.Store(2)
			f.coordinator.delay.Store(int64(5 * time.Second))
			start := time.Now()
			got := f.call(t, endpoint, knownSQL, modeHeaders("bounded:30"))
			if elapsed := time.Since(start); got != "" || elapsed < 150*time.Millisecond || elapsed > time.Second {
				t.Errorf("session %q after %s, want none after about 200 ms", got, elapsed)
			}
			if s := f.stats(t); s["hold_nodes_error"] != 1 || s["hold_time_passed"] != 0 || s["known"] != 1 || len(f.a.deletes()) != 0 {
				t.Errorf("stats = %v, deletes %v", s, f.a.deletes())
			}
		})
	}
}

func TestGroupAndDefaultBoundedSecondsAreLimited(t *testing.T) {
	cfg, err := configFromEnv(mapEnv(map[string]string{"GATE_GROUPS": "a=http://x,b=http://y", "GATE_MAX_BOUNDED_SECONDS": "20",
		"GATE_MODE_A": "bounded:45", "GATE_DEFAULT_MODE": "bounded:300"}))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.groupModes["a"].String() != "bounded:20" || cfg.defaultMode.String() != "bounded:20" {
		t.Errorf("group a %s, default %s; want bounded:20 for both", cfg.groupModes["a"], cfg.defaultMode)
	}
	g := newGate(cfg)
	if m, _ := g.selectMode(http.Header{clientTagsHeader: {"gate_mode=bounded:21"}}, "b"); m.String() != "bounded:20" {
		t.Errorf("tag: %s, want bounded:20", m)
	}
}

func TestWorkerCountStatementCountsActiveWorkersOnly(t *testing.T) {
	// The coordinator and the nodes that shut down do not take new tasks, so they must not count.
	for _, part := range []string{"system.runtime.nodes", "coordinator = false", "state = 'active'"} {
		if !strings.Contains(activeWorkersSQL, part) {
			t.Errorf("activeWorkersSQL %q has no %q", activeWorkersSQL, part)
		}
	}
}
