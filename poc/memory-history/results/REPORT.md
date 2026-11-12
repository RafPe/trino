# Memory-history PoC report

Generated 2026-11-12T15:35:29+00:00 from 117 runs. Actual values from: PostgreSQL query_history (falling back to runs.jsonl per query).
Runs found in `query_history`: 117 of 117.

## Summary per pass

| pass | runs | gated | FINISHED | memory errors | other errors | known prediction | waited for workers (> 1 s) | workers added during the run |
|---|---|---|---|---|---|---|---|---|
| learn | 39 | 0 | 30 | 9 | 0 | 0 | 0 | 0 |
| predict | 78 | 78 | 77 | 0 | 1 | 78 | 4 | 4 |

## Hit rate (predict pass, gated)

78 of 78 queries had a known fingerprint: **100%**.

## Predicted vs actual peak user memory (hits that FINISHED)

Ratio = predicted / actual (1.0 is perfect, < 1 is under-prediction).

- n = 77, under-predicted: 11
- median: 1.06
- p90: 3.26
- min: 0.46, max: 47808.67
- worst: 47808.67 (tpch_q06 sf1 v2, predicted 4.38 MB, actual 96 B)

The same ratio by group. Queries under 100 MB are noisy (a few kB to a few MB) and do not matter for scaling.

| group | n | under-predicted | median | p90 | min | max |
|---|---|---|---|---|---|---|
| actual peak ≥ 100 MB, predicted from successful runs | 28 | 1 | 1.06 | 1.09 | 0.96 | 1.25 |
| actual peak ≥ 100 MB, predicted from a memory failure (peak × growth factor) | 8 | 1 | 2.39 | 2.49 | 0.98 | 2.51 |
| actual peak < 100 MB | 41 | 9 | 1.03 | 5304.83 | 0.46 | 47808.67 |

These ratios are **in-sample**: every query in the predict passes had already run with the same literals, and the prediction is the maximum over that history.

### Leave-one-variant-out

For each FINISHED run with a peak ≥ 100 MB, predict from the runs of the *other* literal variants of the same template only (maximum of their peaks). This is a retrospective cross-check, not a replay of the predictor: it uses successful runs from all passes (also later ones) and ignores the failure-growth rule.

- n = 45, under-predicted: 7
- median: 1.05, p90: 1.08, min: 0.98, max: 1.25

## Spread of actual peak within one template (FINISHED runs, all passes)

Literal variants of one template should share one fingerprint. max/min close to 1 means literals do not change memory much.

| template | schema | fingerprints | runs | min | max | max/min |
|---|---|---|---|---|---|---|
| tpcds_q03 | sf1 | 14d0337245cc | 9 | 3.22 MB | 7.86 MB | 2.44 |
| tpcds_q07 | sf10 | 4fea39f755ae | 9 | 48.78 MB | 53.54 MB | 1.10 |
| tpcds_q19 | sf10 | de79d9be986a | 9 | 1.24 GB | 1.35 GB | 1.08 |
| tpcds_q42 | sf1 | c1863ae0f56c | 9 | 20.64 MB | 31.48 MB | 1.53 |
| tpcds_q43 | sf10 | 4462c4f72db4 | 9 | 1.02 GB | 1.11 GB | 1.08 |
| tpcds_q55 | sf1 | 6c27069c1e2f | 8 | 4.08 MB | 5.18 MB | 1.27 |
| tpcds_q96 | sf1 | 5dd766167580 | 9 | 3.30 MB | 3.35 MB | 1.02 |
| tpch_q01 | sf1 | b19c4b1f01bc | 9 | 1.55 kB | 11.30 MB | 7443.77 |
| tpch_q06 | sf1 | ab8d4a96cbb8 | 9 | 96 B | 4.38 MB | 47808.67 |
| tpch_q09 | sf5 | f952e4a7da3e | 6 | 2.15 GB | 2.33 GB | 1.08 |
| tpch_q13 | sf10 | b8cc80f6c900 | 9 | 471.12 MB | 589.17 MB | 1.25 |
| tpch_q18 | sf5 | b1f2ec89c0b8 | 6 | 1.52 GB | 1.60 GB | 1.06 |
| tpch_q21 | sf5 | 38e26399cfb1 | 6 | 3.29 GB | 3.43 GB | 1.04 |

## Heavy queries

| template | schema | variant | pass | gated | state | error | peak | predicted | required | workers before → after | wait |
|---|---|---|---|---|---|---|---|---|---|---|---|
| tpch_q09 | sf5 | v0 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.94 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q09 | sf5 | v0 | predict | yes | FINISHED | - | 2.33 GB | 3.89 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q09 | sf5 | v0 | predict | yes | FINISHED | - | 2.15 GB | 2.33 GB | 4 | 6 → 6 | 0.0 s |
| tpch_q09 | sf5 | v1 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.84 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q09 | sf5 | v1 | predict | yes | FINISHED | - | 2.21 GB | 2.33 GB | 4 | 6 → 6 | 0.0 s |
| tpch_q09 | sf5 | v1 | predict | yes | FINISHED | - | 2.21 GB | 2.33 GB | 4 | 6 → 6 | 0.0 s |
| tpch_q09 | sf5 | v2 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.68 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q09 | sf5 | v2 | predict | yes | FINISHED | - | 2.22 GB | 2.33 GB | 4 | 6 → 6 | 0.0 s |
| tpch_q09 | sf5 | v2 | predict | yes | FINISHED | - | 2.28 GB | 2.33 GB | 4 | 6 → 6 | 0.0 s |
| tpch_q18 | sf5 | v0 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.67 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q18 | sf5 | v0 | predict | yes | FINISHED | - | 1.59 GB | 3.81 GB | 6 | 3 → 6 | 20.4 s |
| tpch_q18 | sf5 | v0 | predict | yes | FINISHED | - | 1.60 GB | 3.81 GB | 6 | 3 → 6 | 20.3 s |
| tpch_q18 | sf5 | v1 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.91 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q18 | sf5 | v1 | predict | yes | FINISHED | - | 1.52 GB | 3.81 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q18 | sf5 | v1 | predict | yes | FINISHED | - | 1.59 GB | 3.81 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q18 | sf5 | v2 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.58 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q18 | sf5 | v2 | predict | yes | FINISHED | - | 1.57 GB | 3.81 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q18 | sf5 | v2 | predict | yes | FINISHED | - | 1.54 GB | 3.81 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q21 | sf5 | v0 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.61 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q21 | sf5 | v0 | predict | yes | FINISHED | - | 3.29 GB | 3.21 GB | 5 | 6 → 6 | 0.0 s |
| tpch_q21 | sf5 | v0 | predict | yes | FINISHED | - | 3.30 GB | 3.43 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q21 | sf5 | v1 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.56 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q21 | sf5 | v1 | predict | yes | FINISHED | - | 3.43 GB | 3.29 GB | 5 | 6 → 6 | 0.0 s |
| tpch_q21 | sf5 | v1 | predict | yes | FINISHED | - | 3.36 GB | 3.43 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q21 | sf5 | v2 | learn | no | FAILED | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.59 GB | - | - | 2 → 2 | 0.0 s |
| tpch_q21 | sf5 | v2 | predict | yes | FINISHED | - | 3.30 GB | 3.43 GB | 6 | 6 → 6 | 0.0 s |
| tpch_q21 | sf5 | v2 | predict | yes | FINISHED | - | 3.42 GB | 3.43 GB | 6 | 6 → 6 | 0.0 s |

Heavy (template, schema, variant) that failed with a memory error without a gate on ≤ 2 workers **and** later succeeded gated with a known prediction: 9 of 9.

## Cold start (learn pass)

- 39 of 39 learn-pass queries had no prediction (unknown fingerprint or no gate) and ran unchanged.
- Of those, 9 failed with a memory error; these failures are recorded and drive the `memory_failure_growth` prediction in the next pass.
- 0 learn-pass queries were already known from an earlier variant of the same template in the same pass.

## Known limits

- The fingerprint is text-based: the same query on different tables/schemas is a different fingerprint, and literals that change the data volume (e.g. a wider date range) share one fingerprint.
- Prediction is the maximum of recent successful runs; a run that failed with a memory error only gives a lower bound, so the first gated run after a failure uses `FAILURE_GROWTH_FACTOR` and can still be too small.
- Cluster-wide peak memory is converted to workers by dividing by `query.max-memory-per-node`. This assumes memory is spread evenly over workers; skewed partitions or broadcast joins break this.
- Unknown fingerprints (cold start) run without a gate and can fail; they only help the next run.
- Reservations are held in memory by the predictor; a restart forgets them. The scaler scales down after 120 s without checking whether queries still run on the removed workers.
- The gate is client-side; clients that bypass it get no prediction.
