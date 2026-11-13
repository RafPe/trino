# Bench: compose

Times in ms. total = POST until the last page is read. first = the POST /v1/statement request only.
Δp50 = median of the path − median of direct, with a 95% bootstrap interval. "within noise" = the interval contains 0 for total and first.

### W1: SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 300 | 0 | 14.1 | 23.1 | 25.3 | — | 1.0 | 1.7 | 2.1 | — | — |
| gateway-plain | 300 | 0 | 38.5 | 56.1 | 59.1 | +24.4 [+22.5, +26.3] | 22.3 | 28.3 | 29.7 | +21.2 [+21.0, +21.5] | no |
| gateway | 300 | 0 | 52.2 | 69.4 | 74.6 | +38.0 [+35.6, +39.9] | 35.4 | 44.3 | 45.8 | +34.4 [+34.1, +34.7] | no |
| envoy-plain | 300 | 0 | 14.5 | 23.6 | 26.2 | +0.3 [-1.2, +2.4] | 1.4 | 2.1 | 2.3 | +0.4 [+0.4, +0.5] | total within noise |
| envoy-gated | 300 | 0 | 27.4 | 38.6 | 41.2 | +13.2 [+11.4, +15.1] | 13.9 | 16.8 | 17.7 | +12.8 [+12.7, +13.0] | no |
| envoy-first-only | 300 | 0 | 27.0 | 38.2 | 40.9 | +12.9 [+11.0, +15.0] | 13.8 | 16.7 | 17.6 | +12.8 [+12.7, +13.0] | no |

### W1: SELECT 1, 8 at the same time

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 296 | 0 | 26.2 | 34.1 | 37.1 | — | 2.4 | 4.9 | 6.3 | — | — |
| gateway-plain | 296 | 0 | 56.4 | 80.8 | 93.1 | +30.2 [+28.5, +33.2] | 28.0 | 46.1 | 58.8 | +25.5 [+23.9, +26.8] | no |
| gateway | 296 | 0 | 72.1 | 95.9 | 135.6 | +45.9 [+44.8, +47.4] | 45.3 | 61.3 | 75.8 | +42.9 [+41.7, +43.9] | no |
| envoy-plain | 296 | 0 | 24.5 | 33.6 | 93.1 | -1.8 [-2.5, -0.7] | 2.5 | 5.0 | 5.9 | +0.1 [-0.2, +0.3] | no |
| envoy-gated | 296 | 0 | 44.0 | 64.9 | 127.4 | +17.8 [+16.8, +19.7] | 16.9 | 23.8 | 28.9 | +14.5 [+14.1, +14.9] | no |
| envoy-first-only | 296 | 0 | 39.8 | 51.5 | 56.3 | +13.6 [+12.9, +15.0] | 16.4 | 21.2 | 24.0 | +13.9 [+13.4, +14.2] | no |

| Path | queries | wall s | queries/s |
|---|---|---|---|
| direct | 296 | 1.0 | 288.2 |
| gateway-plain | 296 | 2.2 | 134.2 |
| gateway | 296 | 2.8 | 106.6 |
| envoy-plain | 296 | 1.0 | 290.7 |
| envoy-gated | 296 | 1.8 | 162.5 |
| envoy-first-only | 296 | 1.5 | 193.0 |

### W2: known query (count(*) on tpch.tiny.orders), in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 100 | 0 | 35.7 | 53.3 | 75.5 | — | 0.8 | 0.9 | 2.7 | — | — |
| gateway-plain | 100 | 0 | 57.0 | 80.7 | 102.7 | +21.3 [+20.0, +23.4] | 19.7 | 20.6 | 22.0 | +18.9 [+18.8, +18.9] | no |
| gateway | 100 | 0 | 70.1 | 97.5 | 109.7 | +34.4 [+32.9, +36.3] | 32.4 | 35.5 | 42.3 | +31.6 [+31.5, +31.7] | no |
| envoy-plain | 100 | 0 | 36.9 | 74.0 | 96.6 | +1.2 [-0.0, +2.8] | 1.3 | 1.4 | 2.2 | +0.5 [+0.4, +0.5] | total within noise |
| envoy-gated | 100 | 0 | 48.9 | 80.5 | 86.0 | +13.2 [+11.8, +15.2] | 13.4 | 14.0 | 15.2 | +12.6 [+12.5, +12.6] | no |
| envoy-first-only | 100 | 0 | 48.5 | 83.5 | 91.8 | +12.9 [+11.2, +15.3] | 13.3 | 14.3 | 16.8 | +12.5 [+12.4, +12.6] | no |

Gate counters during W2: {"caller_gone": 0, "known": 300, "predictor_errors": 0, "predictor_timeouts": 0, "requests": 300, "skipped": 0, "unknown": 0}.

### W3: large result (tpch.sf1.lineitem), in sequence

| Path | n | errors | rows | total p50 s | total min–max s | rows/s p50 | MB/s p50 | Δ total p50 vs direct |
|---|---|---|---|---|---|---|---|---|
| direct | 5 | 0 | 1000000 | 5.04 | 4.95–5.43 | 198,255 | 28.8 | — |
| gateway-plain | 5 | 0 | 1000000 | 5.71 | 5.54–5.87 | 175,247 | 25.5 | +13.1% |
| gateway | 5 | 0 | 1000000 | 5.69 | 5.50–5.84 | 175,620 | 25.5 | +12.9% |
| envoy-plain | 5 | 0 | 1000000 | 5.29 | 5.06–5.33 | 188,994 | 27.5 | +4.9% |
| envoy-gated | 5 | 0 | 1000000 | 5.19 | 5.00–5.33 | 192,759 | 28.0 | +2.9% |
| envoy-first-only | 5 | 0 | 1000000 | 5.24 | 4.96–5.30 | 191,020 | 27.8 | +3.8% |

With 5 runs I give no interval for W3. A path is within noise when its min–max range overlaps the range of direct.

### W4 (gate-delay-2000ms): SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 30 | 0 | 9.3 | 9.9 | 10.6 | — | 0.8 | 1.0 | 1.5 | — | — |
| gateway | 30 | 0 | 1031.7 | 1033.1 | 1033.9 | +1022.4 [+1022.1, +1022.6] | 1021.1 | 1022.0 | 1022.8 | +1020.3 [+1020.1, +1020.4] | no |
| envoy-gated | 30 | 0 | 261.8 | 262.6 | 263.4 | +252.4 [+252.2, +252.7] | 252.6 | 253.5 | 254.0 | +251.8 [+251.7, +252.0] | no |
| envoy-first-only | 30 | 0 | 261.5 | 262.6 | 262.7 | +252.1 [+251.9, +252.5] | 252.7 | 253.6 | 253.7 | +251.9 [+251.7, +252.0] | no |

### W4 (gate-delay-2000ms): known query, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 5 | 0 | 32.1 | 33.7 | 34.1 | — | 0.8 | 1.4 | 1.5 | — | — |
| gateway | 5 | 0 | 1053.6 | 1054.9 | 1055.1 | +1021.5 [+1019.4, +1023.2] | 1021.3 | 1021.8 | 1021.9 | +1020.5 [+1019.8, +1021.1] | no |
| envoy-gated | 5 | 0 | 283.4 | 287.3 | 287.8 | +251.4 [+249.3, +255.8] | 252.6 | 253.6 | 253.6 | +251.7 [+251.1, +252.8] | no |
| envoy-first-only | 5 | 0 | 284.0 | 286.3 | 286.7 | +251.9 [+249.9, +254.8] | 253.1 | 253.8 | 253.9 | +252.3 [+251.5, +253.1] | no |

Gate counters during W4 (gate-delay-2000ms): {"caller_gone": 101, "known": 0, "predictor_errors": 0, "predictor_timeouts": 0, "requests": 105, "skipped": 0, "unknown": 0}.

### W4 (gate-stopped): SELECT 1, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 100 | 0 | 9.5 | 10.1 | 10.7 | — | 0.9 | 0.9 | 1.0 | — | — |
| gateway | 100 | 0 | 30.9 | 32.7 | 39.2 | +21.3 [+21.2, +21.5] | 20.2 | 21.0 | 22.2 | +19.4 [+19.3, +19.4] | no |
| envoy-gated | 100 | 0 | 10.5 | 11.0 | 12.0 | +0.9 [+0.8, +1.0] | 1.4 | 1.5 | 1.5 | +0.5 [+0.5, +0.6] | no |
| envoy-first-only | 100 | 0 | 10.0 | 10.6 | 11.7 | +0.5 [+0.4, +0.6] | 1.4 | 1.5 | 1.6 | +0.5 [+0.5, +0.5] | no |

### W4 (gate-stopped): known query, in sequence

| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| direct | 10 | 0 | 32.2 | 37.5 | 40.4 | — | 0.9 | 1.3 | 1.5 | — | — |
| gateway | 10 | 0 | 54.2 | 57.8 | 58.7 | +22.0 [+21.5, +23.8] | 20.4 | 21.3 | 21.4 | +19.5 [+19.3, +20.1] | no |
| envoy-gated | 10 | 0 | 33.1 | 38.5 | 41.1 | +0.9 [+0.2, +2.0] | 1.4 | 1.7 | 1.9 | +0.5 [+0.5, +0.6] | no |
| envoy-first-only | 10 | 0 | 32.9 | 40.5 | 43.2 | +0.7 [+0.0, +3.3] | 1.4 | 1.8 | 2.0 | +0.5 [+0.4, +0.6] | no |

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

- One host. I ran the whole bench twice (the second time on a fresh lab). This report is the second run; the first is in `bench-compose-run1.jsonl`. The latency medians of the two runs differ by at most about 5 ms. I did not run it more often.
- Cluster B is only on the routing checks (L2, L3). All timed paths go to cluster A.
- `SELECT 1` is a known query after its first run (the listener records every query), so W1 on the gated paths also makes a prediction and a reservation. I did not time an unknown query on the gated paths.
- The predictor and PostgreSQL ran on the same host as everything else. A remote predictor adds its network time.
- W3 measures the bench client, which parses every page with Python `json`. A faster client can show larger differences between the paths.
- No TLS, no authentication, HTTP/1.1 only.
- Envoy runs with 4 worker threads, Trino Gateway with its defaults. I did not tune either.
