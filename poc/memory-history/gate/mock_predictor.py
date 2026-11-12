#!/usr/bin/env python3
"""TEST ONLY: mock of the predictor HTTP API (Contract 3) with canned answers.

It is NOT the real predictor. It does not read history and does not compute a
fingerprint. It exists so that gate.py and scaler.py can be tested before the
real predictor image exists.

Canned answers:
  --known-workers N   every SQL that contains --match (default: any SQL) is
                      "known" and needs N workers; other SQL is "unknown".
Reservations are kept in memory with a TTL, and /v1/required-workers applies
the Contract 3 formula with --per-worker-bytes, --min-workers, --max-workers,
--safety-factor.

  python mock_predictor.py --port 18090 --known-workers 5 --match catalog_returns
"""

import argparse
import hashlib
import json
import math
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ARGS = None
RESERVATIONS = {}  # id -> (predicted_bytes, expires_at)
LOCK = threading.Lock()


def clamp(value, low, high):
    return max(low, min(high, value))


def workers_for(total_bytes):
    return clamp(math.ceil(total_bytes * ARGS.safety_factor / ARGS.per_worker_bytes), ARGS.min_workers, ARGS.max_workers)


def predict(body):
    sql = body.get("sql") or ""
    if not sql.strip():
        return {"supported": False, "known": False}
    fingerprint = hashlib.sha256(sql.encode()).hexdigest()
    if ARGS.known_workers is None or (ARGS.match and ARGS.match not in sql):
        return {"supported": True, "fingerprint": fingerprint, "known": False, "runs": 0, "successfulRuns": 0, "memoryFailures": 0,
                "predictedPeakUserMemoryBytes": None, "predictedPeakTaskUserMemoryBytes": None, "requiredWorkers": None, "basis": None}
    # Pick a predicted peak that maps to exactly --known-workers.
    predicted = int(ARGS.known_workers * ARGS.per_worker_bytes / ARGS.safety_factor) - 1
    return {"supported": True, "fingerprint": fingerprint, "known": True, "runs": 1, "successfulRuns": 0, "memoryFailures": 1,
            "predictedPeakUserMemoryBytes": predicted, "predictedPeakTaskUserMemoryBytes": predicted // ARGS.known_workers,
            "requiredWorkers": workers_for(predicted), "basis": "mock"}


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, payload=None):
        data = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        if payload is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self):
        if self.path == "/v1/health":
            return self._send(200, {"status": "ok"})
        if self.path == "/v1/required-workers":
            now = time.time()
            with LOCK:
                for rid in [r for r, (_, exp) in RESERVATIONS.items() if exp <= now]:
                    del RESERVATIONS[rid]
                total = sum(b for b, _ in RESERVATIONS.values())
                count = len(RESERVATIONS)
            workers = workers_for(total) if count else ARGS.min_workers
            return self._send(200, {"workers": workers, "reservations": count})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/v1/predict":
            return self._send(200, predict(self._body()))
        if self.path == "/v1/reservations":
            body = self._body()
            with LOCK:
                RESERVATIONS[body["id"]] = (int(body.get("predictedBytes") or 0), time.time() + int(body.get("ttlSeconds") or 900))
            return self._send(201, {"id": body["id"]})
        self._send(404, {"error": "not found"})

    def do_DELETE(self):
        if self.path.startswith("/v1/reservations/"):
            with LOCK:
                RESERVATIONS.pop(self.path.rsplit("/", 1)[1], None)
            return self._send(204)
        self._send(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        print("mock-predictor: " + fmt % args, flush=True)


def main():
    global ARGS
    p = argparse.ArgumentParser(description="TEST ONLY mock predictor")
    p.add_argument("--port", type=int, default=18090)
    p.add_argument("--known-workers", type=int, default=None)
    p.add_argument("--match", default=None, help="only SQL containing this text is known")
    p.add_argument("--per-worker-bytes", type=int, default=1073741824)
    p.add_argument("--min-workers", type=int, default=2)
    p.add_argument("--max-workers", type=int, default=6)
    p.add_argument("--safety-factor", type=float, default=1.2)
    ARGS = p.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    print(f"mock-predictor (TEST ONLY) listening on 127.0.0.1:{ARGS.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
