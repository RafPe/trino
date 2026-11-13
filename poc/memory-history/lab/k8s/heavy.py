"""Helpers for demo-heavy.sh and failopen-check.sh. They run in the bench pod. Standard library only.

  python /bench/heavy.py query --url URL --coordinator URL [--group G] [--catalog C] [--schema S]
                               [--session "a=b,c=d"] [--tag LABEL]  < query.sql
      Runs one query with the REST protocol and prints one JSON line: query id, state, error,
      client times, and from GET /v1/query/<id> on the coordinator: the times (create, execution
      start, end), the time it waited for workers, the peak memory and the session properties.

  python /bench/heavy.py sample
      Prints one JSON line: active workers in Trino and the predictor value, for cluster a and b.

  python /bench/heavy.py predict --predictor URL --catalog C --schema S < query.sql
      POST /v1/predict (no reservation). Prints the answer.
"""
import argparse
import http.client
import json
import sys
import time
import urllib.parse

COORDINATORS = {"a": "http://trino-a:8080", "b": "http://trino-b:8080"}
PREDICTORS = {"a": "http://predictor-a:8090", "b": "http://predictor-b:8090"}
NODES_SQL = "SELECT count(*) FROM system.runtime.nodes WHERE coordinator = false AND state = 'active'"


def request(method, url, body=None, headers=None, timeout=900):
    parsed = urllib.parse.urlsplit(url)
    connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=timeout)
    try:
        target = parsed.path + ("?" + parsed.query if parsed.query else "")
        connection.request(method, target, body=body, headers=headers or {})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def run(url, sql, headers):
    """Returns (final result document, rows, seconds of the first request, seconds in total)."""
    start = time.time()
    status, body = request("POST", url + "/v1/statement", sql.encode(), headers)
    first = time.time() - start
    if status != 200:
        raise RuntimeError(f"HTTP {status}: {body[:300]!r}")
    result = json.loads(body)
    rows = 0
    follow = {"X-Trino-User": headers["X-Trino-User"]}
    while True:
        rows += len(result.get("data") or [])
        if not result.get("nextUri"):
            break
        status, body = request("GET", result["nextUri"], headers=follow)
        if status != 200:
            raise RuntimeError(f"HTTP {status} on nextUri: {body[:300]!r}")
        result = json.loads(body)
    return result, rows, first, time.time() - start


def gib(value):
    """/v1/query gives a data size as a string with a unit, for example "3586361080B"."""
    units = {"B": 1, "kB": 2**10, "MB": 2**20, "GB": 2**30, "TB": 2**40}
    if isinstance(value, str):
        for unit in sorted(units, key=len, reverse=True):
            if value.endswith(unit):
                try:
                    return round(float(value[:-len(unit)]) * units[unit] / 2**30, 2)
                except ValueError:
                    return value
    return value


def cmd_query(args):
    sql = sys.stdin.read().strip().rstrip(";")
    headers = {"X-Trino-User": "lab", "X-Trino-Source": "mhlab-heavy", "X-Trino-Catalog": args.catalog,
               "X-Trino-Schema": args.schema, "Content-Type": "text/plain"}
    if args.group:
        headers["X-Trino-Routing-Group"] = args.group
    if args.session:
        headers["X-Trino-Session"] = args.session
    submitted = time.time()
    out = {"tag": args.tag, "url": args.url, "group": args.group, "submitted": time.strftime("%H:%M:%S", time.gmtime(submitted))}
    try:
        result, rows, first, total = run(args.url, sql, headers)
        out.update(query_id=result.get("id"), state=result.get("stats", {}).get("state"), rows=rows,
                   client_first_s=round(first, 3), client_total_s=round(total, 1))
        if result.get("error"):
            out["error"] = f"{result['error'].get('errorName')}: {result['error'].get('message', '')[:160]}"
    except (OSError, RuntimeError, ValueError, http.client.HTTPException) as e:
        out.update(state="CLIENT_ERROR", error=f"{type(e).__name__}: {e}")
        print(json.dumps(out))
        return
    status, body = request("GET", f"{args.coordinator}/v1/query/{out['query_id']}", headers={"X-Trino-User": "lab"}, timeout=30)
    if status == 200:
        info = json.loads(body)
        stats = info.get("queryStats", {})
        out.update(
            create_time=stats.get("createTime"),
            execution_start_time=stats.get("executionStartTime"),
            end_time=stats.get("endTime"),
            queued_time=stats.get("queuedTime"),
            resource_waiting_time=stats.get("resourceWaitingTime"),
            elapsed_time=stats.get("elapsedTime"),
            execution_time=stats.get("executionTime"),
            peak_user_memory_gib=gib(stats.get("peakUserMemoryReservation")),
            session=info.get("session", {}).get("systemProperties", {}),
        )
    else:
        out["query_info"] = f"HTTP {status}"
    print(json.dumps(out))


def scalar(base, sql):
    _, body = request("POST", base + "/v1/statement", sql.encode(), {"X-Trino-User": "lab-sampler", "X-Trino-Source": "mhlab-sampler"}, timeout=10)
    result = json.loads(body)
    value = None
    while True:
        if result.get("data"):
            value = result["data"][0][0]
        if not result.get("nextUri"):
            return value
        _, body = request("GET", result["nextUri"], headers={"X-Trino-User": "lab-sampler"}, timeout=10)
        result = json.loads(body)


def cmd_sample(args):
    out = {}
    for c in ("a", "b"):
        try:
            active = scalar(COORDINATORS[c], NODES_SQL)
        except (OSError, ValueError, http.client.HTTPException):
            active = None
        try:
            predictor = json.loads(request("GET", PREDICTORS[c] + "/v1/required-workers", timeout=5)[1])
        except (OSError, ValueError, http.client.HTTPException):
            predictor = {}
        out[c] = {"active": active, "predictor": predictor.get("workers"), "reservations": predictor.get("reservations")}
    print(json.dumps(out))


def cmd_predict(args):
    sql = sys.stdin.read().strip().rstrip(";")
    payload = json.dumps({"sql": sql, "catalog": args.catalog, "schema": args.schema}).encode()
    status, body = request("POST", args.predictor + "/v1/predict", payload, {"Content-Type": "application/json"}, timeout=10)
    print(body.decode())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["query", "sample", "predict"])
    parser.add_argument("--url")
    parser.add_argument("--coordinator")
    parser.add_argument("--predictor")
    parser.add_argument("--group")
    parser.add_argument("--catalog", default="tpch")
    parser.add_argument("--schema", default="sf5")
    parser.add_argument("--session")
    parser.add_argument("--tag", default="")
    args = parser.parse_args()
    {"query": cmd_query, "sample": cmd_sample, "predict": cmd_predict}[args.command](args)


if __name__ == "__main__":
    main()
