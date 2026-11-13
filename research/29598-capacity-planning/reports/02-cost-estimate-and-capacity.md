# Code analysis: memory estimate and per-worker capacity

Date: 2026-11-08. The analysis refers to trinodb/trino master `8ae1f91f853`. Paths are relative to `core/trino-main/src/main/java/io/trino/`.
I checked the NaN finding in §1 a second time by hand in `cost/CostCalculatorUsingExchanges.java`.

## 1. Is a whole-plan estimate available after planning?
- `LogicalPlanner.plan` always builds `StatsAndCosts` (`LogicalPlanner.java:303-309`). It uses `CachingCostProvider` and the `CostCalculatorUsingExchanges` that `CoordinatorModule.java:338` binds. You read the root cost as `plan.getStatsAndCosts().getCosts().get(plan.getRoot().getId())`. It is available at `SqlQueryExecution.java:503`.
- `collect_plan_statistics_for_all_queries` (off by default, `OptimizerConfig.java:56`) only controls whether the planner fetches new table stats. When it is off, tables that optimization did not look up receive `TableStatistics.empty()` (`LogicalPlanner.java:294-302`). To build the costs is a cheap tree walk over cached data.
- The root cost is cumulative. Each node adds the cost of its sources through `costForStreaming`, `costForAccumulation` and `costForLookupJoin` (`CostCalculatorUsingExchanges.java:303-358`).
- `getMaxMemory` is a modelled peak of memory held at the same time, summed over the whole cluster. It is not per node. For a lookup join, the model adds the probe peak and the build peak together (line 342).
- It counts only hash tables:
  - The model charges a FINAL/SINGLE aggregation its output size (lines 166-177).
  - The model charges a join its build size times a multiplier (`CostCalculatorWithEstimatedExchanges.java:278-297`).
  - The model charges 0 bytes for exchanges and scans (line 181). Thus, buffers, page sizes and operator overhead are missing.
- `getMaxMemoryWhenOutputting` is internal plumbing for the peak calculation. It is not useful for this feature.
- **Blocker:** `visitPlan` returns `unknown()` (NaN) for each node type that has no visitor of its own (lines 99-102). These include Sort, TopN, Window, MarkDistinct, TableWriter/TableFinish, Unnest and DistinctLimit. PARTIAL aggregations also return unknown (lines 168-170). NaN goes up to the root.
- Thus, the root estimate is NaN for almost all queries with these features:
  - GROUP BY (after the planner splits it into partial and final).
  - ORDER BY.
  - Window functions.
  - INSERT/CTAS.
- The root estimate is also NaN in these conditions:
  - `enable_stats_calculator=false`.
  - The cost calculator throws an exception.
  - A connector has no stats.
- Estimate: Much more than 50% of production queries would have no number. The 200MB floor would then decide them silently.

## 2. Stages and distribution
- The model does not split by fragment or stage. The model charges a hash-partitioned build the full build size one time. It does not divide by `hash_partition_count`.
- The model charges a broadcast build `buildSize × estimateSourceDistributedTaskCount`. This value is the current active node count or the `cost_estimation_worker_count` session property (`TaskCountEstimator.java:65-75`).
- Thus, the estimate already depends on the current cluster size, and the formula is circular. More workers make the broadcast term larger.
- A broadcast build has the same size on each node. If buildSize is larger than the per-node capacity, no number of workers helps.
- `estimate / perWorkerCapacity` assumes an even spread that divides perfectly. That assumption is wrong for these cases:
  - Broadcast.
  - Skewed partitions.
  - Single-node stages (gather, final TopN, the root stage that runs only on the coordinator).
- A better formula is: per-node need ≈ Σ(broadcast builds) + Σ(partitioned state) / min(N, hash partitions). Solve for N. If the broadcast part alone is larger than the capacity, report "infeasible".

## 3. Capacity side
- `ClusterMemoryManager.getWorkersMemoryInfo()` (`ClusterMemoryManager.java:489`) returns `MemoryInfo` for each node. Its `MemoryPoolInfo` has `getMaxBytes`, `getFreeBytes` and `getReservedBytes` (trino-spi `MemoryPoolInfo.java:55-73`).
- `ClusterMemoryPool` exposes total, free and reserved bytes summed across nodes. It also exposes a node count (`ClusterMemoryPool.java:76-106`).
- The pool size on each node is the heap minus `memory.heap-headroom-per-node` (`LocalMemoryManager.java:51`). Thus, the coordinator can see mixed hardware per node.
- The limit that actually stops a query on one node is `query.max-memory-per-node` (`NodeMemoryConfig.java:42`). That value is in the config of each worker, and workers do not report it to the coordinator. As an approximation, use `min(pool maxBytes, coordinator's max-memory-per-node)`.
- The cold-start fallback is only important when no worker has reported yet.
- `query.max-memory` and `query.max-total-memory` (`MemoryManagerConfig.java:57-80`, enforced at `ClusterMemoryManager.java:210-221`) are cluster-wide caps. More workers cannot increase them. If a reliable estimate is more than these caps, it is reasonable to fail fast or to warn, but only as opt-in. Queries with retry policy TASK skip these caps (line 195).

## 4. Existing mechanisms
- `required_workers_count` / `query-manager.required-workers` with `ClusterSizeMonitor.waitForMinimumWorkers` (`ClusterSizeMonitor.java:97`) already does "wait for N workers, then fail with a timeout". It runs before planning, in `LocalDispatchQuery.waitForMinimumWorkers` (`LocalDispatchQuery.java:123`). The new feature should use this future again. Describe the feature as a dynamic setting of the required count.
- Today, no execution code reads the memory from the cost-based optimizer. `getStatsAndCosts` has no callers outside the planner and the fragmenter.
- The fault-tolerant execution estimators (`PartitionMemoryEstimator`, `ExponentialGrowthPartitionMemoryEstimator`, `BinPackingNodeAllocatorService`) use runtime peak usage and error history, not the cost model. They do not conflict with the cost model, but they also do not share code with it. With retry policy TASK, the bin-packing allocator already queues work against real per-node memory. Thus, the pre-flight check should probably not run for TASK.
- The low-memory killers act after the problem occurs. A pre-flight check does not replace them.

## 5. Accuracy risk
- Under-prediction is likely, for these reasons:
  - Stats estimate row counts and sizes, not Java heap.
  - The model excludes overheads.
  - Cardinality errors become larger at each level up the plan.
  - The 2.0 safety multiplier is arbitrary.
- Over-prediction occurs with broadcast scaling and with operators that can spill.
- Recommendations:
  - Treat NaN as "unknown → no-op". Do not let the floor replace a real estimate.
  - Log or emit the estimate next to the actual peak memory (QueryStats). Then you can calibrate it.
  - At first, put the estimate behind an internal `QueryCapacityEstimator` interface, not a public SPI.
  - Keep the feature off by default.

## Changes needed
- A new estimator plus config needs ~300-400 lines. It should walk the fragmented `SubPlan` and treat partitioned memory and broadcast memory separately.
- `SqlQueryExecution` or `LocalDispatchQuery` must connect the estimate and an async wait. The estimate is ~80-150 lines.
- A per-node capacity helper in `ClusterMemoryManager` needs ~40 lines.
- Session properties in `SystemSessionProperties` need ~40 lines.
- Tests need ~400+ lines.
- Visitors in `CostCalculatorUsingExchanges` for Sort, TopN, Window, partial aggregation and TableWriter are optional, but real coverage needs them. They need ~200-400 lines, plus updates to the expected results of plan tests.
