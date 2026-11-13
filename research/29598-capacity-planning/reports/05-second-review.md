# Second, independent review of #29598

- **Date:** 2026-11-11.
- **Inputs:** The review used only the issue text and a shallow checkout of trinodb/trino at `8ae1f91f85313e50fd72286810c52f4b058b1292`. It did **not** see my first analysis. Thus, the first analysis did not influence the verdict.
- **Original:** The original review has 891 words and 51 citations pinned to that commit.
- **This copy:** This copy is a transcription of the original. Paths are relative to `core/trino-main/src/main/java/io/trino/`.

## Verdict: structurally flawed as proposed

> Predictive admission is useful, but this requires lifecycle and capacity-model changes, not merely wiring. The proposed state transition fails; ordinary plans can have unknown memory cost; broadcast memory does not shrink with more workers; and the gauge represents pending thresholds, not reserved capacity. This is a source review, not validation of the Minikube results.

## Claims

| Claim | Assessment | Evidence (from the review) |
|---|---|---|
| Insert a wait after `planQuery` | Partially | The order is planning → dynamic-filter registration → distribution → starting/scheduler. Dispatch already occurred. `start()` runs synchronously through `QueryManager.createQuery` on the cached `dispatcher-query-%s` executor, which renames the thread `Query-…`. A call to the future API alone does not wait. To block on the future holds one thread per query. The existing wait uses callbacks and cancellation. `SqlQueryExecution.java:397–446`, `QueryManager.java:316–334`, `LocalDispatchQueryFactory.java:184–190`, `DispatchExecutor.java:52–54`, `LocalDispatchQuery.java:117–151` |
| Reuse `WAITING_FOR_RESOURCES` after planning | Does not hold | It comes before PLANNING, and the ordinal guard prevents a backward move. Resource-wait timing is already final. If the query stays in PLANNING, the wait counts as planning time, so `query.max-planning-time` can fail the query. Execution and run deadlines also continue. `QueryState.java:26–42`, `QueryStateMachine.java:1227–1248`, `QueryStateTimer.java:77–118`, `QueryTracker.java:181–202` |
| `RequiredWorkers` is a sufficient autoscaler target | Partially | It is the maximum pending threshold, not a sum or a reservation. Two queries that need N both pass at N. It shows zero while queries run. Requests that the cluster already satisfies never go into it. It is useful as a scale-up signal. It is not sufficient alone for capacity or scale-down. `ClusterSizeMonitor.java:97–108, 136–165` |
| `getMaxMemory()` exists after planning | Holds | `LogicalPlanner` builds `StatsAndCosts` for each node. Read the root cost of the saved `Plan`, not of the fragmented plan. `LogicalPlanner.java:304–310`, `StatsAndCosts.java:80–90`, `SqlQueryExecution.java:503–509`, `PlanCostEstimate.java:95–103` |
| Ordinary plans supply a usable memory number | Does not hold | Only about 14 visitor types exist. Sort, TopN, Window, TableWriter and others default to unknown. PARTIAL/INTERMEDIATE aggregation is explicitly unknown. NaN propagates, **including through `max(estimate, FLOOR)`**. `CostCalculatorUsingExchanges.java:99–294, 303–327`, `PlanCostEstimate.java:30–32` |
| Divide memory by worker capacity | Does not hold | The estimate is cluster-wide. A replicated build costs B × estimated tasks, which comes from the current node count. After scaling, a broadcast still needs B per task. `CostCalculatorWithEstimatedExchanges.java:278–296`, `TaskCountEstimator.java:40–86` |
| Discover capacity from `ClusterMemoryManager` | Partially | Worker memory snapshots exist, but **entries can be absent**. Pool capacity is not the per-node limit of the query. It is also not a reservation against competing queries. `ClusterMemoryManager.java:489–499`, `QueryContext.java:139–168` |
| Reuse the named timeout. Replace `RuntimeException` | Does not hold | The property is `query-manager.required-workers-max-wait` (session: `required_workers_max_wait_time`). The future already fails with `TrinoException(GENERIC_INSUFFICIENT_RESOURCES)`. `QueryManagerConfig.java:642–652`, `SystemSessionProperties.java:154–155`, `ClusterSizeMonitor.java:97–128` |
| Joining workers reduce an already-planned query's memory | Partially | The scheduler selects nodes when it starts, so partitionable work can benefit. But planning sets the join distributions and the exchange partition counts. **The small-cluster fast path can leave partition counts unset, so growth is not always capped at the old node count.** Caps, single-node stages and broadcast builds still prevent linear scaling. `PipelinedQueryScheduler.java:310–340`, `NodePartitioningManager.java:153–173`, `PlanOptimizers.java:886–921`, `DeterminePartitionCount.java:167–207, 346–355` |
| Fits existing resource admission | Partially | A query goes into `runningQueries` before its first resource wait. It keeps the slot until it completes. Thus, capacity waits can block queued work. `InternalResourceGroup.java:700–703, 744–759, 864, 1094` |
| "FTE cannot exceed the largest node" | Partially | FTE requires an exchange manager. **The ceiling applies to each task or partition, not to the whole distributed query.** FTE already gets memory leases asynchronously. Bin packing checks reservations and runtime usage. A worker-count gate does not replace or reserve these. `SqlQueryExecution.java:573`, `EventDrivenFaultTolerantQueryScheduler.java:1618–1628`, `BinPackingNodeAllocatorService.java:256–298, 819–874`, `ExponentialGrowthPartitionMemoryEstimator.java:164–203` |

## Minimal changes (from the review)
- Add a forward-only capacity-wait phase with explicit timers. Continue asynchronously through executor callbacks. Handle cancellation. **Finish the planning CPU accounting on the originating thread** (`QueryStateTimer.java:95–118`).
- Handle unknown or non-finite estimates explicitly.
- Model partitionable memory vs. replicated/single-task memory, skew, effective limits and concurrency.
- Report demand that the cluster cannot satisfy. Do not treat MAX_WORKERS as sufficient.
- Examine the partition and join choices again for the target capacity.
- Define admission reservations. Keep a record of running demand until the query completes.
- Integrate fault-tolerant execution, or exclude TASK retries at first.
- Test cancellation, timeouts, unknown costs, concurrent waits and scale-down.

## Keep (from the review)
- Keep the off-by-default rollout.
- Keep the external autoscaler separate from Trino.
- Use the asynchronous monitor again.
- Keep the existing `io.trino.execution:name=ClusterSizeMonitor` MBean and its `RequiredWorkers` attribute (`CoordinatorModule.java:326–328`, `ClusterSizeMonitor.java:159–165`).

## Answers from the review to the open questions of the issue
1. **Placement:** A position before dynamic-filter registration is reasonable, with one condition: registration must still occur before distribution. "Nothing depends on cluster size" is false. Planning already used the cluster size, and node selection after it also uses it.
2. **Gauges:** Keep the pending-max semantics. Add a separate gauge for retained capacity demand, possibly on the same MBean.
3. **Resource groups:** Per-group policy can come later. But v1 must handle slot occupancy and global concurrency accounting.
4. **Bypass:** Use statement or plan semantics, not `estimate == 0`. **Scans have zero modeled memory** (`CostCalculatorUsingExchanges.java:144–148`, `LocalCostEstimate.java:47–50`). DDL and USE use `DataDefinitionExecution`. SELECT, EXPLAIN, DML/CTAS and EXPLAIN ANALYZE use `SqlQueryExecution`. Plain EXPLAIN becomes a single-value query. EXPLAIN ANALYZE keeps its executable statement (`ExplainRewrite.java:104–107, 138–156`). **Even the existing bypasses wait for at least one node** (`LocalDispatchQuery.java:129–133`).
5. **Error:** This is already implemented. Keep the existing `TrinoException` and propagate the cause of the future.

---

## Comparison with my first analysis (2026-11-11)

**Agreement:** The verdicts are the same. Both reviews identify these problems:
- The state transition is not valid.
- The estimate can be NaN or unknown.
- The broadcast term increases with the worker count.
- The gauge shows zero while queries run, and it is not a sum.
- The query holds a resource-group slot.
- The wait interacts with the planning time limit.
- The property name is wrong.
- The error code already exists.

**Where the second review corrected the first analysis (I checked this by hand):**
- **Thread pool:** `start()` runs on the cached, unbounded `dispatcher-query-%s` pool (`DispatchExecutor.java:52`, through `LocalDispatchQueryFactory.java:108` → `LocalDispatchQuery.startExecution` → `QueryManager.createQuery`). It does not run on the bounded `query-execution` pool. The conclusion (the wait must be async) is still correct. But the risk is an unbounded thread count, not pool exhaustion.

**New points from the second review that the first analysis did not have:**
- `max(estimate, FLOOR)` with a NaN estimate gives NaN. Thus, the floor does not even help with unknown estimates. The code must check for NaN explicitly.
- `estimate == 0` is a bad bypass test, because the model gives scans 0 memory.
- The existing bypasses still wait for at least one node.
- Worker memory snapshots in `ClusterMemoryManager` can be missing for some nodes.
- The small-cluster fast path in `DeterminePartitionCount` can leave partition counts unset. Thus, a larger cluster can sometimes be used.
- The fault-tolerant-execution ceiling is per task or partition, not per query. This corrects the framing of the issue.
- When the wait is split asynchronously, the planning CPU accounting must finish on the originating thread (`QueryStateTimer.java:95–118`).

**Not in the second review (it did not get these questions):**
- The alternative estimator that uses statistics.
- The Tier C sizing.
- The fact that new statistics rules change plans.
