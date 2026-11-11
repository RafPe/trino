# Research: pre-flight cluster capacity planning (trinodb/trino#29598)

This directory collects the research done on 2026-11-08 into proposal
[trinodb/trino#29598](https://github.com/trinodb/trino/issues/29598): wait for enough workers
before a query starts, based on its estimated memory, so external autoscalers can scale the
cluster ahead of time.

The research was done against `trinodb/trino` master at `8ae1f91f853` (version `485-SNAPSHOT`).
All `file:line` references point at that commit.

## Contents

| File | What it holds |
|---|---|
| [01-issue.md](01-issue.md) | Snapshot of the issue text and its comments |
| [02-scope-synthesis.md](02-scope-synthesis.md) | Main conclusions: what the proposal gets wrong about the code, design problems, and scope tiers A/B/C |
| [03-implementation-plan.md](03-implementation-plan.md) | Phased plan, parallel workstreams, local environment, and child workspaces |
| [04-without-tier-c.md](04-without-tier-c.md) | What we get without Tier C: a statistics-based estimator versus root cost |
| [05-tier-c-assessment.md](05-tier-c-assessment.md) | Tier C (cost-based optimizer visitors) assessment by an Opus coordinator with Sonnet researchers |
| [reports/05-codex-structural-review.md](reports/05-codex-structural-review.md) | Independent Codex review (verdict: structurally flawed as proposed) and a comparison with our findings |
| [06-upstream-overlap.md](06-upstream-overlap.md) | Upstream issues/PRs that overlap our work, and bundling recommendations (2026-11-11) |
| [07-prior-art-memory-prediction.md](07-prior-art-memory-prediction.md) | Prior art on predicting query memory: PrestoDB HBO, industry, academic, Trino internals (2026-11-11) |
| [reports/](reports/) | Raw reports from the four Opus research agents (lifecycle, cost estimate, ClusterSizeMonitor/scheduling, config/tests/docs) |

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

- `SqlQueryExecution.start()` runs on the cached `dispatcher-query-%s` pool, not the `query-execution` pool (found by the Codex review on 2026-11-11).

- Only `ReorderJoins` consumes the cost calculator for plan selection; the join distribution rules use statistics. See the note in `05-tier-c-assessment.md` (2026-11-11).

## Status and decisions pending (as of 2026-11-08)

- [ ] Launch WS1–WS3 implementation, with the WS2 estimator based on statistics.
- [ ] Narrow the Tier C workspace to the missing statistics rules (Window, MarkDistinct, GroupId, Unnest, grouping-set aggregation).
- [ ] Post corrections and questions on #29598 to get maintainers' input (see the questions in `05-tier-c-assessment.md` §6 and `02-scope-synthesis.md`).
- [ ] Coordinate with #31287 before touching `AggregationStatsRule` or the cost calculator.
