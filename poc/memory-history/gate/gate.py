#!/usr/bin/env python3
"""Client-side gate for the memory-history PoC (Contract 4).

Asks the predictor how much memory a query needs. When the query is known and
needs more workers than the cluster has, it creates a reservation (the scaler
reads it) and submits the query with `required_workers_count`, so Trino holds
the query until enough workers are active. Prints one JSON line per query.

Usage:
  gate.py --server http://localhost:18080 --predictor http://localhost:18090 \
          --catalog tpcds --schema sf10 [--user poc] [--no-gate] [--tag k=v ...] \
          [--session name=value ...] (-f query.sql | -e "sql")

Importable: `run_query(...)` returns the same dict that the CLI prints.
"""

import argparse
import json
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from urllib.parse import urlparse

import requests
import trino

DEFAULT_SERVER = "http://localhost:18080"
DEFAULT_PREDICTOR = "http://localhost:18090"
# Contract 5: session properties used for every workload query.
DEFAULT_SESSION_PROPERTIES = {
    "join_distribution_type": "PARTITIONED",
    "task_concurrency": "4",
}
MAX_WAIT = "10m"
RESERVATION_TTL_SECONDS = 900
HTTP_TIMEOUT = 10

_SIZE_UNITS = {"B": 1, "kB": 1024, "MB": 1024**2, "GB": 1024**3, "TB": 1024**4, "PB": 1024**5}
_DURATION_UNITS = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1e3, "m": 60e3, "h": 3600e3, "d": 86400e3}


def parse_data_size(value):
    """Airlift DataSize string ("1.5GB") -> bytes."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value)
    m = re.fullmatch(r"\s*([\d.]+)\s*([a-zA-Z]+)\s*", value)
    if not m:
        return None
    return int(float(m.group(1)) * _SIZE_UNITS[m.group(2)])


def parse_duration_ms(value):
    """Airlift Duration string ("1.50s") -> milliseconds."""
    if value is None:
        return None
    m = re.fullmatch(r"\s*([\d.]+)\s*([a-z]+)\s*", value)
    if not m:
        return None
    return int(float(m.group(1)) * _DURATION_UNITS[m.group(2)])


def _connect(server, user, catalog, schema, session_properties=None, client_tags=None):
    url = urlparse(server)
    return trino.dbapi.connect(
        host=url.hostname,
        port=url.port or (443 if url.scheme == "https" else 80),
        http_scheme=url.scheme,
        user=user,
        catalog=catalog,
        schema=schema,
        source="memory-history-gate",
        session_properties=session_properties or {},
        client_tags=client_tags or [],
    )


def active_workers(server, user="poc"):
    conn = _connect(server, user, "system", "runtime")
    try:
        cur = conn.cursor()
        cur.execute("SELECT count(*) FROM system.runtime.nodes WHERE coordinator = false AND state = 'active'")
        return int(cur.fetchall()[0][0])
    finally:
        conn.close()


def query_info(server, query_id, user="poc"):
    """Actual result of a query from the Trino REST API (GET /v1/query/{id})."""
    try:
        r = requests.get(f"{server}/v1/query/{query_id}", headers={"X-Trino-User": user}, timeout=HTTP_TIMEOUT)
        r.raise_for_status()
        info = r.json()
    except (requests.RequestException, ValueError) as e:
        return {"error": f"query info unavailable: {e}"}
    stats = info.get("queryStats", {})
    error_code = info.get("errorCode") or {}
    return {
        "state": info.get("state"),
        "error_code": error_code.get("name"),
        "error_type": error_code.get("type") or info.get("errorType"),
        "peak_user_memory_bytes": parse_data_size(stats.get("peakUserMemoryReservation")),
        "peak_task_user_memory_bytes": parse_data_size(stats.get("peakTaskUserMemory")),
        "peak_task_total_memory_bytes": parse_data_size(stats.get("peakTaskTotalMemory")),
        "elapsed_ms": parse_duration_ms(stats.get("elapsedTime")),
        "execution_ms": parse_duration_ms(stats.get("executionTime")),
        "queued_ms": parse_duration_ms(stats.get("queuedTime")),
        "resource_waiting_ms": parse_duration_ms(stats.get("resourceWaitingTime")),
        "cpu_ms": parse_duration_ms(stats.get("totalCpuTime")),
        "physical_input_rows": stats.get("physicalInputPositions"),
    }


def predict(predictor, sql, catalog, schema):
    r = requests.post(f"{predictor}/v1/predict", json={"sql": sql, "catalog": catalog, "schema": schema}, timeout=HTTP_TIMEOUT)
    r.raise_for_status()
    return r.json()


def run_query(
        sql,
        *,
        server=DEFAULT_SERVER,
        predictor=DEFAULT_PREDICTOR,
        catalog="tpcds",
        schema="sf10",
        user="poc",
        gate=True,
        tags=(),
        session_properties=None,
        max_wait=MAX_WAIT):
    """Run one query, optionally through the gate. Returns a JSON-serializable dict."""
    sql = sql.strip().rstrip(";").strip()
    session = dict(DEFAULT_SESSION_PROPERTIES)
    session.update(session_properties or {})
    result = {
        "ts": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "catalog": catalog,
        "schema": schema,
        "gated": gate,
        "tags": sorted(tags),
        "query_id": None,
        "state": None,
        "error_code": None,
        "prediction": None,
        "required_workers": None,
        "reservation_id": None,
        "workers_before": None,
        "workers_after": None,
        "wait_ms": None,
    }

    result["workers_before"] = active_workers(server, user)

    reservation_id = None
    if gate:
        try:
            prediction = predict(predictor, sql, catalog, schema)
        except (requests.RequestException, ValueError) as e:
            prediction = {"error": f"predictor unavailable: {e}"}
        result["prediction"] = prediction
        required = prediction.get("requiredWorkers")
        if prediction.get("known") and required is not None and required > result["workers_before"]:
            reservation_id = str(uuid.uuid4())
            try:
                r = requests.post(
                    f"{predictor}/v1/reservations",
                    json={"id": reservation_id, "predictedBytes": prediction.get("predictedPeakUserMemoryBytes"), "ttlSeconds": RESERVATION_TTL_SECONDS},
                    timeout=HTTP_TIMEOUT)
                r.raise_for_status()
            except requests.RequestException as e:
                print(f"warning: reservation failed: {e}", file=sys.stderr)
            session["required_workers_count"] = str(required)
            session["required_workers_max_wait_time"] = max_wait
            result["required_workers"] = required
            result["reservation_id"] = reservation_id

    started = time.monotonic()
    conn = _connect(server, user, catalog, schema, session, sorted(tags))
    try:
        cur = conn.cursor()
        try:
            cur.execute(sql)
            rows = cur.fetchall()
            result["rows"] = len(rows)
            result["query_id"] = cur.query_id
        except trino.exceptions.TrinoQueryError as e:
            result["query_id"] = e.query_id
            result["error_code"] = e.error_name
            result["error_message"] = (e.message or "")[:300]
    finally:
        conn.close()
        if reservation_id is not None:
            try:
                requests.delete(f"{predictor}/v1/reservations/{reservation_id}", timeout=HTTP_TIMEOUT)
            except requests.RequestException as e:
                print(f"warning: reservation delete failed: {e}", file=sys.stderr)
    result["client_wall_ms"] = int((time.monotonic() - started) * 1000)

    if result["query_id"]:
        actual = query_info(server, result["query_id"], user)
        result["actual"] = actual
        result["state"] = actual.get("state")
        result["error_code"] = actual.get("error_code") or result["error_code"]
        result["wait_ms"] = actual.get("resource_waiting_ms")
    else:
        result["state"] = "FAILED"
    result["workers_after"] = active_workers(server, user)
    return result


def _key_value(text):
    if "=" not in text:
        raise argparse.ArgumentTypeError(f"expected name=value, got: {text}")
    return tuple(text.split("=", 1))


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--server", default=DEFAULT_SERVER)
    p.add_argument("--predictor", default=DEFAULT_PREDICTOR)
    p.add_argument("--catalog", default="tpcds")
    p.add_argument("--schema", default="sf10")
    p.add_argument("--user", default="poc")
    p.add_argument("--no-gate", action="store_true", help="submit the query unchanged")
    p.add_argument("--tag", action="append", default=[], type=_key_value, help="client tag k=v (repeatable)")
    p.add_argument("--session", action="append", default=[], type=_key_value, help="extra session property name=value (repeatable)")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("-f", "--file")
    src.add_argument("-e", "--execute")
    args = p.parse_args(argv)

    sql = open(args.file).read() if args.file else args.execute
    result = run_query(
        sql,
        server=args.server.rstrip("/"),
        predictor=args.predictor.rstrip("/"),
        catalog=args.catalog,
        schema=args.schema,
        user=args.user,
        gate=not args.no_gate,
        tags=[f"{k}={v}" for k, v in args.tag],
        session_properties=dict(args.session))
    print(json.dumps(result, sort_keys=True))
    return 0 if result["state"] == "FINISHED" else 1


if __name__ == "__main__":
    sys.exit(main())
