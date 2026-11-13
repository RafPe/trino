# Bench: compose, after the predictor and gate changes (2026-11-13)

Images: predictor `e5ba0e5339c5`, gate `85a199a57341`. The baseline is [BENCH-compose.md](BENCH-compose.md).

Times in ms. total = POST until the last page is read. first = the POST /v1/statement request only.
Δp50 = median of the path − median of direct, with a 95% bootstrap interval. "within noise" = the interval contains 0 for total and first.

### W1: SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 300 | 0 | 14.8 | 24.4 | 27.6 | — | 1.1 | 1.8 | 2.2 | — | — |
| gateway-plain | 300 | 0 | 40.0 | 56.9 | 63.7 | +25.3 [+23.9, +26.4] | 22.7 | 28.5 | 30.5 | +21.6 [+21.4, +21.9] | no |
| gateway | 300 | 0 | 42.6 | 62.5 | 69.2 | +27.8 [+26.4, +29.1] | 25.3 | 33.6 | 37.1 | +24.3 [+24.0, +24.8] | no |
| envoy-plain | 300 | 0 | 15.5 | 24.9 | 29.4 | +0.7 [-0.2, +1.6] | 1.5 | 2.2 | 2.8 | +0.4 [+0.4, +0.5] | total within noise |
| envoy-gated | 300 | 0 | 17.4 | 28.0 | 29.9 | +2.6 [+1.6, +3.4] | 3.3 | 5.0 | 5.8 | +2.2 [+2.1, +2.3] | no |
| envoy-first-only | 300 | 0 | 16.8 | 27.4 | 31.7 | +2.0 [+1.3, +3.1] | 3.3 | 5.2 | 6.2 | +2.2 [+2.1, +2.3] | no |

### W1: SELECT 1, 8 at the same time

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 296 | 0 | 23.1 | 36.0 | 43.4 | — | 2.8 | 5.3 | 6.7 | — | — |
| gateway-plain | 296 | 0 | 54.8 | 86.1 | 122.0 | +31.6 [+28.8, +33.7] | 28.2 | 48.1 | 61.4 | +25.4 [+24.5, +26.4] | no |
| gateway | 296 | 0 | 69.3 | 94.7 | 102.6 | +46.1 [+43.8, +49.1] | 37.4 | 56.4 | 62.7 | +34.6 [+33.2, +35.7] | no |
| envoy-plain | 296 | 0 | 40.1 | 59.0 | 68.5 | +17.0 [+14.2, +18.6] | 3.1 | 8.2 | 9.6 | +0.3 [+0.1, +0.5] | no |
| envoy-gated | 296 | 0 | 23.8 | 33.6 | 37.5 | +0.7 [-0.6, +1.7] | 4.2 | 6.4 | 7.5 | +1.4 [+1.2, +1.6] | total within noise |
| envoy-first-only | 296 | 0 | 30.6 | 48.9 | 58.6 | +7.4 [+6.0, +8.9] | 4.5 | 8.8 | 13.2 | +1.7 [+1.6, +2.0] | no |

| Path | queries | wall s | queries/s |
|---|---|---|---|
| direct | 296 | 1.0 | 308.9 |
| gateway-plain | 296 | 2.2 | 135.1 |
| gateway | 296 | 2.7 | 109.9 |
| envoy-plain | 296 | 1.6 | 187.6 |
| envoy-gated | 296 | 1.0 | 307.5 |
| envoy-first-only | 296 | 1.2 | 241.6 |

### W2: known query (count(*) on tpch.tiny.orders), in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 100 | 0 | 37.0 | 66.3 | 96.6 | — | 0.8 | 1.1 | 2.3 | — | — |
| gateway-plain | 100 | 0 | 57.8 | 85.8 | 117.7 | +20.8 [+19.1, +23.7] | 19.8 | 21.0 | 21.6 | +18.9 [+18.8, +19.0] | no |
| gateway | 100 | 0 | 61.3 | 91.8 | 137.9 | +24.3 [+22.3, +27.3] | 22.5 | 25.6 | 26.9 | +21.6 [+21.5, +21.9] | no |
| envoy-plain | 100 | 0 | 38.0 | 66.9 | 87.8 | +0.9 [-0.7, +3.9] | 1.3 | 1.5 | 1.7 | +0.5 [+0.4, +0.5] | total within noise |
| envoy-gated | 100 | 0 | 39.7 | 65.5 | 87.5 | +2.7 [+0.8, +5.5] | 3.0 | 3.9 | 4.8 | +2.2 [+2.1, +2.2] | no |
| envoy-first-only | 100 | 0 | 39.0 | 68.1 | 105.9 | +2.0 [+0.1, +4.6] | 3.0 | 4.2 | 4.6 | +2.2 [+2.1, +2.2] | no |

Gate counters during W2: {"caller_gone": 0, "known": 300, "predictor_errors": 0, "predictor_timeouts": 0, "requests": 300, "reservation_delete_errors": 0, "reservations_deleted": 0, "skipped": 0, "unknown": 0}.

### W3: large result (tpch.sf1.lineitem), in sequence

| Path | n | errors | rows | total p50 s | total min–max s | rows/s p50 | MB/s p50 | Δ total p50 vs direct |
|---|---|---|---|---|---|---|---|---|
| direct | 5 | 0 | 1000000 | 5.31 | 5.02–5.35 | 188,229 | 27.4 | — |
| gateway-plain | 5 | 0 | 1000000 | 5.74 | 5.63–6.03 | 174,186 | 25.3 | +8.1% |
| gateway | 5 | 0 | 1000000 | 5.87 | 5.69–5.91 | 170,291 | 24.7 | +10.5% |
| envoy-plain | 5 | 0 | 1000000 | 5.26 | 5.05–5.34 | 190,265 | 27.7 | -1.1% |
| envoy-gated | 5 | 0 | 1000000 | 5.25 | 5.11–5.34 | 190,483 | 27.7 | -1.2% |
| envoy-first-only | 5 | 0 | 1000000 | 5.25 | 5.08–5.32 | 190,629 | 27.7 | -1.3% |

With 5 runs I give no interval for W3. A path is within noise when its min–max range overlaps the range of direct.

### W4 (gate-delay-2000ms): SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 30 | 0 | 9.5 | 17.3 | 21.6 | — | 0.9 | 1.2 | 2.4 | — | — |
| gateway | 30 | 0 | 1031.7 | 1047.9 | 1059.3 | +1022.2 [+1021.8, +1022.7] | 1021.0 | 1025.3 | 1032.4 | +1020.1 [+1020.0, +1020.4] | no |
| envoy-gated | 30 | 0 | 262.2 | 272.7 | 277.0 | +252.7 [+252.3, +253.4] | 252.8 | 254.0 | 255.2 | +251.9 [+251.7, +252.3] | no |
| envoy-first-only | 30 | 0 | 261.4 | 262.8 | 263.0 | +252.0 [+251.8, +252.2] | 252.6 | 253.6 | 253.6 | +251.7 [+251.7, +251.9] | no |

### W4 (gate-delay-2000ms): known query, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 5 | 0 | 32.6 | 64.2 | 70.4 | — | 0.9 | 1.4 | 1.5 | — | — |
| gateway | 5 | 0 | 1055.5 | 1092.6 | 1093.0 | +1022.9 [+983.5, +1060.5] | 1022.2 | 1024.7 | 1024.8 | +1021.3 [+1020.0, +1024.0] | no |
| envoy-gated | 5 | 0 | 285.0 | 307.7 | 312.1 | +252.4 [+213.0, +280.5] | 253.1 | 253.6 | 253.6 | +252.2 [+251.3, +252.7] | no |
| envoy-first-only | 5 | 0 | 285.1 | 289.9 | 290.6 | +252.5 [+213.1, +258.1] | 252.9 | 253.1 | 253.1 | +252.0 [+251.4, +252.2] | no |

Gate counters during W4 (gate-delay-2000ms): {"caller_gone": 102, "known": 0, "predictor_errors": 0, "predictor_timeouts": 0, "requests": 105, "reservation_delete_errors": 0, "reservations_deleted": 73, "skipped": 0, "unknown": 0}.

### W4 (gate-stopped): SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 100 | 0 | 9.9 | 13.0 | 15.4 | — | 0.9 | 1.1 | 1.6 | — | — |
| gateway | 100 | 0 | 31.6 | 34.9 | 38.4 | +21.7 [+21.5, +21.9] | 20.6 | 22.7 | 23.9 | +19.8 [+19.7, +19.9] | no |
| envoy-gated | 100 | 0 | 10.7 | 12.7 | 14.9 | +0.8 [+0.7, +0.9] | 1.4 | 1.5 | 2.1 | +0.5 [+0.5, +0.5] | no |
| envoy-first-only | 100 | 0 | 10.5 | 12.1 | 14.3 | +0.6 [+0.5, +0.7] | 1.4 | 1.6 | 1.9 | +0.5 [+0.5, +0.5] | no |

### W4 (gate-stopped): known query, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 10 | 0 | 35.7 | 48.7 | 54.5 | — | 0.9 | 2.2 | 2.3 | — | — |
| gateway | 10 | 0 | 62.8 | 80.2 | 83.4 | +27.2 [+18.0, +37.1] | 21.2 | 24.2 | 25.9 | +20.3 [+19.6, +20.7] | no |
| envoy-gated | 10 | 0 | 37.2 | 46.0 | 47.3 | +1.6 [-3.9, +10.3] | 1.4 | 1.7 | 1.9 | +0.5 [-0.1, +0.6] | within noise |
| envoy-first-only | 10 | 0 | 34.7 | 60.4 | 70.3 | -0.9 [-4.8, +5.3] | 1.5 | 3.3 | 3.5 | +0.6 [+0.3, +1.6] | total within noise |

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

- One host. Another lab (a kind cluster) ran on the same host at the same time; the load average went up to 15. So I ran the whole bench twice, each time on a fresh lab. This report is the second run; the first is in `bench-compose-after-run1.jsonl`. The sequential medians of the two runs differ by at most about 1 ms. The queries per second at 8 clients do not repeat: `envoy-plain` gave 262 and 188, `envoy-gated` 252 and 308. I did not run it more often.
- Cluster B is only on the routing checks (L2, L3). All timed paths go to cluster A.
- `SELECT 1` is a known query after its first run (the listener records every query), so W1 on the gated paths also makes a prediction and a reservation. I did not time an unknown query on the gated paths.
- The predictor and PostgreSQL ran on the same host as everything else. A remote predictor adds its network time.
- W3 measures the bench client, which parses every page with Python `json`. A faster client can show larger differences between the paths.
- No TLS, no authentication, HTTP/1.1 only.
- Envoy runs with 4 worker threads, Trino Gateway with its defaults. I did not tune either.
