# Research: pre-flight cluster capacity planning (trinodb/trino#29598)

This directory collects the research that I did on 2026-11-08 into proposal
[trinodb/trino#29598](https://github.com/trinodb/trino/issues/29598). The proposal is to wait for
enough workers before a query starts, based on its estimated memory. External autoscalers can then
scale the cluster ahead of time.

I did the research against `trinodb/trino` master at `8ae1f91f853` (version `485-SNAPSHOT`).
All `file:line` references point at that commit.

I used AI tools to speed up the code research. I checked the main findings against the source code myself.

## Contents

| File | What it holds |
|---|---|
| [00-design-and-decisions.md](00-design-and-decisions.md) | **Start here.** Goal, upstream discussion (issues and Slack thread), decisions with reasons, architecture, results, open questions |
| [01-issue.md](01-issue.md) | Snapshot of the issue text and its comments |
| [02-scope-synthesis.md](02-scope-synthesis.md) | Main conclusions: what the proposal gets wrong about the code, design problems, and scope tiers A/B/C |
| [03-implementation-plan.md](03-implementation-plan.md) | Phased plan, parallel workstreams, local build environment, and branches |
| [04-without-tier-c.md](04-without-tier-c.md) | What we get without Tier C: a statistics-based estimator versus root cost |
| [05-tier-c-assessment.md](05-tier-c-assessment.md) | Assessment of Tier C (cost-based optimizer visitors) |
| [reports/05-second-review.md](reports/05-second-review.md) | A second, independent review of the proposal against the code (verdict: structurally flawed as proposed), and a comparison with our findings |
| [06-upstream-overlap.md](06-upstream-overlap.md) | Upstream issues/PRs that overlap our work, and bundling recommendations (2026-11-11) |
| [07-prior-art-memory-prediction.md](07-prior-art-memory-prediction.md) | Prior art on predicting query memory: PrestoDB HBO, industry, academic, Trino internals (2026-11-11) |
| [08-karpenter-nodeclaim-analysis.md](08-karpenter-nodeclaim-analysis.md) | Should the PoC create Karpenter NodeClaims directly? Timing, risks, better options (2026-11-13) |
| [09-image-on-new-nodes.md](09-image-on-new-nodes.md) | How to get the Trino image onto new nodes faster. Comparison of the options, with CNCF status (2026-11-13) |
| [reports/](reports/) | Raw code-analysis reports for the four areas (lifecycle, cost estimate, ClusterSizeMonitor/scheduling, config/tests/docs) |

## Summary

- **The goal is sound, but the design in the issue won't work as written.**
  - A query cannot move back from `PLANNING` to `WAITING_FOR_RESOURCES`.
  - The issue names a timeout property that doesn't exist. The real one is `query-manager.required-workers-max-wait`.
  - The timeout error code it proposes is already the one used.
  - `PlanCostEstimate.getMaxMemory()` is NaN for most real plans.
- **A usable version is a 2–3 PR effort of about 1.5–2k lines** (Tiers A+B). An optional Tier C extends the cost-based optimizer.
- **Key insight:** an estimator that reads per-node *statistics* instead of root *costs* covers GROUP BY, ORDER BY, TopN and joins without changing the cost-based optimizer. Window functions, COUNT(DISTINCT) (MarkDistinct), UNNEST and ROLLUP/CUBE (GroupId) need new statistics rules either way.
- **Upstream overlap:** open PR [trinodb/trino#31287](https://github.com/trinodb/trino/pull/31287) (wendigo) touches the same cost and statistics classes.

## Corrections

- New statistics rules are **not** plan-neutral: about 20 optimizer rules read statistics directly. See the note at the top of `05-tier-c-assessment.md`. The WS2 estimator itself (read-only) is still plan-neutral.

- `SqlQueryExecution.start()` runs on the cached `dispatcher-query-%s` pool, not the `query-execution` pool (found by the second review on 2026-11-11).

- Only `ReorderJoins` uses the cost calculator for plan selection. The join distribution rules use statistics. See the note in `05-tier-c-assessment.md` (2026-11-11).

## Status (2026-11-13)

- [x] Corrections and questions posted on #29598. New issue #32089 opened for the missing cost/stats estimates. No maintainer reply yet.
- [x] Direction agreed in the Slack thread: history-based prediction without Trino core changes.
- [x] PoC built and tested on docker compose and on kind + KEDA: branch `RafPe/memory-history-poc`.
- [x] PoC reviewed twice. Findings fixed.
- [ ] Collect-only run of the listener on real traffic.
- [ ] Share results in the Slack thread.
- [ ] Review the small upstream-candidate branches (`RafPe/stats-*`, `RafPe/docs-required-workers`, `RafPe/capacity-ws1-gauges`). No PRs opened.
- [ ] Coordinate with #31287 before changes to `AggregationStatsRule` or the cost calculator.
- On hold: in-core work (`RafPe/capacity-ws2-estimator`, `RafPe/capacity-ws3-async-start`).
