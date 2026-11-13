# Upstream overlap and bundling (checked 2026-11-11)

I searched trinodb/trino issues and PRs for: stats rules, cost calculator, PlanCostEstimate, required workers, autoscaling, admission control and ClusterSizeMonitor.

## Our own posts
- **#32089** (RafPe, 2026-11-11): "Cost estimates are unknown for most plans…". I posted it on the feature-request template, with the corrected facts:
  - Only `ReorderJoins` uses the cost calculator.
  - 9 of the 15 uncovered node types have no stats.

  All stats-rule PRs should reference it.

## Direct overlap with our branches

| Upstream | State | Overlaps | Recommendation |
|---|---|---|---|
| **#28902** "Document missing query management configuration properties" (jmealo, +190 lines, `properties-query-management.md`) | Closed 2026-06-24 **for staleness**, with no review and the CLA signed | `RafPe/docs-required-workers`: it documents `query-manager.required-workers*` plus 12 more properties | Do not duplicate it. Ask the author on #28902 to reopen it (and offer review help). Or supersede it with credit (`Co-authored-by`) and the same wider scope. Hold our docs PR until we make this decision. |
| **#29944** "Resource-aware query admission control (memory/vCPU based)" (+1334 lines) | Closed 2026-07-31, stale, no maintainer review | #29598 itself. It adds a `WAITING_FOR_RESOURCES` gate, chained before the min-worker wait, in `LocalDispatchQuery`. It estimates memory from a rolling average of the peak memory of recent queries. | Prior art for #29598. It shows that an admission gate *before* planning is possible, but with estimates from history, not from the plan. Cite it in the #29598 discussion. |
| **#29951** "Add pluggable AdmissionPolicy SPI" (+3189 lines) | Closed 2026-09-16, stale, no maintainer review | #29598: it replaces the hard-coded `ClusterSizeMonitor.waitForMinimumWorkers` gate with an SPI | A possible home for capacity planning as a plugin, which would need fewer core changes. But it is a large SPI that got no review. Ask maintainers if they want an admission extension point before we build on it. |
| **#4130 / #4131** (findepi): admit system-only and EXPLAIN queries when the cluster does not have the required worker count | Open since 2020 | The #29598 "trivial query bypass" question, and the finding of a second, independent review that existing bypasses still wait for one node | Link from #29598. |
| **#29208** "Skip worker availability check for coordinator-only queries" | Closed by its author. Queries like `SELECT DISTINCT … FROM system.metadata.catalogs` still need workers because of the aggregation, so it needs a planner change | Same bypass question | Evidence that a correct bypass needs knowledge at plan level, not only catalog checks. |

## Complementary (no bundling)
- **#31917 / #31918–#31920** (Iceberg partition statistics for row counts). Connector-level. It makes table-scan statistics cheap enough to leave on for Iceberg tables with many files. It shares no code with our engine-level stats rules. But it **raises the coverage of an estimator based on statistics**. Today, users turn statistics off to save planning time, and then our estimator gets NaN. Its point 3 relies on `DetermineJoinDistributionType` using output size from stats, which matches our correction. Worth a supportive mention, not a bundle.
- **#31287** (wendigo, open): statistics confidence and partitioned-join credit in cost. Already known. It touches `AggregationStatsRule`, `PlanCostEstimate`, `CostComparator` and `CostCalculatorWithEstimatedExchanges`. Sequence our work with it.
- **#31342** (merged 2026-11-10): "Speed up join reordering stats lookups" touches `PlanNodeStatsEstimate` and `Symbol`. **Rebase the stats-rule branches onto the latest master before you open PRs.**
- **#30815** (open): node groups that restrict queries to a subset of workers. If this PR merges, each capacity estimate would need to be per node group.
- **#18739** (memory-based autoscaler for the Helm chart) and **#30969** (worker autoscaling with bursty concurrent queries): evidence of user demand for #29598. Link them.
- **#51** (open): quantify the quality of the stats/cost calculator. Related to the calibration of estimates. Link from #32089.
- Historical:
  - **#14268** (EXPLAIN prints local cost. This matches our correction.)
  - **#11573** (prevent use of the wrong cost calculator before/after `AddExchanges`. This is relevant to the calculator-split design.)
  - **#847** (make `TaskCountEstimator` dynamic).

## Bundling summary
1. **Stats-rule PRs:** bundle under #32089. Each PR description references it. Nothing else upstream overlaps.
2. **Docs PR:** resolve against #28902 first.
3. **#29598:** add #29944, #29951, #4130/#4131, #29208, #18739 and #30969 to the comment draft as prior art and demand evidence.
4. **Iceberg #31917:** complementary. No bundling.
