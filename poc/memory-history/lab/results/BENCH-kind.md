# Bench: kind

Times in ms. total = POST until the last page is read. first = the POST /v1/statement request only.
Δp50 = median of the path − median of direct, with a 95% bootstrap interval. "within noise" = the interval contains 0 for total and first.

### W1: SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 300 | 0 | 10.7 | 11.6 | 15.4 | — | 0.9 | 1.0 | 1.2 | — | — |
| gateway-plain | 300 | 0 | 33.3 | 37.7 | 41.7 | +22.6 [+22.3, +23.0] | 20.5 | 23.6 | 26.8 | +19.6 [+19.5, +19.7] | no |
| gateway | 300 | 0 | 36.1 | 40.1 | 44.1 | +25.5 [+25.2, +25.8] | 23.4 | 26.4 | 27.6 | +22.5 [+22.3, +22.8] | no |
| envoy-plain | 300 | 0 | 11.5 | 12.4 | 14.1 | +0.8 [+0.7, +0.9] | 1.3 | 1.5 | 1.9 | +0.5 [+0.4, +0.5] | no |
| envoy-gated | 300 | 0 | 13.2 | 14.7 | 17.6 | +2.6 [+2.4, +2.7] | 3.0 | 3.6 | 4.2 | +2.1 [+2.1, +2.1] | no |
| envoy-first-only | 300 | 0 | 12.9 | 14.5 | 16.1 | +2.2 [+2.1, +2.4] | 2.9 | 3.7 | 5.2 | +2.1 [+2.0, +2.1] | no |

### W1: SELECT 1, 8 at the same time

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 296 | 0 | 24.3 | 35.7 | 47.2 | — | 2.8 | 6.3 | 9.0 | — | — |
| gateway-plain | 296 | 0 | 70.8 | 97.6 | 103.3 | +46.5 [+45.0, +50.3] | 36.8 | 63.4 | 74.8 | +34.0 [+32.1, +36.8] | no |
| gateway | 296 | 0 | 81.2 | 102.0 | 108.0 | +56.9 [+53.6, +59.2] | 39.2 | 73.9 | 81.8 | +36.4 [+33.7, +44.3] | no |
| envoy-plain | 296 | 0 | 25.1 | 35.4 | 44.6 | +0.8 [-0.1, +1.7] | 3.1 | 6.1 | 7.2 | +0.3 [+0.0, +0.7] | total within noise |
| envoy-gated | 296 | 0 | 28.2 | 49.1 | 74.8 | +3.9 [+2.7, +5.3] | 4.9 | 8.9 | 10.4 | +2.2 [+1.8, +2.5] | no |
| envoy-first-only | 296 | 0 | 26.6 | 41.6 | 103.0 | +2.3 [+0.8, +3.6] | 5.0 | 8.2 | 11.1 | +2.2 [+1.9, +2.5] | no |

| Path | queries | wall s | queries/s |
|---|---|---|---|
| direct | 296 | 1.0 | 300.2 |
| gateway-plain | 296 | 2.7 | 108.9 |
| gateway | 296 | 3.0 | 100.2 |
| envoy-plain | 296 | 1.0 | 292.6 |
| envoy-gated | 296 | 1.2 | 250.9 |
| envoy-first-only | 296 | 1.1 | 261.8 |

### W2: known query (count(*) on tpch.tiny.orders), in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 100 | 0 | 33.2 | 43.1 | 55.8 | — | 0.8 | 0.9 | 1.0 | — | — |
| gateway-plain | 100 | 0 | 55.4 | 67.8 | 80.0 | +22.2 [+21.3, +24.3] | 20.0 | 23.7 | 26.0 | +19.2 [+19.1, +19.3] | no |
| gateway | 100 | 0 | 58.3 | 67.0 | 73.9 | +25.1 [+24.0, +26.3] | 22.4 | 24.9 | 25.3 | +21.6 [+21.4, +21.8] | no |
| envoy-plain | 100 | 0 | 34.0 | 43.4 | 51.3 | +0.8 [-0.0, +1.6] | 1.3 | 1.4 | 1.5 | +0.4 [+0.4, +0.5] | total within noise |
| envoy-gated | 100 | 0 | 35.6 | 45.3 | 55.8 | +2.4 [+1.5, +3.5] | 3.0 | 3.8 | 4.3 | +2.1 [+2.1, +2.2] | no |
| envoy-first-only | 100 | 0 | 35.4 | 46.1 | 59.7 | +2.2 [+1.4, +3.5] | 3.0 | 3.6 | 5.8 | +2.1 [+2.1, +2.2] | no |

Gate counters during W2: {"caller_gone": 0, "known": 300, "predictor_errors": 0, "predictor_timeouts": 0, "requests": 300, "reservation_delete_errors": 0, "reservations_deleted": 0, "skipped": 0, "unknown": 0}.

### W3: large result (tpch.sf1.lineitem), in sequence

| Path | n | errors | rows | total p50 s | total min–max s | rows/s p50 | MB/s p50 | Δ total p50 vs direct |
|---|---|---|---|---|---|---|---|---|
| direct | 5 | 0 | 1000000 | 5.28 | 5.03–5.40 | 189,561 | 27.6 | — |
| gateway-plain | 5 | 0 | 1000000 | 5.87 | 5.62–6.05 | 170,455 | 24.8 | +11.2% |
| gateway | 5 | 0 | 1000000 | 5.76 | 5.64–5.96 | 173,649 | 25.2 | +9.2% |
| envoy-plain | 5 | 0 | 1000000 | 5.24 | 5.03–5.44 | 190,893 | 27.7 | -0.7% |
| envoy-gated | 5 | 0 | 1000000 | 5.24 | 5.01–5.50 | 190,853 | 27.7 | -0.7% |
| envoy-first-only | 5 | 0 | 1000000 | 5.26 | 4.99–5.41 | 189,967 | 27.6 | -0.2% |

With 5 runs I give no interval for W3. A path is within noise when its min–max range overlaps the range of direct.

### W4 (gate-delay-2000ms): SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 30 | 0 | 9.2 | 9.7 | 20.3 | — | 0.8 | 1.1 | 1.4 | — | — |
| gateway | 30 | 0 | 1031.2 | 1033.8 | 1034.1 | +1022.0 [+1021.7, +1022.9] | 1020.9 | 1023.7 | 1023.9 | +1020.1 [+1019.9, +1020.9] | no |
| envoy-gated | 30 | 0 | 261.7 | 262.6 | 262.6 | +252.5 [+252.4, +252.7] | 252.5 | 253.5 | 253.5 | +251.8 [+251.7, +251.9] | no |
| envoy-first-only | 30 | 0 | 261.4 | 262.3 | 262.5 | +252.3 [+252.1, +252.6] | 252.6 | 253.5 | 253.6 | +251.8 [+251.8, +252.2] | no |

### W4 (gate-delay-2000ms): known query, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 5 | 0 | 31.2 | 31.5 | 31.6 | — | 0.8 | 1.3 | 1.4 | — | — |
| gateway | 5 | 0 | 1054.1 | 1056.1 | 1056.2 | +1022.9 [+1022.3, +1025.0] | 1021.5 | 1023.8 | 1023.9 | +1020.8 [+1020.1, +1023.2] | no |
| envoy-gated | 5 | 0 | 283.5 | 285.9 | 286.3 | +252.3 [+251.5, +255.1] | 252.7 | 254.0 | 254.2 | +251.9 [+251.3, +253.4] | no |
| envoy-first-only | 5 | 0 | 284.1 | 294.6 | 296.3 | +252.8 [+251.4, +265.6] | 252.5 | 253.4 | 253.5 | +251.7 [+251.1, +252.8] | no |

Gate counters during W4 (gate-delay-2000ms): {"caller_gone": 102, "known": 0, "predictor_errors": 0, "predictor_timeouts": 0, "requests": 105, "reservation_delete_errors": 0, "reservations_deleted": 72, "skipped": 0, "unknown": 0}.

### W4 (gate-stopped): SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 100 | 0 | 9.4 | 10.3 | 11.1 | — | 0.7 | 0.9 | 1.0 | — | — |
| gateway | 100 | 0 | 1030.6 | 1033.8 | 1034.0 | +1021.2 [+532.2, +1021.6] | 1020.4 | 1023.2 | 1023.6 | +1019.6 [+694.9, +1019.8] | no |
| envoy-gated | 100 | 0 | 217.4 | 262.6 | 263.2 | +208.0 [+1.6, +252.1] | 207.9 | 253.2 | 253.4 | +207.2 [+0.9, +251.5] | no |
| envoy-first-only | 100 | 0 | 223.5 | 262.3 | 262.8 | +214.1 [+1.3, +251.6] | 214.6 | 253.2 | 253.3 | +213.9 [+0.9, +251.4] | no |

### W4 (gate-stopped): known query, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 10 | 0 | 32.2 | 39.0 | 39.1 | — | 0.8 | 1.2 | 1.4 | — | — |
| gateway | 10 | 0 | 184.2 | 1059.0 | 1062.3 | +152.0 [+21.7, +1021.8] | 151.4 | 1022.5 | 1023.4 | +150.7 [+20.1, +1020.0] | no |
| envoy-gated | 10 | 0 | 159.7 | 287.9 | 290.1 | +127.5 [+0.2, +252.7] | 126.8 | 253.2 | 253.2 | +126.0 [+0.7, +252.3] | no |
| envoy-first-only | 10 | 0 | 174.5 | 284.4 | 284.6 | +142.3 [+1.7, +252.3] | 143.0 | 252.8 | 252.8 | +142.2 [+0.9, +251.7] | no |

### Checks

| Check | Result |
|---|---|
| client session kept [gateway] | pass |
| known query gets required_workers_count [gateway] | pass |
| client session kept [envoy-gated] | pass |
| known query gets required_workers_count [envoy-gated] | pass |
| client session kept [envoy-first-only] | pass |
| known query gets required_workers_count [envoy-first-only] | pass |
| client session kept [direct] | pass |
| header canonical X-Trino-Session [gateway] | pass |
| header lower-case x-trino-session [gateway] | pass |
| header two X-Trino-Session lines [gateway] | pass |
| header canonical X-Trino-Session [envoy-gated] | pass |
| header lower-case x-trino-session [envoy-gated] | pass |
| header two X-Trino-Session lines [envoy-gated] | pass |
| header canonical X-Trino-Session [direct] | pass |
| header lower-case x-trino-session [direct] | pass |
| header two X-Trino-Session lines [direct] | pass |
| reservations back to 0 | pass |

### Not checked

- One kind node on one host. Every pod, the client included, runs on the same node.
- Cluster B is only on the routing checks. All timed paths go to cluster A.
- `SELECT 1` is a known query after its first run, so W1 on the gated paths also makes a prediction and a reservation.
- The bench queries need 2 workers, so KEDA does not scale during the bench, and Trino never waits for workers.
- W3 measures the bench client, which parses every page with Python `json`.
- No TLS, no authentication, HTTP/1.1 only. Envoy runs with 4 worker threads, Trino Gateway with its chart defaults and 1 GiB.
