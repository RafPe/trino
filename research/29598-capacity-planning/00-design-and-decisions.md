# Design and decisions: proactive scale-up for memory-heavy Trino queries

Status: 2026-11-13. This is the technical summary of the work. It records the goal, the upstream
discussion, the options, the decisions and their reasons. The numbered files next to it give the details.

## 1. Goal

Add Trino workers **before** a memory-heavy query runs, instead of reacting after it fails with
`EXCEEDED_LOCAL_MEMORY_LIMIT`. Autoscaling on CPU or memory is reactive: it acts on pressure, which is too
late for the query that caused it.

## 2. Starting point: trinodb/trino#29598

Proposal by another contributor:

1. After planning, estimate memory from `PlanCostEstimate.getMaxMemory()`.
2. Move the query back to `WAITING_FOR_RESOURCES`.
3. Wait with `ClusterSizeMonitor.waitForMinimumWorkers`.
4. Expose the worker count through the `RequiredWorkers` JMX gauge for KEDA/HPA.

I checked the proposal against the code (master `8ae1f91f853`). It does not work as written
([02-scope-synthesis.md](02-scope-synthesis.md), [reports/](reports/)):

| Proposal | Code |
|---|---|
| Go back to `WAITING_FOR_RESOURCES` after planning | Not possible: that state is before `PLANNING`. The state machine ignores the transition. |
| `PlanCostEstimate.getMaxMemory()` as the estimate | NaN for most plans (no cost for partial aggregation, Sort, TopN, Window, …). It is also a cluster-wide number and grows with the worker count for broadcast joins. |
| `RequiredWorkers` gauge as the scaling signal | It is the maximum over *waiting* queries and drops to 0 when they start. |
| Timeout property `query.required-workers-max-wait-time` | Does not exist. The real one is `query-manager.required-workers-max-wait`. |
| Timeout error to be changed to `GENERIC_INSUFFICIENT_RESOURCES` | Already that error. |

A second, independent review reached the same verdict ([reports/05-second-review.md](reports/05-second-review.md)).

## 3. What I posted upstream

| Where | What | Link |
|---|---|---|
| #29598 | Comment asking the author for PoC details (2026-11-08) | https://github.com/trinodb/trino/issues/29598#issuecomment-6722472265 |
| #29598 | Comment with the code mismatches above and questions (2026-11-09) | https://github.com/trinodb/trino/issues/29598#issuecomment-6748664140 |
| New issue #32089 | "Cost estimates are unknown for most plans with aggregations, sorting, or window functions" (2026-11-11) | https://github.com/trinodb/trino/issues/32089 |
| #32089 | Comment linking related issues and PRs | https://github.com/trinodb/trino/issues/32089#issuecomment-6788999899 |

No maintainer reply on either issue as of 2026-11-13.

## 4. Trino Slack thread (summary)

The thread is not public. This is a summary of the technical content, without names.

**My post** gave the goal and the reasons why #29598 cannot work as written. It listed four possible paths:

1. Signal only: expose demand metrics, and an external autoscaler decides.
2. Plan-based wait inside Trino.
3. A pluggable admission policy.
4. History-based admission.

It asked which path is acceptable, and how to sequence the work with PR #31287.

| Question from the thread | My answer |
|---|---|
| "How would you estimate memory query needs? That's the foundational question" | No validated method yet. Three options: plan statistics, history of earlier runs, runtime adjustment (what FTE does per task). |
| "`io.trino.cost.PlanCostEstimate#maxMemory` ?" | Not usable as is. It is NaN for most plans with GROUP BY / ORDER BY / window functions (#32089). It is one cluster-wide number, but queries fail on a per-node limit. It multiplies broadcast builds by the current worker count. It counts scans and exchanges as 0. |
| "4 feels the simplest without trino changes. The classifier for query cost can also be as simple or as complex as you'd like as well" | Agreed. Decision to build a PoC of path 4 without Trino core changes, measure it, and share the numbers. |
| "This is an interesting approach. Will you track CTEs or other subqueries separately?" | Not in the first version: the fingerprint covers the whole query. To track parts, I need plan-based fingerprints and memory per plan part. PrestoDB's history-based optimizer uses fingerprints per plan node. Trino reports peak memory per query and per stage only. Draft reply prepared. Not confirmed as posted. |

## 5. Decisions

| # | Decision | Reason | Alternatives not chosen |
|---|---|---|---|
| D1 | Estimate memory from **history of the same query shape**, not from the plan. | Plan cost is NaN for most plans. Cardinality errors grow with every join. History works for repeated queries and needs no engine change. The thread suggested it as the simplest start. Prior art: PrestoDB HBO fingerprints, Redshift "Stage" (cache of past runs first). | Plan statistics per stage (needs #32089 and missing stats rules). ML model (no data yet). |
| D2 | **No Trino core changes** for the PoC: event-listener plugin + external service. | Runs on a stock release. Quick to test. Nothing to maintain in a fork. | In-core hook after planning (needs async `start()` and state/timer changes. two earlier admission PRs, #29944/#29951, were closed without review). |
| D3 | Fingerprint = **normalized SQL text + table list**, SHA-256. Literals → `?`, case/whitespace/comments ignored, `LIMIT` value kept, IN-lists collapsed. | The plugin and the predictor can both compute it from SQL alone, before submission, with the same result. | Plan-based fingerprint (the plan is only available after planning / at completion). |
| D4 | Prediction = **largest peak of the last 10 successful runs**. After a memory failure that is newer than the last success: largest known peak × 2. | Simple and explainable. The peak of a failed run is only a lower bound, so the prediction grows from it. A success after a failure resolves the failure. I changed this rule after a test showed permanent over-provisioning. | p90 / regression on input size (no input-size estimate before the run). |
| D5 | Workers = `ceil(predicted × 1.5 / query.max-memory-per-node)`, clamped to min/max. | Trino reports no per-node peak. Memory is not spread evenly, so the formula needs a safety factor. Example: TPC-H q18 needs 3 workers at 1.7 GB with a 1 GB limit. | Per-node peak (not available without a Trino change). |
| D6 | **Trino itself holds the query**, with the existing session properties `required_workers_count` and `required_workers_max_wait_time`. | No new wait mechanism. Stock Trino already has it. | Gate that holds the HTTP request (client timeouts). New state in Trino. |
| D7 | Scaling signal = **a number that the predictor serves** (`/v1/required-workers`), read by KEDA (`metrics-api` trigger). | The `RequiredWorkers` gauge drops to 0 when queries start. Reservations hold capacity for the whole run. | JMX gauge. Queue-length triggers. |
| D8 | Gate is **client-side** in the PoC. | Smallest thing that proves the loop. | Trino Gateway external routing (on timeout it routes the query anyway). A transparent proxy (not built yet). |
| D9 | **Unknown query shape → no prediction, no wait.** | Never make things worse for queries we know nothing about. | Default estimate / floor (would delay or mis-size unknown queries). |
| D10 | Do **not** create Karpenter NodeClaims directly (for now). | Saves under ~10 s of a 1.5–3 min path. Loses Karpenter's retry, cleanup and limits ([08-karpenter-nodeclaim-analysis.md](08-karpenter-nodeclaim-analysis.md)). | Static NodePool driven by the predictor. Image pre-loading. Earlier prediction. |

## 6. Architecture of the PoC

Code and walkthrough: branch `RafPe/memory-history-poc`, `poc/memory-history/README.md` and `PLAN.md`
(contracts for the fingerprint, the history table, the predictor API, the gate and the cluster shape).

```
client → gate → predictor → history DB (PostgreSQL)
           │        ▲
           │        └── autoscaler (KEDA) reads "required workers", adds Trino workers
           └→ Trino coordinator (holds the query until workers joined) → workers
                    └── event-listener plugin writes fingerprint + peak memory after every query
```

## 7. Results so far (test workload, not real traffic)

TPC-DS/TPC-H workload, 13 query shapes × 3 literal variants, cluster with 1 GB per-node query memory.

- Literal variants of one shape share one fingerprint (13 shapes → 13 fingerprints).
- 9 of 9 heavy variants fail without the gate on 2 workers. All gated heavy runs finish.
- Queries ≥ 100 MB, predicted from successful runs: predicted ÷ actual 0.96–1.25, median 1.06 (in-sample).
  Cross-check with only the other literal variants: 0.98–1.25, median 1.05.
- Kubernetes (kind + KEDA): a heavy query fails on 2 workers. With the gate, the cluster grows to 6, the query waits
  about 30 s and finishes.
- Every shape repeats in this workload, so the hit rate on real traffic is unknown.

## 8. Open questions

1. How often do real queries repeat, and how much does memory vary within one fingerprint with real literals?
2. Trino does not report per-node peak memory per query. This number would remove the safety factor.
3. The gate must become transparent (proxy or Trino Gateway) to cover BI and JDBC clients.
4. Shared CTEs/subqueries across different queries need plan-based fingerprints.
5. Cold start: new nodes take roughly 1.5–3 min (node start, image pull, JVM). Which part should I shorten first?
6. Upstream: is #32089 (cost/stats for missing plan nodes) wanted, and how to sequence with PR #31287?

## 9. Next steps

1. Collect-only run of the listener on real traffic (answers question 1).
2. Share PoC and real-traffic numbers in the Slack thread.
3. Shorten the cold start (image on new nodes, earlier prediction) after I measure it on EKS.
4. Transparent gate.
5. Propose Trino-side pieces with data: per-node peak in the completed-query event, plan fingerprint, optional post-planning hook.

## 10. Where things are

| Topic | Location |
|---|---|
| This summary | `research` branch, this file |
| Code-level analysis of #29598, scope tiers | `02`–`05`, `reports/` |
| Upstream overlap | `06-upstream-overlap.md` |
| Prior art on memory prediction | `07-prior-art-memory-prediction.md` |
| Karpenter NodeClaim analysis | `08-karpenter-nodeclaim-analysis.md` |
| PoC code, contracts, results | branch `RafPe/memory-history-poc`, `poc/memory-history/` |
| Small upstream-candidate branches (stats rules, docs, gauge) | `RafPe/stats-*`, `RafPe/docs-required-workers`, `RafPe/capacity-ws1-gauges` (not reviewed, no PRs opened) |
| Draft texts for issues and Slack | local only, git-ignored `drafts/` |
