# Codex structural review of #29598 (independent second opinion)

- **Reviewer:** Codex v0.167.0 (GPT-6-Astra, xhigh), run on 2026-11-11 through the Orca MCP in terminal `remore-review` on the Mac.
- **Inputs:** the issue text and a shallow checkout of trinodb/trino at `8ae1f91f85313e50fd72286810c52f4b058b1292`. It was **not** shown our research, so the verdict is unanchored.
- **Original:** `~/orca/workspaces/misc/remore-review/codex-review-29598.md` on the Mac (891 words, 51 citations pinned to that commit).
- **This copy:** transcribed from the terminal. Paths are relative to `core/trino-main/src/main/java/io/trino/`.

## Verdict: structurally flawed as proposed

> Predictive admission is useful, but this requires lifecycle and capacity-model changes, not merely wiring. The proposed state transition fails; ordinary plans can have unknown memory cost; broadcast memory does not shrink with more workers; and the gauge represents pending thresholds, not reserved capacity. This is a source review, not validation of the Minikube results.

## Claims

| Claim | Assessment | Evidence (Codex) |
|---|---|---|
| Insert a wait after `planQuery` | Partially | The order is planning → dynamic-filter registration → distribution → starting/scheduler, and dispatch has already happened. `start()` runs synchronously through `QueryManager.createQuery` on the cached `dispatcher-query-%s` executor, with the thread renamed `Query-…`. Calling the future API alone doesn't wait, and blocking on it holds one thread per query. The existing wait uses callbacks and cancellation. `SqlQueryExecution.java:397–446`, `QueryManager.java:316–334`, `LocalDispatchQueryFactory.java:184–190`, `DispatchExecutor.java:52–54`, `LocalDispatchQuery.java:117–151` |
| Reuse `WAITING_FOR_RESOURCES` after planning | Does not hold | It comes before PLANNING, and the ordinal guard rejects moving backward. Resource-wait timing is already finalized. Staying in PLANNING charges the wait to planning time, so `query.max-planning-time` can fail the query; execution and run deadlines also keep running. `QueryState.java:26–42`, `QueryStateMachine.java:1227–1248`, `QueryStateTimer.java:77–118`, `QueryTracker.java:181–202` |
| `RequiredWorkers` is a sufficient autoscaler target | Partially | It is the maximum pending threshold, not a sum or a reservation. Two queries needing N both pass at N. It reads zero while queries run, and requests that are already satisfied never enter it. Useful as a scale-up signal; not enough on its own for capacity or scale-down. `ClusterSizeMonitor.java:97–108, 136–165` |
| `getMaxMemory()` exists after planning | Holds | `LogicalPlanner` builds `StatsAndCosts` for every node. Read the root cost of the saved `Plan`, not of the fragmented plan. `LogicalPlanner.java:304–310`, `StatsAndCosts.java:80–90`, `SqlQueryExecution.java:503–509`, `PlanCostEstimate.java:95–103` |
| Ordinary plans supply a usable memory number | Does not hold | Only about 14 visitor types exist. Sort, TopN, Window, TableWriter and others default to unknown, and PARTIAL/INTERMEDIATE aggregation is explicitly unknown. NaN propagates, **including through `max(estimate, FLOOR)`**. `CostCalculatorUsingExchanges.java:99–294, 303–327`, `PlanCostEstimate.java:30–32` |
| Divide memory by worker capacity | Does not hold | The estimate is cluster-wide. A replicated build costs B × estimated tasks, derived from the current node count, and a broadcast still needs B per task after scaling. `CostCalculatorWithEstimatedExchanges.java:278–296`, `TaskCountEstimator.java:40–86` |
| Discover capacity from `ClusterMemoryManager` | Partially | Worker memory snapshots exist but **entries can be absent**. Pool capacity is neither the query's per-node limit nor a reservation against competing queries. `ClusterMemoryManager.java:489–499`, `QueryContext.java:139–168` |
| Reuse the named timeout; replace `RuntimeException` | Does not hold | The property is `query-manager.required-workers-max-wait` (session: `required_workers_max_wait_time`). The future already fails with `TrinoException(GENERIC_INSUFFICIENT_RESOURCES)`. `QueryManagerConfig.java:642–652`, `SystemSessionProperties.java:154–155`, `ClusterSizeMonitor.java:97–128` |
| Joining workers reduce an already-planned query's memory | Partially | Nodes are chosen at scheduler start, so partitionable work can benefit. But join distributions and exchange partition counts are fixed during planning. **The small-cluster fast path can leave partition counts unset, so growth isn't always capped at the old node count.** Caps, single-node stages and broadcast builds still prevent linear scaling. `PipelinedQueryScheduler.java:310–340`, `NodePartitioningManager.java:153–173`, `PlanOptimizers.java:886–921`, `DeterminePartitionCount.java:167–207, 346–355` |
| Fits existing resource admission | Partially | A query enters `runningQueries` before its first resource wait and keeps the slot until it completes, so capacity waits can block queued work. `InternalResourceGroup.java:700–703, 744–759, 864, 1094` |
| "FTE cannot exceed the largest node" | Partially | An exchange manager is required. **The ceiling applies to each task or partition, not to the whole distributed query.** FTE already acquires memory leases asynchronously, and bin packing checks reservations and runtime usage. A worker-count gate neither replaces nor reserves those. `SqlQueryExecution.java:573`, `EventDrivenFaultTolerantQueryScheduler.java:1618–1628`, `BinPackingNodeAllocatorService.java:256–298, 819–874`, `ExponentialGrowthPartitionMemoryEstimator.java:164–203` |

## Minimal changes (Codex)
- A forward-only capacity-wait phase with explicit timers. Resume asynchronously through executor callbacks, handle cancellation, and **finish planning CPU accounting on the originating thread** (`QueryStateTimer.java:95–118`).
- Handle unknown or non-finite estimates explicitly.
- Model partitionable vs. replicated/single-task memory, skew, effective limits and concurrency.
- Report unsatisfiable demand instead of treating MAX_WORKERS as sufficient.
- Revisit partition and join choices for the target capacity.
- Define admission reservations and keep running demand recorded until completion.
- Integrate fault-tolerant execution, or exclude TASK retries at first.
- Test cancellation, timeouts, unknown costs, concurrent waits and scale-down.

## Keep (Codex)
- Off-by-default rollout.
- Keeping the external autoscaler separate from Trino.
- Reusing the asynchronous monitor.
- The existing `io.trino.execution:name=ClusterSizeMonitor` MBean and its `RequiredWorkers` attribute (`CoordinatorModule.java:326–328`, `ClusterSizeMonitor.java:159–165`).

## Codex's answers to the issue's open questions
1. **Placement:** conditionally reasonable before dynamic-filter registration, as long as registration still happens before distribution. "Nothing depends on cluster size" is false: planning already used it, and node selection afterwards does too.
2. **Gauges:** keep the pending-max semantics and add a separate gauge for retained capacity demand, possibly on the same MBean.
3. **Resource groups:** per-group policy can come later, but slot occupancy and global concurrency accounting must be handled in v1.
4. **Bypass:** use statement or plan semantics, not `estimate == 0`. **Scans have zero modeled memory** (`CostCalculatorUsingExchanges.java:144–148`, `LocalCostEstimate.java:47–50`). DDL and USE use `DataDefinitionExecution`. SELECT, EXPLAIN, DML/CTAS and EXPLAIN ANALYZE use `SqlQueryExecution`. Plain EXPLAIN becomes a single-value query, while EXPLAIN ANALYZE keeps its executable statement (`ExplainRewrite.java:104–107, 138–156`). **Even the existing bypasses wait for at least one node** (`LocalDispatchQuery.java:129–133`).
5. **Error:** already implemented. Keep the existing `TrinoException` and propagate the future's cause.

---

## Comparison with our research (added by Claude, 2026-11-11)

**Agreement:** the verdicts match. Both reviews flag the invalid state transition, the NaN or unknown estimate, the broadcast term growing with the worker count, a gauge that reads zero while queries run and isn't summed, the resource-group slot, the planning-time-limit interaction, the wrong property name, and the error code that already exists.

**Where Codex corrects us (verified by hand):**
- **Thread pool:** `start()` runs on the cached, unbounded `dispatcher-query-%s` pool (`DispatchExecutor.java:52`, via `LocalDispatchQueryFactory.java:108` → `LocalDispatchQuery.startExecution` → `QueryManager.createQuery`). It does not run on the bounded `query-execution` pool. The conclusion (the wait must be async) stands, but the risk is an unbounded thread count, not pool exhaustion.

**New points from Codex that we didn't have:**
- `max(estimate, FLOOR)` with a NaN estimate yields NaN, so the floor doesn't even rescue unknown estimates. It has to be checked explicitly.
- `estimate == 0` is a bad bypass test because scans are modeled at 0 memory.
- The existing bypasses still wait for at least one node.
- Worker memory snapshots in `ClusterMemoryManager` can be missing for some nodes.
- The small-cluster fast path in `DeterminePartitionCount` can leave partition counts unset, so a larger cluster can sometimes be used.
- The fault-tolerant-execution ceiling is per task or partition, not per query. This corrects the issue's framing.
- Planning CPU accounting has to be finished on the originating thread when the wait is split asynchronously (`QueryStateTimer.java:95–118`).

**Not covered by Codex (it wasn't asked):** the statistics-based estimator alternative, the Tier C sizing, and the fact that new statistics rules change plans.
