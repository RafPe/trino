"""Lab bench (Contract L5). Runs in a container on the mhlab network. Standard library only.

Commands:
  smoke                      SELECT 1 on every path (waits up to 3 minutes for each path).
  learn                      runs the W2 query on cluster A and cluster B directly, and waits until each predictor knows it.
  run   --workloads W1,W2,W3 the timed workloads on all paths.
  w4    --tag <label>        W1 in sequence on the direct and the gated paths (gate stopped or slow).
  checks                     the three checks of Contract L5 and the header tests.

Every result is one JSON line in --out (default /results/bench-compose.jsonl).
"""
import argparse
import http.client
import json
import random
import socket
import statistics
import sys
import threading
import time
import urllib.parse

PATHS = {
    "direct": "http://coordinator-a:8080",
    "gateway-plain": "http://gateway-plain:8080",
    "gateway": "http://gateway:8080",
    "envoy-plain": "http://envoy:10001",
    "envoy-gated": "http://envoy:10000",
    "envoy-first-only": "http://envoy:10002",
}
GATED = ["gateway", "envoy-gated", "envoy-first-only"]
COORDINATORS = {"a": "http://coordinator-a:8080", "b": "http://coordinator-b:8080"}
PREDICTORS = {"a": "http://predictor-a:8090", "b": "http://predictor-b:8090"}
GATE = "http://gate:8095"

W1_SQL = "SELECT 1"
W2_TEMPLATE = "SELECT count(*) FROM orders WHERE orderkey > {}"
W3_SQL = "SELECT * FROM lineitem LIMIT {}"


class Client:
    """A Trino REST client with one keep-alive connection for each host, like a client session."""

    def __init__(self, timeout=600):
        self.timeout = timeout
        self.connections = {}

    def _connection(self, netloc):
        connection = self.connections.get(netloc)
        if connection is None:
            host, _, port = netloc.partition(":")
            connection = http.client.HTTPConnection(host, int(port or 80), timeout=self.timeout)
            self.connections[netloc] = connection
        return connection

    def request(self, method, url, body=None, headers=None):
        parsed = urllib.parse.urlsplit(url)
        target = parsed.path + ("?" + parsed.query if parsed.query else "")
        for attempt in (1, 2):
            connection = self._connection(parsed.netloc)
            try:
                connection.request(method, target, body=body, headers=headers or {})
                response = connection.getresponse()
                data = response.read()
                return response.status, response.headers, data
            except (http.client.RemoteDisconnected, BrokenPipeError, ConnectionResetError, http.client.CannotSendRequest):
                # A keep-alive connection that the server closed: the request did not reach it.
                connection.close()
                self.connections.pop(parsed.netloc, None)
                if attempt == 2:
                    raise

    def close(self):
        for connection in self.connections.values():
            connection.close()
        self.connections.clear()


def headers_for(catalog="tpch", schema="tiny", session=None, group="a", extra=None):
    headers = {
        "X-Trino-User": "bench",
        "X-Trino-Source": "mhlab-bench",
        "X-Trino-Catalog": catalog,
        "X-Trino-Schema": schema,
        "X-Trino-Routing-Group": group,
        "Content-Type": "text/plain",
    }
    if session:
        headers["X-Trino-Session"] = session
    if extra:
        headers.update(extra)
    return headers


def run_query(client, base, sql, headers):
    """Runs one query. Returns the timing of the first request and of the whole query."""
    record = {"rows": 0, "bytes": 0, "requests": 0, "next_hosts": []}
    start = time.perf_counter()
    try:
        status, _, body = client.request("POST", base + "/v1/statement", sql.encode(), headers)
        record["t_first_ms"] = (time.perf_counter() - start) * 1000
        record["bytes"] += len(body)
        record["requests"] += 1
        if status != 200:
            raise RuntimeError(f"HTTP {status}: {body[:200]!r}")
        result = json.loads(body)
        record["query_id"] = result.get("id")
        hosts = set()
        follow = {k: v for k, v in headers.items() if k in ("X-Trino-User", "X-Trino-Routing-Group")}
        while True:
            record["rows"] += len(result.get("data") or [])
            next_uri = result.get("nextUri")
            if not next_uri:
                break
            hosts.add(urllib.parse.urlsplit(next_uri).netloc)
            status, _, body = client.request("GET", next_uri, headers=follow)
            record["bytes"] += len(body)
            record["requests"] += 1
            if status != 200:
                raise RuntimeError(f"HTTP {status} on nextUri: {body[:200]!r}")
            result = json.loads(body)
        record["t_total_ms"] = (time.perf_counter() - start) * 1000
        record["state"] = result.get("stats", {}).get("state")
        if result.get("error"):
            record["error"] = result["error"].get("errorName") + ": " + result["error"].get("message", "")[:200]
        record["next_hosts"] = sorted(hosts)
    except (OSError, RuntimeError, ValueError, http.client.HTTPException) as e:
        record["t_total_ms"] = (time.perf_counter() - start) * 1000
        record["state"] = "CLIENT_ERROR"
        record["error"] = f"{type(e).__name__}: {e}"
        client.close()
    return record


def get_json(url, headers=None, timeout=10):
    parsed = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    try:
        connection.request("GET", parsed.path + ("?" + parsed.query if parsed.query else ""), headers=headers or {})
        response = connection.getresponse()
        return response.status, json.loads(response.read() or b"null")
    finally:
        connection.close()


def post_json(url, payload, timeout=10):
    parsed = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    try:
        connection.request("POST", parsed.path, body=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        return response.status, json.loads(response.read() or b"null")
    finally:
        connection.close()


def gate_stats():
    try:
        return get_json(GATE + "/stats", timeout=2)[1]
    except OSError as e:
        return {"error": str(e)}


def reservations():
    return {group: get_json(url + "/v1/required-workers")[1] for group, url in PREDICTORS.items()}


class Output:
    def __init__(self, path, env, run_id):
        self.file = open(path, "a", buffering=1)
        self.env = env
        self.run_id = run_id
        self.lock = threading.Lock()

    def write(self, record):
        record = {"env": self.env, "run": self.run_id, "ts": time.time(), **record}
        with self.lock:
            self.file.write(json.dumps(record, sort_keys=True) + "\n")


def summary(records, key):
    values = sorted(r[key] for r in records if r.get("state") == "FINISHED" and key in r)
    if not values:
        return "n/a"
    q = lambda p: values[min(len(values) - 1, int(round(p * (len(values) - 1))))]
    return f"n={len(values)} p50={statistics.median(values):.1f} p95={q(0.95):.1f} p99={q(0.99):.1f}"


# ---------------------------------------------------------------- commands

def cmd_smoke(args, out):
    failed = False
    for name, base in PATHS.items():
        deadline = time.monotonic() + 180
        client = Client(timeout=30)
        while True:
            record = run_query(client, base, W1_SQL, headers_for())
            if record.get("state") == "FINISHED" or time.monotonic() > deadline:
                break
            time.sleep(2)
        client.close()
        ok = record.get("state") == "FINISHED"
        failed |= not ok
        print(f"smoke {name:17s} {record.get('state')} {record.get('query_id')} total={record['t_total_ms']:.1f}ms {record.get('error', '')}")
    sys.exit(1 if failed else 0)


def cmd_learn(args, out):
    for group, base in COORDINATORS.items():
        client = Client()
        record = run_query(client, base, W2_TEMPLATE.format(1), headers_for(group=group))
        print(f"learn {group}: {record.get('state')} {record.get('query_id')}")
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            status, prediction = post_json(PREDICTORS[group] + "/v1/predict", {"sql": W2_TEMPLATE.format(2), "catalog": "tpch", "schema": "tiny"})
            if status == 200 and prediction.get("known"):
                print(f"learn {group}: predictor knows the W2 query: {json.dumps(prediction)}")
                break
            time.sleep(1)
        else:
            sys.exit(f"predictor {group} does not know the W2 query")


def sequential(out, workload, paths, runs, make_sql, catalog, schema, tag=None):
    clients = {path: Client() for path in paths}
    by_path = {path: [] for path in paths}
    order = list(paths)
    for i in range(runs):
        random.shuffle(order)
        for path in order:
            record = run_query(clients[path], PATHS[path], make_sql(i), headers_for(catalog, schema))
            record.update(workload=workload, path=path, concurrency=1, i=i)
            if tag:
                record["tag"] = tag
            out.write(record)
            by_path[path].append(record)
    for client in clients.values():
        client.close()
    for path in paths:
        print(f"{workload} {tag or ''} {path:17s} total {summary(by_path[path], 't_total_ms')} | first {summary(by_path[path], 't_first_ms')}")


def concurrent(out, workload, paths, runs, threads, make_sql, catalog, schema):
    order = list(paths)
    random.shuffle(order)
    for path in order:
        records = []
        per_thread = runs // threads

        def worker(index):
            client = Client()
            for i in range(per_thread):
                record = run_query(client, PATHS[path], make_sql(i), headers_for(catalog, schema))
                record.update(workload=workload, path=path, concurrency=threads, i=index * per_thread + i)
                out.write(record)
                records.append(record)
            client.close()

        started = time.perf_counter()
        pool = [threading.Thread(target=worker, args=(t,)) for t in range(threads)]
        for t in pool:
            t.start()
        for t in pool:
            t.join()
        wall = time.perf_counter() - started
        out.write({"workload": workload, "path": path, "concurrency": threads, "kind": "wall", "wall_s": wall, "queries": len(records)})
        print(f"{workload} c={threads} {path:17s} total {summary(records, 't_total_ms')} | first {summary(records, 't_first_ms')} | {len(records) / wall:.1f} q/s")


def cmd_run(args, out):
    paths = list(PATHS)
    workloads = args.workloads.split(",")
    out.write({"kind": "gate_stats", "when": "before", "stats": gate_stats(), "reservations": reservations()})
    # Warm-up: connections, JIT and the plan caches of every path.
    sequential(out, "warmup", paths, 20, lambda i: W1_SQL, "tpch", "tiny")
    if "W1" in workloads:
        sequential(out, "W1", paths, args.w1_runs, lambda i: W1_SQL, "tpch", "tiny")
        concurrent(out, "W1", paths, args.w1_runs, 8, lambda i: W1_SQL, "tpch", "tiny")
    if "W2" in workloads:
        before = gate_stats()
        sequential(out, "W2", paths, args.w2_runs, lambda i: W2_TEMPLATE.format(random.randint(1, 100000)), "tpch", "tiny")
        out.write({"kind": "gate_stats", "when": "after W2", "before": before, "stats": gate_stats(), "reservations": reservations()})
    if "W3" in workloads:
        sequential(out, "W3", paths, args.w3_runs, lambda i: W3_SQL.format(args.w3_rows), "tpch", "sf1")
    out.write({"kind": "gate_stats", "when": "after", "stats": gate_stats(), "reservations": reservations()})


def cmd_w4(args, out):
    paths = ["direct"] + GATED
    before = gate_stats()
    sequential(out, "W4", paths, args.runs, lambda i: W1_SQL, "tpch", "tiny", tag=args.tag)
    # The known query too: with the gate stopped or slow it must run unchanged.
    sequential(out, "W4-known", paths, max(5, args.runs // 10), lambda i: W2_TEMPLATE.format(random.randint(1, 100000)), "tpch", "tiny", tag=args.tag)
    out.write({"kind": "gate_stats", "when": f"after W4 {args.tag}", "before": before, "stats": gate_stats()})


def query_session(query_id):
    """Session properties that Trino applied to a query, from the coordinator of cluster A."""
    status, info = get_json(f"{COORDINATORS['a']}/v1/query/{query_id}", headers={"X-Trino-User": "bench"})
    if status != 200:
        return None
    return info.get("session", {}).get("systemProperties", {})


def raw_post(base, sql, header_lines):
    """POST /v1/statement with the exact header names and lines given (http.client keeps the case)."""
    parsed = urllib.parse.urlsplit(base)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=60)
    connection.putrequest("POST", "/v1/statement", skip_accept_encoding=True)
    body = sql.encode()
    for name, value in header_lines:
        connection.putheader(name, value)
    connection.putheader("Content-Length", str(len(body)))
    connection.endheaders(body)
    response = connection.getresponse()
    result = json.loads(response.read())
    client = Client()
    while result.get("nextUri"):
        _, _, data = client.request("GET", result["nextUri"], headers={"X-Trino-User": "bench"})
        result = json.loads(data)
    client.close()
    connection.close()
    return result


def cmd_checks(args, out):
    known_sql = W2_TEMPLATE.format(4242)
    results = []

    def check(name, ok, detail):
        results.append(ok)
        out.write({"kind": "check", "check": name, "ok": ok, "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'} {name}: {json.dumps(detail, sort_keys=True)}")

    # 1 and 2: client property kept, required_workers_count added, on both gated paths (and first-only).
    for path in GATED + ["direct"]:
        client = Client()
        record = run_query(client, PATHS[path], known_sql, headers_for(session="query_max_run_time=10m"))
        client.close()
        session = query_session(record.get("query_id")) or {}
        check(f"client session kept [{path}]", session.get("query_max_run_time") == "10m",
              {"query_id": record.get("query_id"), "state": record.get("state"), "session": session})
        if path != "direct":
            check(f"known query gets required_workers_count [{path}]", "required_workers_count" in session,
                  {"query_id": record.get("query_id"), "session": session})

    # Header tests through Trino Gateway: client header name casing and several header lines.
    variants = {
        "canonical X-Trino-Session": [("X-Trino-Session", "query_max_run_time=11m")],
        "lower-case x-trino-session": [("x-trino-session", "query_max_run_time=12m")],
        "two X-Trino-Session lines": [("X-Trino-Session", "query_max_run_time=13m"), ("X-Trino-Session", "join_distribution_type=BROADCAST")],
    }
    for path in ("gateway", "envoy-gated", "direct"):
        for label, lines in variants.items():
            base_headers = [("X-Trino-User", "bench"), ("X-Trino-Catalog", "tpch"), ("X-Trino-Schema", "tiny"), ("X-Trino-Routing-Group", "a")]
            try:
                result = raw_post(PATHS[path], known_sql, base_headers + lines)
                session = query_session(result.get("id")) or {}
                detail = {"query_id": result.get("id"), "state": result.get("stats", {}).get("state"),
                          "error": (result.get("error") or {}).get("message"), "session": session}
            except (OSError, ValueError, http.client.HTTPException) as e:
                detail = {"error": f"{type(e).__name__}: {e}"}
                session = {}
            wanted = {name: value for name, value in (line[1].split("=") for line in lines)}
            ok = all(session.get(name) == value for name, value in wanted.items())
            if path != "direct":
                ok = ok and "required_workers_count" in session
            check(f"header {label} [{path}]", ok, detail)

    # 3: reservations go back to 0.
    deadline = time.monotonic() + 60
    while True:
        current = reservations()
        if all(r.get("reservations") == 0 for r in current.values()) or time.monotonic() > deadline:
            break
        time.sleep(1)
    check("reservations back to 0", all(r.get("reservations") == 0 for r in current.values()),
          {"required_workers": current, "waited_s": round(60 - (deadline - time.monotonic()), 1)})
    out.write({"kind": "gate_stats", "when": "after checks", "stats": gate_stats()})
    sys.exit(0 if all(results) else 1)


# ---------------------------------------------------------------- wait modes (Contract L6)

# Paths for the mode checks. Not in PATHS, so the timed workloads do not change.
MODE_PATHS = {
    "envoy-gated": "http://envoy:10000",
    "envoy-gated-bounded": "http://envoy:10003",
    "gateway": "http://gateway:8080",
}
# A light query: 2 workers, about 3 s on 2 workers, so a reservation is visible while it runs.
LIGHT_SQL = "SELECT count(*) FROM (SELECT orderkey, count(*) AS c FROM tpch.sf5.lineitem GROUP BY orderkey) WHERE c > {}"
# A heavier query: peak about 1.75 GB, so the predictor asks for 3 workers (1.5 x 1.75 GB / 1 GiB = 2.4).
# On 2 workers it still finishes, in about 5 s. The client sets the join type, so the peak stays the same.
HEAVY_SQL = "SELECT count(*) FROM tpch.sf9.orders o JOIN tpch.sf9.lineitem l ON o.orderkey = l.orderkey WHERE l.quantity > {}"
HEAVY_SESSION = "join_distribution_type=PARTITIONED"


def query_info(query_id):
    status, info = get_json(f"{COORDINATORS['a']}/v1/query/{query_id}", headers={"X-Trino-User": "bench"})
    return info if status == 200 else {}


def reservations_a():
    return get_json(PREDICTORS["a"] + "/v1/required-workers")[1].get("reservations")


def mode_headers(how, mode, session=None):
    """how = header | tag | none: where the client puts the mode."""
    extra = {}
    if how == "header":
        extra["X-Memory-Gate-Mode"] = mode
    elif how == "tag":
        extra["X-Trino-Client-Tags"] = "mhlab-bench,gate_mode=" + mode
    return headers_for(schema="sf1", session=session, extra=extra)


def run_watched(base, sql, headers):
    """Runs one query and samples the reservations of predictor-a right after the first request and 1.5 s later."""
    client = Client()
    record = {"rows": 0}
    start = time.perf_counter()
    status, _, body = client.request("POST", base + "/v1/statement", sql.encode(), headers)
    record["t_first_ms"] = round((time.perf_counter() - start) * 1000, 1)
    record["resv_after_first"] = reservations_a()
    result = json.loads(body) if status == 200 else {}
    record["query_id"] = result.get("id")
    sampled = False
    follow = {"X-Trino-User": "bench"}
    while result.get("nextUri"):
        if not sampled and time.perf_counter() - start > record["t_first_ms"] / 1000 + 1.5:
            record["resv_1500ms"] = reservations_a()
            sampled = True
        status, _, body = client.request("GET", result["nextUri"], headers=follow)
        result = json.loads(body)
    client.close()
    record["t_total_ms"] = round((time.perf_counter() - start) * 1000, 1)
    record["state"] = result.get("stats", {}).get("state")
    if result.get("error"):
        record["error"] = result["error"].get("errorName")
    return record


def wait_reservations(target, limit_s=30):
    deadline = time.monotonic() + limit_s
    started = time.monotonic()
    while True:
        current = reservations_a()
        if current == target or time.monotonic() > deadline:
            return current, round(time.monotonic() - started, 1)
        time.sleep(0.5)


def learn_a(sql, session=None, min_workers=2):
    """Runs the shape directly on cluster A until predictor-a knows it and asks for at least min_workers."""
    for attempt in range(3):
        client = Client()
        record = run_query(client, COORDINATORS["a"], sql.format(attempt), headers_for(schema="sf1", session=session))
        client.close()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            status, prediction = post_json(PREDICTORS["a"] + "/v1/predict", {"sql": sql.format(99), "catalog": "tpch", "schema": "sf1"})
            if status == 200 and prediction.get("known") and (prediction.get("requiredWorkers") or 0) >= min_workers:
                print(f"learn: {record.get('query_id')} {record.get('state')}; predictor-a: requiredWorkers={prediction['requiredWorkers']} "
                      f"peak={prediction.get('predictedPeakUserMemoryBytes')} basis={prediction.get('basis')}")
                return prediction
            time.sleep(1)
    sys.exit(f"predictor-a does not know {sql!r} with at least {min_workers} workers")


def cmd_modes(args, out):
    """For each mode on envoy-gated, envoy-gated-bounded and gateway: the session properties, the hold and the reservation.

    Compose has 2 workers for each cluster and no scaler. The light query needs 2 workers, so `bounded` answers at once.
    The heavy query needs 3 workers, so `bounded:<n>` holds for n seconds, then the query runs on 2 workers.
    query_history.session_properties is read afterwards by bench/modes.sh (this container has no PostgreSQL client).
    """
    light = learn_a(LIGHT_SQL, min_workers=2)
    heavy = learn_a(HEAVY_SQL, session=HEAVY_SESSION, min_workers=3)
    if light["requiredWorkers"] != 2:
        sys.exit(f"the light query must need 2 workers: {light}")
    hold_s = args.hold_seconds
    # (path, how the client sets the mode, mode, query, expect the gate properties, expected hold, reservation while it runs)
    cases = [
        ("envoy-gated", "tag", "wait", "light", True, "none", True),
        ("envoy-gated", "tag", "signal", "light", False, "none", True),
        ("envoy-gated", "tag", "bounded:10", "light", False, "ready", True),
        ("envoy-gated-bounded", "header", "wait", "light", True, "none", True),
        ("envoy-gated-bounded", "header", "signal", "light", False, "none", True),
        ("envoy-gated-bounded", "header", "bounded:10", "light", False, "ready", True),
        ("envoy-gated-bounded", "header", f"bounded:{hold_s}", "heavy", False, "time", True),
        # gated keeps its 250 ms time limit: Envoy gives up, the query runs unchanged, the gate deletes the reservation.
        ("envoy-gated", "tag", f"bounded:{hold_s}", "heavy", False, "caller_gone", False),
        ("gateway", "header", "wait", "light", True, "none", True),
        ("gateway", "header", "signal", "light", False, "none", True),
        ("gateway", "tag", "signal", "light", False, "none", True),
        ("envoy-gated-bounded", "none", "", "light", True, "none", True),  # no mode: GATE_DEFAULT_MODE=wait
    ]
    results = []
    for i, (path, how, mode, kind, want_props, want_hold, want_resv) in enumerate(cases):
        sql = (LIGHT_SQL if kind == "light" else HEAVY_SQL).format(1000 + i)
        before_resv, _ = wait_reservations(0)
        before = gate_stats()
        record = run_watched(MODE_PATHS[path], sql, mode_headers(how, mode, HEAVY_SESSION if kind == "heavy" else None))
        after_resv, waited = wait_reservations(0)
        after = gate_stats()
        delta = {k: after.get(k, 0) - before.get(k, 0) for k in after if k.startswith(("hold_", "mode_", "caller_gone", "reservations_deleted", "known"))}
        info = query_info(record.get("query_id"))
        session = info.get("session", {}).get("systemProperties", {})
        stats = info.get("queryStats", {})
        has_props = "required_workers_count" in session
        hold_end = {"ready": "hold_workers_ready", "time": "hold_time_passed", "caller_gone": "hold_caller_gone"}.get(want_hold)
        resv_during = record.get("resv_after_first")
        resv_later = record.get("resv_1500ms", resv_during)
        checks = {
            "finished": record.get("state") == "FINISHED",
            "session properties as expected": has_props == want_props,
            "hold as expected": (delta.get(hold_end, 0) == 1) if hold_end else all(delta.get(k, 0) == 0 for k in delta if k.startswith("hold_") and k != "hold_ms_total"),
            "reservation while it runs": (resv_later >= before_resv + 1) if want_resv else (resv_later == before_resv),
            "no reservation after it": after_resv == 0,
        }
        if want_hold == "time":
            checks["hold about n seconds"] = hold_s * 1000 <= record["t_first_ms"] <= hold_s * 1000 + 2500
        if want_hold in ("ready", "none"):
            checks["answer at once"] = record["t_first_ms"] < 1000
        if want_hold == "caller_gone":
            checks["Envoy time limit 250 ms"] = record["t_first_ms"] < 1000 and delta.get("reservations_deleted", 0) == 1
        ok = all(checks.values())
        results.append(ok)
        line = {
            "kind": "mode-check", "case": i, "path": path, "how": how, "mode": mode or "(none)", "query": kind,
            "expect_properties": want_props, "query_id": record.get("query_id"), "state": record.get("state"),
            "error": record.get("error"), "t_first_ms": record["t_first_ms"], "t_total_ms": record["t_total_ms"],
            "trino_resource_waiting": stats.get("resourceWaitingTime"), "trino_elapsed": stats.get("elapsedTime"),
            "peak": stats.get("peakUserMemoryReservation"), "session": session,
            "reservations": {"before": before_resv, "after_first_request": resv_during, "after_1500ms": resv_later,
                             "after_end": after_resv, "back_to_0_after_s": waited},
            "gate_delta": {k: v for k, v in delta.items() if v}, "checks": checks, "ok": ok,
        }
        out.write(line)
        print(f"{'PASS' if ok else 'FAIL'} [{path} {how} {mode or '(none)'} {kind}] {record.get('query_id')} {record.get('state')} "
              f"first={record['t_first_ms']:.0f}ms total={record['t_total_ms']:.0f}ms trino_wait={stats.get('resourceWaitingTime')} "
              f"props={'yes' if has_props else 'no'} resv {before_resv}->{resv_during}->{resv_later}->{after_resv} "
              f"gate {json.dumps(line['gate_delta'], sort_keys=True)}"
              + ("" if ok else f" failed: {[k for k, v in checks.items() if not v]}"))
    out.write({"kind": "gate_stats", "when": "after modes", "stats": gate_stats()})
    sys.exit(0 if all(results) else 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["smoke", "learn", "run", "w4", "checks", "modes"])
    parser.add_argument("--hold-seconds", type=int, default=8, help="modes: n of bounded:<n> for the heavy query")
    parser.add_argument("--out", default="/results/bench-compose.jsonl")
    parser.add_argument("--env", default="compose")
    parser.add_argument("--run-id", default=time.strftime("%Y%m%dT%H%M%S"))
    parser.add_argument("--workloads", default="W1,W2,W3")
    parser.add_argument("--w1-runs", type=int, default=300)
    parser.add_argument("--w2-runs", type=int, default=100)
    parser.add_argument("--w3-runs", type=int, default=5)
    parser.add_argument("--w3-rows", type=int, default=1_000_000)
    parser.add_argument("--runs", type=int, default=100, help="w4: runs for each path")
    parser.add_argument("--tag", default="", help="w4: label of the gate state")
    args = parser.parse_args()
    socket.setdefaulttimeout(600)
    if args.command == "smoke":
        cmd_smoke(args, None)
        return
    out = Output(args.out, args.env, args.run_id)
    {"learn": cmd_learn, "run": cmd_run, "w4": cmd_w4, "checks": cmd_checks, "modes": cmd_modes}[args.command](args, out)


if __name__ == "__main__":
    main()
