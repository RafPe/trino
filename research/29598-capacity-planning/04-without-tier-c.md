# What we get without Tier C

It depends on where the estimator gets its numbers.

**If the estimator reads the root cost (`getMaxMemory()`):** Tier C is required. Only joins,
filters and projections with no GROUP BY, ORDER BY or window function get a number. In TPC-DS
that's close to 0% of queries.

**If the estimator reads per-node statistics (`PlanNodeStatsEstimate` from `StatsAndCosts`)**,
most of the gap closes without touching the cost-based optimizer. Statistics rules exist in
`io.trino.cost` for: Aggregation (PARTIAL/INTERMEDIATE pass their source statistics through,
`AggregationStatsRule:59`), AssignUniqueId, DistinctLimit, DynamicFilterSource, EnforceSingleRow,
Exchange, Filter, Join, Limit, Output, Project, RemoteSource, RowNumber, Sample, SemiJoin,
SimpleFilterProjectSemiJoin, Sort, SpatialJoin, TableScan, TopNRanking, TopN, Union and Values.

| Plan shape | Cost | Statistics | Estimate without Tier C? |
|---|---|---|---|
| Joins (build hash tables) | ✅ | ✅ | ✅ |
| GROUP BY (partial and final aggregation) | ❌ partial is NaN | ✅ | ✅ |
| ORDER BY / TopN / DistinctLimit / TopNRanking | ❌ | ✅ | ✅ |
| INSERT / CTAS | ❌ | ❌ | ✅ (skip the writer, estimate the query feeding it) |
| Window functions, COUNT(DISTINCT) (MarkDistinct), UNNEST, ROLLUP/CUBE (GroupId, multiple grouping sets) | ❌ | ❌ no statistics rule | ⚠️ the subtree below only, flagged as a lower bound |

## What we still get
- Everything from A+B: the non-blocking wait, the summed-demand and running-query gauges, the planning-time fix, the "infeasible" result, and skipping fault-tolerant execution.
- A real memory estimate for queries built from joins, aggregations and sorts. That covers the main causes of `EXCEEDED_LOCAL_MEMORY_LIMIT`: hash-join build sides and aggregation state.
- No risk of changing query plans. The estimator only reads statistics the planner already computed.
- Logging of predicted vs. actual memory, to calibrate the multiplier.

## What we don't get
- Estimates for window functions, COUNT(DISTINCT), UNNEST and ROLLUP/CUBE. These need new statistics rules, which both approaches require (about 250 lines).
- Better costs for other planner code, since the cost-based optimizer is unchanged.
- One shared memory model: ours lives in the estimator. Maintainers may prefer the cost-based optimizer's model.

## Comparison

| Piece | Statistics-based estimator (WS2) | Tier C cost visitors |
|---|---|---|
| GROUP BY, ORDER BY, TopN, joins | ✅ works now | ✅ after PR 1 |
| Window, COUNT(DISTINCT), UNNEST, ROLLUP/CUBE | ❌ needs new statistics rules | ❌ needs the same statistics rules |
| Risk to plan choice | none | none with the calculator split, real without it |
| Upstream dependency | none | coordinate with #31287, get maintainer agreement |

**Recommendation:**
1. Implement WS1–WS3 with the statistics-based estimator.
2. Narrow Tier C to the missing statistics rules, one node per commit with tests.
3. Hold the cost-visitor PRs until maintainers respond and #31287 settles.
