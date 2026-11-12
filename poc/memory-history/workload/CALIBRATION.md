# Calibration (milestone M4)

Measured on 2026-11-12 on the compose cluster (`compose/`), stock image `trinodb/trino:484`
(no event listener, no predictor), host: Linux arm64, 16 cores, 124 GB RAM.
All peaks come from `GET /v1/query/{id}` (`queryStats.peakUserMemoryReservation`,
`queryStats.peakTaskUserMemory`), read by `gate/gate.py`. 1 GiB = 2^30 bytes.

## Final cluster numbers

Contract 5 starting numbers needed **no change**:

| setting | value |
|---|---|
| coordinator | 4 GB heap (`-Xms4G -Xmx4G`), `node-scheduler.include-coordinator=false`, container limit 6 GB |
| workers | 4 GB heap (`-Xms4G -Xmx4G`), container limit 6 GB, 2 at start, at most 6 |
| `query.max-memory-per-node` | `1GB` (`PER_WORKER_MEMORY_BYTES=1073741824`) |
| `query.max-memory` | `6GB` |
| catalogs | `tpcds` (`tpcds.splits-per-node=4`), `tpch` (`tpch.splits-per-node=4`) |
| session | `join_distribution_type=PARTITIONED`, `task_concurrency=4` |

The memory limits are set in `compose/.env` (`QUERY_MAX_MEMORY_PER_NODE`, `QUERY_MAX_MEMORY`).

## Heavy queries: fail on 2 workers, pass on more

Heavy templates in `workload.yaml`: TPC-H q18, q09, q21 at `tpch.sf5`, three literal variants each.
Each run is one query, ungated (`--no-gate`), on an idle cluster.

- **q18, q09**: fail on 2 workers, pass on 3, 4 and 6.
- **q21**: fails on 2 **and 3** workers, passes on 4 and 6.
- Every run is under 13 s. All 9 (template, variant) pairs behave the same; a repeat of the 2-worker runs
  (learn pass in the gate test below) gave the same states and error codes.

| template | variant | workers | query id | state | error code | peak user mem | peak task user mem | wall |
|---|---|---|---|---|---|---|---|---|
| tpch_q09 | v0 | 2 | `20261112_133347_00254_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.77 GiB | 0.67 GiB | 5.0 s |
| tpch_q09 | v0 | 3 | `20261112_133643_00319_wsu9y` | FINISHED | - | 2.44 GiB | 0.66 GiB | 6.1 s |
| tpch_q09 | v0 | 4 | `20261112_133508_00288_wsu9y` | FINISHED | - | 2.30 GiB | 0.49 GiB | 8.2 s |
| tpch_q09 | v0 | 6 | `20261112_133808_00352_wsu9y` | FINISHED | - | 2.28 GiB | 0.33 GiB | 8.4 s |
| tpch_q09 | v1 | 2 | `20261112_133352_00257_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.99 GiB | 0.77 GiB | 4.9 s |
| tpch_q09 | v1 | 3 | `20261112_133649_00322_wsu9y` | FINISHED | - | 2.20 GiB | 0.66 GiB | 6.1 s |
| tpch_q09 | v1 | 4 | `20261112_133517_00291_wsu9y` | FINISHED | - | 2.40 GiB | 0.49 GiB | 6.5 s |
| tpch_q09 | v1 | 6 | `20261112_133816_00355_wsu9y` | FINISHED | - | 2.18 GiB | 0.33 GiB | 7.2 s |
| tpch_q09 | v2 | 2 | `20261112_133357_00260_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.83 GiB | 0.76 GiB | 4.8 s |
| tpch_q09 | v2 | 3 | `20261112_133655_00325_wsu9y` | FINISHED | - | 2.45 GiB | 0.66 GiB | 5.8 s |
| tpch_q09 | v2 | 4 | `20261112_133523_00294_wsu9y` | FINISHED | - | 2.30 GiB | 0.49 GiB | 6.3 s |
| tpch_q09 | v2 | 6 | `20261112_133824_00358_wsu9y` | FINISHED | - | 2.27 GiB | 0.32 GiB | 6.7 s |
| tpch_q18 | v0 | 2 | `20261112_133334_00245_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.61 GiB | 0.60 GiB | 4.0 s |
| tpch_q18 | v0 | 3 | `20261112_133625_00310_wsu9y` | FINISHED | - | 1.58 GiB | 0.43 GiB | 5.9 s |
| tpch_q18 | v0 | 4 | `20261112_133447_00279_wsu9y` | FINISHED | - | 1.51 GiB | 0.34 GiB | 9.2 s |
| tpch_q18 | v0 | 6 | `20261112_133745_00343_wsu9y` | FINISHED | - | 1.42 GiB | 0.25 GiB | 10.1 s |
| tpch_q18 | v1 | 2 | `20261112_133338_00248_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.70 GiB | 0.71 GiB | 4.2 s |
| tpch_q18 | v1 | 3 | `20261112_133631_00313_wsu9y` | FINISHED | - | 1.60 GiB | 0.46 GiB | 5.4 s |
| tpch_q18 | v1 | 4 | `20261112_133456_00282_wsu9y` | FINISHED | - | 1.54 GiB | 0.36 GiB | 5.6 s |
| tpch_q18 | v1 | 6 | `20261112_133755_00346_wsu9y` | FINISHED | - | 1.54 GiB | 0.23 GiB | 6.3 s |
| tpch_q18 | v2 | 2 | `20261112_133343_00251_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.64 GiB | 0.67 GiB | 3.8 s |
| tpch_q18 | v2 | 3 | `20261112_133637_00316_wsu9y` | FINISHED | - | 1.71 GiB | 0.47 GiB | 5.7 s |
| tpch_q18 | v2 | 4 | `20261112_133502_00285_wsu9y` | FINISHED | - | 1.61 GiB | 0.38 GiB | 5.9 s |
| tpch_q18 | v2 | 6 | `20261112_133802_00349_wsu9y` | FINISHED | - | 1.54 GiB | 0.26 GiB | 5.9 s |
| tpch_q21 | v0 | 2 | `20261112_133402_00263_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.66 GiB | 0.58 GiB | 5.8 s |
| tpch_q21 | v0 | 3 | `20261112_133701_00328_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 2.82 GiB | 0.59 GiB | 8.1 s |
| tpch_q21 | v0 | 4 | `20261112_133530_00297_wsu9y` | FINISHED | - | 2.96 GiB | 0.44 GiB | 11.9 s |
| tpch_q21 | v0 | 6 | `20261112_133831_00361_wsu9y` | FINISHED | - | 3.32 GiB | 0.32 GiB | 12.3 s |
| tpch_q21 | v1 | 2 | `20261112_133408_00266_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.76 GiB | 0.58 GiB | 6.6 s |
| tpch_q21 | v1 | 3 | `20261112_133709_00331_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 2.86 GiB | 0.59 GiB | 8.3 s |
| tpch_q21 | v1 | 4 | `20261112_133542_00300_wsu9y` | FINISHED | - | 3.01 GiB | 0.44 GiB | 10.5 s |
| tpch_q21 | v1 | 6 | `20261112_133843_00364_wsu9y` | FINISHED | - | 3.36 GiB | 0.31 GiB | 10.9 s |
| tpch_q21 | v2 | 2 | `20261112_133414_00269_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.70 GiB | 0.63 GiB | 6.1 s |
| tpch_q21 | v2 | 3 | `20261112_133718_00334_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 2.82 GiB | 0.59 GiB | 8.5 s |
| tpch_q21 | v2 | 4 | `20261112_133552_00303_wsu9y` | FINISHED | - | 2.86 GiB | 0.44 GiB | 10.6 s |
| tpch_q21 | v2 | 6 | `20261112_133854_00367_wsu9y` | FINISHED | - | 3.28 GiB | 0.31 GiB | 10.6 s |

Observations that matter for the predictor:

- A failed run's peak is a lower bound, and it is close to the 2-worker capacity (≈ 1.6–2.0 GiB of 2 GiB).
- The cluster-wide peak grows a little with more workers (q21: 2.9 → 3.3 GiB from 4 to 6 workers).
- q18 needs 3 workers although its successful peak (1.4–1.7 GiB) is below 2 × 1 GiB: per-node memory is not
  spread evenly. With `SAFETY_FACTOR=1.2`, a successful peak of 1.71 GiB gives 3 workers (OK), but 1.54 GiB
  gives 2 workers, which fails. See open problems.
- `peakTaskUserMemory` (≤ 0.8 GiB) never reaches the 1 GiB node limit: several tasks of one query share a node.

### TPC-DS candidates (also measured, not in the workload)

TPC-DS heavy queries show the same effect but are slow, because `tpcds` generates data on the fly with
4 splits per node (and does not split tables at all below about sf5, so `sf1`–`sf3` scans run on one thread):

| query | schema | workers | query id | state | error code | peak user mem | wall |
|---|---|---|---|---|---|---|---|
| q72 | sf10 | 2 | `20261112_125054_00003_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.96 GiB | 18 s |
| q72 | sf10 | 4 | `20261112_125907_00024_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 3.58 GiB | 27 s |
| q72 | sf5 | 2 | `20261112_131102_00042_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.68 GiB | 6 s |
| q72 | sf5 | 4 | `20261112_130500_00033_wsu9y` | FINISHED | - | 3.08 GiB | 125 s |
| q04 | sf10 | 2 | `20261112_125309_00012_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.89 GiB | 37 s |
| q04 | sf10 | 4 | `20261112_125935_00027_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 3.54 GiB | 75 s |
| q04 | sf5 | 2 | `20261112_131108_00045_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.80 GiB | 30 s |
| q04 | sf5 | 4 | `20261112_130705_00036_wsu9y` | FINISHED | - | 2.12 GiB | 101 s |
| q11 | sf10 | 2 | `20261112_125347_00015_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.89 GiB | 36 s |
| q11 | sf10 | 4 | `20261112_130050_00030_wsu9y` | FINISHED | - | 3.17 GiB | 99 s |
| q23a | sf10 | 2 | `20261112_125423_00018_wsu9y` | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.98 GiB | 91 s |
| q23a | sf5 | 2 | `20261112_131138_00048_wsu9y` | FINISHED | - | 1.21 GiB | 99 s |
| q67 | sf10 | 2 | `20261112_125112_00006_wsu9y` | FINISHED | - | 0.99 GiB | 30 s |
| q78 | sf10 | 2 | `20261112_125142_00009_wsu9y` | FINISHED | - | 0.74 GiB | 86 s |
| q95 | sf10 | 2 | `20261112_125555_00021_wsu9y` | FINISHED | - | 0.58 GiB | 71 s |

TPC-H q18/q09/q21 at sf5 were chosen because they show the same behavior in 4–13 s instead of ~2 min.

## Small and medium templates (2 workers, ungated)

One full ungated pass of all 13 templates × 3 variants on 2 workers took **520 s** (39 queries; the 9 heavy
ones failed in 4–7 s). No variant returned an empty result.

| template | size | schema | peak user mem (min–max over 3 variants) | wall per query |
|---|---|---|---|---|
| tpcds_q03 | small | sf1 | 3.2 MiB – 3.4 MiB | 18–18 s |
| tpcds_q42 | small | sf1 | 19.4 MiB – 27.4 MiB | 18–18 s |
| tpcds_q55 | small | sf1 | 4.1 MiB – 5.1 MiB | 18–18 s |
| tpcds_q96 | small | sf1 | 3.3 MiB – 4.4 MiB | 15–15 s |
| tpch_q06 | small | sf1 | 0.0 MiB – 3.3 MiB | 1–1 s |
| tpch_q01 | small | sf1 | 0.0 MiB – 12.3 MiB | 1–1 s |
| tpcds_q07 | medium | sf10 | 49.9 MiB – 52.5 MiB | 28–28 s |
| tpcds_q19 | medium | sf10 | 1330.0 MiB – 1377.8 MiB | 24–25 s |
| tpcds_q43 | medium | sf10 | 1079.2 MiB – 1145.2 MiB | 23–24 s |
| tpch_q13 | medium | sf10 | 575.0 MiB – 588.5 MiB | 10–11 s |

Note: `tpcds_q19` and `tpcds_q43` at sf10 peak at 1.0–1.4 GiB on 2 workers and pass; they are the
"upper medium" cases (the predictor should keep them at `MIN_WORKERS`=2).

## Gate + scaler mechanism with the mock predictor (M5 rehearsal, stock image)

`gate/test_gate_mock.sh` (TEST ONLY: `gate/mock_predictor.py` returns canned answers; it is not the real predictor).
Query: `tpch_q21` (SAUDI ARABIA) at `tpch.sf5`. The mock says "known, 4 workers"; `scaler.py` runs with
`--down-delay 30`.

1. Ungated, 2 workers → FAILED, `EXCEEDED_LOCAL_MEMORY_LIMIT`:

```json
{"actual": {"cpu_ms": 35870, "elapsed_ms": 5600, "error_code": "EXCEEDED_LOCAL_MEMORY_LIMIT", "error_type": "INSUFFICIENT_RESOURCES", "execution_ms": 5600, "peak_task_total_memory_bytes": 558314352, "peak_task_user_memory_bytes": 558314352, "peak_user_memory_bytes": 1594470096, "physical_input_rows": 49170097, "queued_ms": 0, "resource_waiting_ms": 5, "state": "FAILED"}, "catalog": "tpch", "client_wall_ms": 5612, "error_code": "EXCEEDED_LOCAL_MEMORY_LIMIT", "error_message": "Query exceeded per-node memory limit of 1GB [Allocated: 1023.99MB, Delta: 64kB, Top Consumers: {HashBuilderOperator=982.88MB, LazyOutputBuffer=15.94MB, ScanFilterAndProjectOperator-ConnectorPageSource=10.76MB}]", "gated": false, "prediction": null, "query_id": "20261112_134031_00372_wsu9y", "required_workers": null, "reservation_id": null, "schema": "sf5", "state": "FAILED", "tags": ["test=ungated"], "ts": "2026-11-12T13:40:31+00:00", "wait_ms": 5, "workers_after": 2, "workers_before": 2}
```

2. Gated → reservation → scaler scales to 4 → Trino waits 19.6 s (`resourceWaitingTime`, from
`required_workers_count=4`) → FINISHED on 4 workers:

```json
{"actual": {"cpu_ms": 138000, "elapsed_ms": 34340, "error_code": null, "error_type": null, "execution_ms": 14790, "peak_task_total_memory_bytes": 475415568, "peak_task_user_memory_bytes": 475415568, "peak_user_memory_bytes": 3083713376, "physical_input_rows": 93703911, "queued_ms": 0, "resource_waiting_ms": 19550, "state": "FINISHED"}, "catalog": "tpch", "client_wall_ms": 34355, "error_code": null, "gated": true, "prediction": {"basis": "mock", "fingerprint": "8a32acf8c189b76beccd626cec0345495edfd2b880041324a94b7e62c83bd360", "known": true, "memoryFailures": 1, "predictedPeakTaskUserMemoryBytes": 894784853, "predictedPeakUserMemoryBytes": 3579139412, "requiredWorkers": 4, "runs": 1, "successfulRuns": 0, "supported": true}, "query_id": "20261112_134039_00375_wsu9y", "required_workers": 4, "reservation_id": "15d43ac1-b7e8-4551-8662-bd8f712dab9c", "rows": 100, "schema": "sf5", "state": "FINISHED", "tags": ["test=gated"], "ts": "2026-11-12T13:40:39+00:00", "wait_ms": 19550, "workers_after": 4, "workers_before": 2}
```

Scaler log (scale-up 5 s after the reservation; scale-down 30 s after the reservation was deleted):

```
2026-11-12T13:40:37 scaler: start: running=2
2026-11-12T13:40:42 scaler: scale worker=4 (wanted=4, reservations=1, running=2)
2026-11-12T13:41:14 scaler: wanted=2 < target=4; scale down in 30s if it stays lower
2026-11-12T13:41:45 scaler: scale worker=2 (wanted=2, reservations=0, running=4)
```

`run_workload.py` + `report.py` were also run with the mock (learn pass with an empty mock, predict pass with
the mock knowing the 3 heavy templates, `tpch_q06`/`tpch_q13`/heavy only): learn pass 9/9 heavy runs
failed on 2 workers; predict pass 15/15 FINISHED, the first heavy query waited 22.1 s while the scaler
added 2 workers. `report.py` was tested both with PostgreSQL unreachable/no table (falls back to
`runs.jsonl`) and with a hand-made `query_history` row (uses it).
