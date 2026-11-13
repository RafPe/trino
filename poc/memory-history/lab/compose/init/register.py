"""Registers cluster A and cluster B in Trino Gateway, then waits until both gateways route to them.

Both gateways share one database, so one registration serves both.
Standard library only (runs in python:3.13-alpine).
"""
import json
import sys
import time
import urllib.error
import urllib.request

BACKENDS = [
    {"name": "cluster-a", "proxyTo": "http://coordinator-a:8080", "externalUrl": "http://coordinator-a:8080", "routingGroup": "a", "active": True},
    {"name": "cluster-b", "proxyTo": "http://coordinator-b:8080", "externalUrl": "http://coordinator-b:8080", "routingGroup": "b", "active": True},
]
GATEWAYS = ["http://gateway:8080", "http://gateway-plain:8080"]
DEADLINE = time.monotonic() + 300


def request(method, url, body=None, headers=None, timeout=10):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if body is not None and "Content-Type" not in req.headers:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.status, response.read()


def wait(what, check):
    while True:
        try:
            if check():
                return
        except (urllib.error.URLError, OSError, ValueError):
            pass
        if time.monotonic() > DEADLINE:
            sys.exit(f"timeout: {what}")
        time.sleep(1)


def run_select_1(gateway, group):
    """Runs SELECT 1 through the gateway and returns the coordinator that ran it."""
    headers = {"X-Trino-User": "init", "X-Trino-Routing-Group": group, "X-Trino-Source": "mhlab-init"}
    _, body = request("POST", gateway + "/v1/statement", b"SELECT 1", headers)
    result = json.loads(body)
    while "nextUri" in result:
        _, body = request("GET", result["nextUri"], headers=headers)
        result = json.loads(body)
    if result.get("stats", {}).get("state") != "FINISHED":
        raise ValueError(f"query {result.get('id')} ended in {result.get('stats', {}).get('state')}: {result.get('error', {}).get('message')}")
    return result["id"]


for gateway in GATEWAYS:
    wait(f"{gateway} ready", lambda: request("GET", gateway + "/trino-gateway/readyz")[0] == 200)

existing = {b["name"] for b in json.loads(request("GET", GATEWAYS[0] + "/gateway/backend/all")[1])}
for backend in BACKENDS:
    action = "update" if backend["name"] in existing else "add"
    status, _ = request("POST", f"{GATEWAYS[0]}/gateway/backend/modify/{action}", backend)
    print(f"{action} {backend['name']} -> {status}")

for gateway in GATEWAYS:
    for group in ("a", "b"):
        started = time.monotonic()
        holder = {}
        wait(f"{gateway} group {group}", lambda: holder.setdefault("id", run_select_1(gateway, group)))
        print(f"{gateway} group {group}: query {holder['id']} after {time.monotonic() - started:.1f}s")
print("init done")
