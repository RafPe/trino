# Agent report: memory estimate and per-worker capacity

Opus research agent, 2026-11-08. Paths are relative to `core/trino-main/src/main/java/io/trino/`.
The NaN finding in §1 was re-checked by hand in `cost/CostCalculatorUsingExchanges.java`.

## 1. Is a whole-plan estimate available after planning?
- `LogicalPlanner.plan` always builds `StatsAndCosts` (`LogicalPlanner.java:303-309`) using `CachingCostProvider` and the `CostCalculatorUsingExchanges` bound at `CoordinatorModule.java:338`. You read it as `plan.getStatsAndCosts().getCosts().get(plan.getRoot().getId())`, and it is available at `SqlQueryExecution.java:503`.
- `collect_plan_statistics_for_all_queries` (off by default, `OptimizerConfig.java:56`) only controls whether new table stats are fetched. When it is off, tables not looked up during optimization get `TableStatistics.empty()` (`LogicalPlanner.java:294-302`). Building the costs is a cheap tree walk over cached data.
- The root cost is cumulative. Each node folds in its sources through `costForStreaming`, `costForAccumulation` and `costForLookupJoin` (`CostCalculatorUsingExchanges.java:303-358`).
- `getMaxMemory` is a modelled peak of memory held at the same time, summed over the whole cluster. It is not per node. For a lookup join, probe and build peaks are added together (line 342).
- It only counts hash tables. A FINAL/SINGLE aggregation is charged its output size (lines 166-177). A join is charged its build size times a multiplier (`CostCalculatorWithEstimatedExchanges.java:278-297`). Exchanges and scans are charged 0 bytes (line 181), so buffers, page sizes and operator overhead are missing.
- `getMaxMemoryWhenOutputting` is internal plumbing for the peak calculation and not useful for this feature.
- **Blocker:** `visitPlan` returns `unknown()` (NaN) for every node type without its own visitor (lines 99-102). That includes Sort, TopN, Window, MarkDistinct, TableWriter/TableFinish, Unnest and DistinctLimit. PARTIAL aggregations also return unknown (lines 168-170). NaN carries up to the root.
- So the root estimate is NaN for nearly every query with GROUP BY (once it is split into partial and final), ORDER BY, window functions, or INSERT/CTAS. It is also NaN when `enable_stats_calculator=false`, when the cost calculator throws, or when a connector has no stats.
- Estimate: well over 50% of production queries would get no number, and a 200MB floor would then decide them silently.

## 2. Stages and distribution
- The model doesn't split by fragment or stage. A hash-partitioned build is charged the full build size once, with no division by `hash_partition_count`.
- A broadcast build is charged `buildSize × estimateSourceDistributedTaskCount`, which is the current active node count or the `cost_estimation_worker_count` session property (`TaskCountEstimator.java:65-75`).
- So the estimate already depends on today's cluster size, and the formula is circular: adding workers raises the broadcast term.
- A broadcast build is the same size on every node. If buildSize is larger than per-node capacity, no number of workers helps.
- `estimate / perWorkerCapacity` assumes even, perfectly divisible spread. That is wrong for broadcast, for skewed partitions, and for single-node stages (gather, final TopN, the coordinator-only root stage).
- A sounder formula: per-node need ≈ Σ(broadcast builds) + Σ(partitioned state) / min(N, hash partitions). Solve for N. If the broadcast part alone is larger than capacity, report "infeasible".

## 3. Capacity side
- `ClusterMemoryManager.getWorkersMemoryInfo()` (`ClusterMemoryManager.java:489`) returns per-node `MemoryInfo`, whose `MemoryPoolInfo` has `getMaxBytes`, `getFreeBytes` and `getReservedBytes` (trino-spi `MemoryPoolInfo.java:55-73`).
- `ClusterMemoryPool` exposes total, free and reserved bytes summed across nodes, plus a node count (`ClusterMemoryPool.java:76-106`).
- Pool size per node is heap minus `memory.heap-headroom-per-node` (`LocalMemoryManager.java:51`), so mixed hardware is visible per node.
- The limit that actually stops a query on one node is `query.max-memory-per-node` (`NodeMemoryConfig.java:42`). That value lives in each worker's own config and isn't reported to the coordinator. Use `min(pool maxBytes, coordinator's max-memory-per-node)` as an approximation.
- The cold-start fallback only matters when no worker has reported yet.
- `query.max-memory` and `query.max-total-memory` (`MemoryManagerConfig.java:57-80`, enforced at `ClusterMemoryManager.java:210-221`) are cluster-wide caps that more workers can't lift. If a reliable estimate exceeds them, failing fast or warning is reasonable, but only as opt-in. Queries with retry policy TASK skip these caps (line 195).

## 4. Existing mechanisms
- `required_workers_count` / `query-manager.required-workers` with `ClusterSizeMonitor.waitForMinimumWorkers` (`ClusterSizeMonitor.java:97`) already does "wait for N workers, then fail with a timeout". It runs before planning, in `LocalDispatchQuery.waitForMinimumWorkers` (`LocalDispatchQuery.java:123`). The new feature should reuse this future and be framed as setting the required count dynamically.
- Nothing in execution reads cost-based optimizer memory today. `getStatsAndCosts` has no callers outside the planner and fragmenter.
- The fault-tolerant execution estimators (`PartitionMemoryEstimator`, `ExponentialGrowthPartitionMemoryEstimator`, `BinPackingNodeAllocatorService`) work from runtime peak usage and error history, not from the cost model. They don't conflict, but they don't reuse each other either. With retry policy TASK, the bin-packing allocator already queues work against real per-node memory, so pre-flight should probably be skipped for TASK.
- The low-memory killers act after the fact. A pre-flight check doesn't replace them.

## 5. Accuracy risk
- Under-prediction is likely. Stats estimate row counts and sizes, not Java heap; overheads are excluded; cardinality errors compound up the plan. The 2.0 safety multiplier is arbitrary.
- Over-prediction happens with broadcast scaling and spill-capable operators.
- Recommendations:
  - Treat NaN as "unknown → no-op". Don't let the floor stand in for a real estimate.
  - Log or emit the estimate next to actual peak memory (QueryStats) so it can be calibrated.
  - Put this behind an internal `QueryCapacityEstimator` interface rather than a public SPI at first.
  - Keep it off by default.

## Changes needed
- New estimator plus config (~300-400 lines). It should walk the fragmented `SubPlan` and treat partitioned and broadcast memory separately.
- `SqlQueryExecution` or `LocalDispatchQuery`: wire in the estimate and an async wait (~80-150 lines).
- Per-node capacity helper in `ClusterMemoryManager` (~40 lines).
- Session properties in `SystemSessionProperties` (~40 lines).
- Tests (~400+ lines).
- Optional but needed for real coverage: visitors in `CostCalculatorUsingExchanges` for Sort, TopN, Window, partial aggregation and TableWriter (~200-400 lines plus plan-test expectation updates).
