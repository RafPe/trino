# Lab: a transparent gate, and what it costs

The PoC gate is a script that the client runs. This lab puts the gate on the server side, so that each
client gets it without a change. The lab also measures the time that each design adds to a query.

- Plan and contracts: [LAB-PLAN.md](LAB-PLAN.md)
- Compose lab, notes and proofs: [NOTES-compose.md](NOTES-compose.md)
- kind lab, notes and proofs: [NOTES-k8s.md](NOTES-k8s.md)
- Bench reports: [results/BENCH-compose.md](results/BENCH-compose.md) (before the predictor change),
  [results/BENCH-compose-after.md](results/BENCH-compose-after.md), [results/BENCH-kind.md](results/BENCH-kind.md)

## What the lab contains

```
                         ┌── Trino Gateway (external routing) ──────────────┐
client / bench ──────────┤                                                  ├──▶ Trino cluster A
                         ├── Envoy (asks the gate on the first request) ────┘
                         └── direct ───────────────────────────────────────────▶ Trino cluster A or B

Trino Gateway or Envoy ──▶ gate ──▶ predictor of the cluster ──▶ history database of the cluster
```

| Part | What it does |
|---|---|
| Two Trino clusters (A, B) | Stock Trino 484 with the history listener. Each cluster has its own history database and its own predictor. |
| Predictor | New call `POST /v1/gate`: it predicts and makes the reservation in one call. It releases the reservation when the history shows that the query ended. |
| Gate | A small Go service. Trino Gateway and Envoy call it for the first request of a query. It calls the predictor and returns the session properties to add. If the predictor is slow or stopped, the query runs unchanged. |
| Trino Gateway | Two instances: one with external routing to the gate, one without rules (baseline). |
| Envoy | Three listeners: with the gate, without the gate (baseline), and one where only the first request of a query goes through Envoy. |
| Bench | Sends the same queries on six paths and reports the time. |

## How to run it

Build the images first (`docker/build-images.sh` in the parent folder, then `lab/gate/build.sh`).

**Docker compose** (two workers for each cluster, no scaling):

```bash
cd poc/memory-history/lab
compose/up.sh        # about 40 s
bench/run.sh         # writes results/bench-compose.jsonl and results/BENCH-compose.md
compose/down.sh
```

**kind** (Trino from the Helm chart, KEDA scales each cluster):

```bash
cd poc/memory-history/lab/k8s
./up.sh              # about 4 minutes the first time
./status.sh
./demo-heavy.sh      # a heavy known query on each path. Only the right cluster scales.
./bench-kind.sh      # writes results/bench-kind.jsonl and results/BENCH-kind.md
./down.sh            # deletes only the kind cluster "mhlab"
```

The kind lab uses its own kubeconfig file (`k8s/.kubeconfig`). It does not change the default kubeconfig.

## Results

All numbers are from one host. On kind, all pods run on one node. Read them as a comparison between the
paths, not as absolute numbers.

### The gate works on the server side

- A known query that goes through Trino Gateway or through Envoy gets `required_workers_count` and `required_workers_max_wait_time`.
- Session properties that the client sets stay. I tested three forms of the `X-Trino-Session` header.
- The reservation is released when the query ends. The client does nothing for this.
- On kind, a heavy known query for cluster A made only cluster A scale. Cluster B stayed at 2 workers. Trino held the query for 22 to 26 seconds while the workers joined.

### Time added to a query (kind, median)

"First request" is the `POST /v1/statement` request. The other requests of a query do not call the gate.

| Path | Short query (`SELECT 1`), total | First request | Queries each second, 8 clients | Large result (1 million rows) |
|---|---|---|---|---|
| Direct | 10.7 ms | 0.9 ms | 300 | 189,561 rows/s |
| Envoy, no gate | +0.8 ms | +0.5 ms | 293 | no measurable change |
| Envoy with the gate | +2.6 ms | +2.1 ms | 251 | no measurable change |
| Envoy, first request only | +2.2 ms | +2.1 ms | 262 | no measurable change |
| Trino Gateway, no rules | +22.6 ms | +19.6 ms | 109 | 11.2% more time |
| Trino Gateway with the gate | +25.5 ms | +22.5 ms | 100 | 9.2% more time |

- Envoy adds less than 1 ms. The gate adds approximately 2 ms more, one time for each query.
- Trino Gateway adds approximately 20 ms for each query without the gate. I did not find the cause.

### What made the gate fast

In the first bench the gate added 13 ms. The predictor opened a new database connection for each request.
The connection cost 7.4 ms and its first query cost 4.2 ms. With connections that stay open, a warm start
and a short cache, the same call costs approximately 2 ms. Details: [NOTES-compose.md](NOTES-compose.md),
section "Before and after".

### When the gate is slow or stopped

No query failed in these tests. The queries ran without the gate properties.

| Condition | Envoy with the gate | Trino Gateway with the gate |
|---|---|---|
| Gate answers after 2 s | +252 ms (the Envoy time limit is 250 ms) | +1020 ms (the Gateway time limit is 1 s) |
| Gate stopped, compose | +0.5 ms | the same as without rules |
| Gate stopped, kind | +208 ms median | +1021 ms median for `SELECT 1` |

On kind, a stopped gate does not always fail fast. Many calls wait for the full time limit. So a gate that
is not available makes each query slower by the time limit. I did not find the cause.

## What I learned for a real system

1. **Use a proxy that only asks for the first request.** Envoy sends all other requests straight through. The result data does not go through code of the gate.
2. **Keep the decision call short.** Reuse connections, cache known query shapes, and set a time limit of some milliseconds.
3. **Fail open, and fail fast.** A time limit protects the query, but each query pays the time limit while the gate is not available. Run two or more copies of the gate, and make the proxy stop calls to a gate that does not answer.
4. **Set the Trino Gateway time limit.** The default for the routing call is 5 minutes.
5. **Set `excludeHeaders` in Trino Gateway.** If the key is not there, Trino Gateway does not call the routing service, and it writes no message.
6. **Return the merged session header.** Trino Gateway replaces the session header of the client with the header from the routing service.

## Open problems

- **A reservation can stay after a time limit.** If the gate stops its wait for the predictor, it does not know the reservation id, so it cannot delete the reservation. The reservation stays until a query of the same shape ends, or for one hour.
- **A stopped gate is slow on kind** (see above).
- **The release check reads the full history table** each 2 seconds, because the table has no index on `end_time`. On a copy with 200,000 rows this took 48 ms.
- **Not tested:** both clusters scale at the same time, more than one copy of the gate, a restart of Envoy or Trino Gateway during a query, TLS, authentication.
