# Compose lab: notes

Parts L2, L3 (compose), L4, L5 (compose) of [LAB-PLAN.md](LAB-PLAN.md). Milestones L2, L3 and L4 are done.
The bench report is [results/BENCH-compose.md](results/BENCH-compose.md).

## Start and stop

```bash
cd poc/memory-history/lab
gate/build.sh            # unit tests + static binary + image trino-memory-history-gate:lab (up.sh runs it if the image is missing)
compose/up.sh            # starts project mhlab, registers the backends, waits until every path answers SELECT 1
bench/run.sh             # about 6 minutes; writes results/bench-compose.jsonl and results/BENCH-compose.md
compose/down.sh          # removes the containers; add -v to also delete the database volume
```

`up.sh` takes about 40 s from zero and is safe to run again (the second time it updates the backends instead of adding them).
It needs the images `trino-memory-history:poc`, `trino-memory-history-predictor:poc` (with `/v1/gate`),
`trinodb/trino-gateway:22`, `envoyproxy/envoy:v1.35.3`, `postgres:17-alpine`, `python:3.13-alpine` and `alpine:3.22`.

## Ports and paths

All host ports are on 127.0.0.1 and are set in `compose/.env`.

| Host port | Service | What it is |
|---|---|---|
| 19080 | coordinator-a | Cluster A (1 coordinator + 2 workers), history database `history_a` |
| 19081 | coordinator-b | Cluster B (1 coordinator + 2 workers), history database `history_b` |
| 19090, 19091 | predictor-a, predictor-b | One predictor for each cluster (`MIN_WORKERS=2`, `MAX_WORKERS=6`, 1 GB per worker) |
| 19095 | gate | Decision service (Contract L2). `/stats` has the counters |
| 19100 | gateway | Trino Gateway, external routing to the gate. Group from `X-Trino-Routing-Group`, default `a` |
| 19101 | gateway-plain | Trino Gateway without routing rules (baseline). Routes by `X-Trino-Routing-Group`, default `a` |
| 19110 | envoy `gated` | ext_authz to the gate on `POST /v1/statement` only, then cluster A |
| 19111 | envoy `plain` | No ext_authz, cluster A |
| 19112 | envoy `first-only` | As `gated`, but the upstream `Host` is `coordinator-a:8080`, so `nextUri` points at the coordinator |
| 19119 | envoy admin | |
| 19432 | postgres | Databases `history_a`, `history_b`, `gateway`. User and password `memoryhistory` |

The Trino nodes use the `jvm.config`, `node.properties` and catalogs of `poc/memory-history/compose/etc` unchanged.
The coordinator and worker configuration are copies of that stack's files, with
`http-server.process-forwarded=true` on the coordinator and a per-cluster `discovery.uri` on the workers.
The listener runs on the coordinators only. The two gateways share the `gateway` database, so one registration
serves both. `monitor.taskDelay` is 5 s (the default is 1 minute). With it, a new backend gets traffic within about 5–8 s.

## Milestone proofs

**L2: two clusters behind `gateway-plain` and behind `envoy-plain`.** From the fresh start of 10:01. The query id
suffix is the coordinator: `xgyxq` = cluster A, `89x9f` = cluster B. The rows are from `query_history` of each cluster.

| Path | Header | Query id | Recorded in |
|---|---|---|---|
| gateway-plain | `X-Trino-Routing-Group: a` | 20261113_100124_00001_xgyxq | `history_a` |
| gateway-plain | `X-Trino-Routing-Group: b` | 20261113_100124_00001_89x9f | `history_b` |
| gateway (gated) | `a` / `b` | 20261113_100118_00000_xgyxq / 20261113_100123_00000_89x9f | `history_a` / `history_b` |
| envoy-plain | — | 20261113_100126_00005_xgyxq | `history_a` |

**L3: the gate adds the properties, and client properties stay.** The bench sent the known W2 query with
`X-Trino-Session: query_max_run_time=10m`. `query_history.session_properties` in `history_a`:

```
20261113_100619_04343_xgyxq (gateway)      {"query_max_run_time":"10m","required_workers_count":"2","required_workers_max_wait_time":"10m"}
20261113_100620_04344_xgyxq (envoy-gated)  {"query_max_run_time":"10m","required_workers_count":"2","required_workers_max_wait_time":"10m"}
20261113_100620_04346_xgyxq (direct)       {"query_max_run_time":"10m"}
```

After the checks, `GET /v1/required-workers` returned `{"workers":2,"reservations":0}` on both predictors after 2 s.
During W2 the gate counted 300 known decisions of 300, and no predictor errors.

**L4:** [results/BENCH-compose.md](results/BENCH-compose.md). All 17 checks pass.

## Answers to the facts that were not verified

I captured the requests that reached coordinator A with `tcpdump` in its network namespace.

- **Header casing.** Trino Gateway sends every header to the backend in lower case. The Trino CLI 484 and the
  Python client: the backend got one `x-trino-session` line with the merged value (client properties first, then the
  two from the gate). So the gateway replaced the client header. A raw client that sends `x-trino-session` in
  lower case: the backend got two lines, the original and the merged one. Trino accepted both and applied the
  merged values. In every case the client properties reached Trino. I checked the result with
  `/v1/query/<id>` for canonical, lower-case and two-line client headers on `gateway` and `envoy-gated`.
  I did not capture the wire for the two-line case.
- **Several entries for one name.** Trino accepts several `X-Trino-Session` lines and a repeated name. The last entry
  wins: `11m` on one line, then `22m,33m` on a second line gave `33m`. The gate still removes a client entry that has
  the same name as a gate property, so the merged value has no duplicates.
- **`excludeHeaders` missing.** With a copy of the gateway configuration without that key, the gateway did not call
  the gate at all (the gate request counter did not change). It wrote no warning or error and routed the query to
  the default group without the properties. So the key is required, and the failure is silent.
- **Gate stopped.** No query failed. Trino Gateway: the first request took the same time as on `gateway-plain`
  (+19 ms over direct). Envoy: the ext_authz call failed at once and `failure_mode_allow` let the request through
  (+0.5 ms, the same as `envoy-plain`).
- **Gate slow (`GATE_DELAY_MS=2000`).** No query failed and no properties were added. Trino Gateway waited for its
  callout timeout: the first request took +1020 ms. So `router.http-client.request-timeout: 1s` in `serverConfig`
  works. Envoy waited for its 250 ms timeout: +252 ms. The gate counts these calls as `caller_gone` and does not ask
  the predictor, so it makes no reservation for them.
- **`nextUri`.** Trino Gateway sets `X-Forwarded-Host`, `-Port` and `-Proto`, so every `nextUri` pointed at the
  gateway. Envoy keeps the client `Host`, so `nextUri` pointed at the listener. On `first-only`, every `nextUri`
  pointed at `coordinator-a:8080` (596 of 596 W1 queries).
- **Envoy ext_authz.** Envoy sends the check with the original method and the body. The gate gets the path
  `/envoy/check/a/v1/statement`. The catalog and schema headers need `allowed_headers` (I allow the prefix `x-trino-`).

## Bench in short (second full run)

Added time against `direct`, median, ms. "noise" = the 95% interval of the difference contains 0.

| Path | W1 total | W1 first request | W2 total | W2 first request | W1 queries/s at 8 clients | W3 rows/s |
|---|---|---|---|---|---|---|
| direct | 14.1 (abs) | 1.0 (abs) | 35.7 (abs) | 0.8 (abs) | 288 | 198,000 |
| gateway-plain | +24.4 | +21.3 | +21.3 | +18.9 | 134 | 175,000 (−12%) |
| gateway | +38.0 | +34.4 | +34.4 | +31.6 | 107 | 176,000 (−12%) |
| envoy-plain | +0.3 (noise) | +0.4 | +1.2 (noise) | +0.5 | 291 | 189,000 (noise) |
| envoy-gated | +13.2 | +12.8 | +13.2 | +12.6 | 163 | 193,000 (noise) |
| envoy-first-only | +12.9 | +12.8 | +12.9 | +12.5 | 193 | 191,000 (noise) |

- The gate costs about 13 ms on the first request. Almost all of it is the predictor: a `POST /v1/predict` takes
  10–12 ms with `curl` on the host, while `/v1/health` takes 1 ms. I did not find out why.
- Trino Gateway itself costs about 20 ms on the first request and a few ms on each later request. I did not find out
  which part of the gateway takes that time.
- Envoy without the gate costs under 1 ms. For W3, the Envoy paths are within the min–max range of direct. The two
  gateway paths are not: they lose about 12% of the throughput.
- `first-only` and `envoy-gated` cost the same for short queries. At 8 clients `first-only` is a little faster
  (193 against 163 queries/s), because the later requests do not go through Envoy.
- The first full run gave the same picture: every latency median was within about 5 ms of the second run, and the added times within about 3 ms.
  Its records are in `results/bench-compose-run1.jsonl`. In that run the gate counted the timed-out calls of the slow
  gate test as predictor errors. I fixed that before the second run.

## Problems and open points

- The first `/v1/gate` call after a predictor starts takes more than 200 ms, so the gate times out on it and the query
  runs without properties. I saw this once for each predictor after every start. A warm-up call would avoid it.
- `SELECT 1` is a known query after its first run, because the listener records every query. So W1 on the gated
  paths also makes a prediction and a reservation. I did not time an unknown query on the gated paths.
- If Envoy or Trino Gateway gives up after the gate called the predictor, the predictor keeps a reservation for a
  query that runs without the properties. The automatic release removes it when a query of that fingerprint ends.
- All workers are fixed at 2 for each cluster (no scaler in compose), and the W2 query needs 2 workers, so Trino never
  waited. The bench does not measure the wait for new workers.
- Both gateways share one database. I did not test one gateway alone with its own database.

## Before and after (2026-11-13): predictor connection pool, cache, warm-up; gate deletes orphan reservations

### Where the 10–12 ms went

I measured on the running lab with the old predictor image (`ad2e21dfa362`), from the host:

| Part | Median |
|---|---|
| `GET /v1/health` (HTTP only) | 1.4 ms |
| `POST /v1/predict` for a text that has no fingerprint (HTTP, JSON, parse, no database) | 1.6 ms |
| `POST /v1/predict` for a fingerprint (all of it) | 14–15 ms |
| `DriverManager.getConnection` (JDBC timing program on the host, same URL and timeouts) | 7.4 ms |
| First history query on that new connection | 4.2 ms |
| The same query on a reused connection | 0.3 ms |
| Fingerprint of one SQL text, warm (in-process) | 0.17 ms |
| JSON read and write, warm (in-process) | 0.04 ms |

So about 11.6 ms of the 13 ms is the new connection for each request: the connection setup (SCRAM-SHA-256 login and
a new PostgreSQL backend) and the first query of a cold backend. The fingerprint and JSON cost under 0.3 ms.
After a restart, the first `/v1/gate` call took 546, 578 and 546 ms (three restarts).

### What I changed

- Predictor: a fixed pool of up to 17 connections (16 predictions + 1 for the automatic release), written with the JDK.
  The timeouts and the 16-request limit stay. When a query fails on a pooled connection that is broken
  (SQLSTATE class 08 or 57P, or the connection is not valid), the pool closes all idle connections and runs the
  read once more on a new connection.
- Predictor: a warm-up before the port listens. It opens 8 connections, runs the history query on each, runs the
  prediction 100 times and sends 20 `/v1/predict` and `/v1/gate` requests to a temporary server on a free loopback
  port (with its own reservations, which are dropped). If the database is down, the warm-up logs a warning and the
  predictor starts anyway. The start (to the first healthy answer) is now about 1.6 s instead of 0.5 s.
- Predictor: a cache of the history rows for each fingerprint. `HISTORY_CACHE_TTL_MS`, default 2000, `0` turns it off.
  Staleness: a prediction can miss the rows that were written in the last 2 s. So a new fingerprint stays unknown,
  and a new run (also a memory failure) changes the prediction, up to 2 s later than before. The automatic release
  and the reservations do not use the cache. Failed reads are not cached.
- Gate: the call to the predictor no longer stops when the caller goes away (the 200 ms predictor timeout still
  applies). If the caller is gone when the answer arrives and the answer has a `reservationId`, the gate sends
  `DELETE /v1/reservations/{id}` (best effort). New `/stats` counters: `reservations_deleted`,
  `reservation_delete_errors`. The other counters do not change.
  I changed the existing test `TestCallerGoneIsNotAPredictorError`: it expected no predictor call. Now the gate may
  call the predictor, depending on when the server sees the closed connection, and must then delete the reservation.

### Numbers

Added time against `direct`, median, ms. Before = [results/BENCH-compose.md](results/BENCH-compose.md).
After = [results/BENCH-compose-after.md](results/BENCH-compose-after.md) (second run), run 1 in
`results/bench-compose-after-run1.jsonl`. Both after-runs were on a fresh lab.

| | Before | After, run 1 | After, run 2 |
|---|---|---|---|
| W1 first request, `envoy-gated` | +12.8 | +2.3 | +2.2 |
| W2 first request, `envoy-gated` | +12.6 | +2.2 | +2.2 |
| W1 first request, `gateway` | +34.4 | +24.3 | +24.3 |
| W2 first request, `gateway` | +31.6 | +21.6 | +21.6 |
| W1 queries/s at 8 clients, `envoy-gated` | 163 | 252 | 308 |
| W1 queries/s at 8 clients, `gateway` | 107 | 119 | 110 |
| W1 queries/s at 8 clients, `direct` | 288 | 296 | 309 |
| First `/v1/gate` after a predictor restart (curl on the host, 3 restarts) | 546–578 ms | 5–29 ms | |
| Gate predictor timeouts during `up.sh` | 1 per predictor | 0 | 0 |

- The first-request numbers are stable: the two after-runs agree within 0.1 ms, and the intervals do not overlap
  with the baseline. The gate now adds about 2.2 ms on Envoy, and the gateway path about 2.7 ms over `gateway-plain`.
- The queries per second at 8 clients are not stable. Another lab ran on the host (load average up to 15).
  `envoy-plain`, which I did not change, gave 291 before and 262 and 188 after. So I only say that `envoy-gated`
  at 8 clients is now in the range of `envoy-plain`; the `gateway` change is within noise.
- With `/v1/predict` from the host: cache hit 1.9 ms, cache miss on a pooled connection 2.8 ms, health 1.1 ms.
- In W4 with `GATE_DELAY_MS=2000`, the gate deleted 76 (run 1) and 73 (run 2) reservations of callers that had gone.
  Before the change those reservations stayed until a query of the same fingerprint ended or the time limit passed.
  All 17 checks pass in both runs, also "reservations back to 0".

### Index on `query_history`

The listener creates `(fingerprint, end_time DESC)`. The prediction query uses it. The automatic-release query
(`WHERE end_time >= ? ORDER BY end_time, query_id`) cannot use it, because `end_time` is not the first column.
With a temporary copy of the table with 200,000 rows, `EXPLAIN ANALYZE` showed a sequential scan of all rows,
48 ms for each run. The predictor runs it every 2 s. An index on `(end_time)` would fix it. I did not change the
listener.

### Not checked

- A gate timeout (200 ms) can still leave a reservation: the predictor makes it, but the gate never gets the id. A fix
  needs a contract change (for example, the gate sends the id to `/v1/gate`), so I did not make it.
- I did not restart PostgreSQL in the lab. The pool recovery is tested with `pg_terminate_backend` on every backend.
- The cache has a fixed bound of 10,000 fingerprints. I did not measure its memory.
- Concurrent cache misses for one fingerprint each read the database. I did not measure a burst of new fingerprints.
- I did not measure the gate path on kind.
