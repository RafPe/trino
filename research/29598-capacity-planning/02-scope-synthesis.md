# Scope synthesis

I analysed the code in four areas (the raw reports are in [reports/](reports/)). I checked the two
findings that block the design again by hand: the NaN estimate and the state order.

**Bottom line:** the goal makes sense, but the design in the issue won't work as written. A
version that actually prevents OOMs is roughly 1,500–2,000 lines over 2–3 PRs, not "a wiring
change plus one new service".

## Where the proposal conflicts with the code

| Claim in the issue | What the code does |
|---|---|
| Move the query back to `WAITING_FOR_RESOURCES` after planning | Not possible. `WAITING_FOR_RESOURCES` comes before `PLANNING` in `QueryState`, and `transitionToWaitingForResources()` only fires from an earlier state (`QueryStateMachine.java:1227-1231`). The call returns false and does nothing. |
| The wait reuses `query.required-workers-max-wait-time` | That property doesn't exist. The real one is `query-manager.required-workers-max-wait` (`QueryManagerConfig.java:647`), with session override `required_workers_max_wait_time`. |
| On timeout it throws `RuntimeException`, and the PR will change it to `GENERIC_INSUFFICIENT_RESOURCES` | `ClusterSizeMonitor` already fails the future with `TrinoException(GENERIC_INSUFFICIENT_RESOURCES)`. |
| `PlanCostEstimate.getMaxMemory()` gives a usable estimate | It is NaN for most real plans. `CostCalculatorUsingExchanges.visitPlan` returns `unknown()` ("TODO implement cost estimates for all plan nodes"). The calculator handles only about 17 node types, and PARTIAL aggregations also return unknown. NaN carries up to the root. |
| `estimate / perWorkerCapacity` gives the worker count | `maxMemory` is a cluster-wide total that already depends on the current node count: the broadcast-join term is build size × the count from `TaskCountEstimator`. Adding workers *raises* it. A broadcast build bigger than one node's memory can't be fixed by adding workers. |

**Implication for the proof of concept:** most TPC-DS queries would get NaN. Thus the "3 of 4
rescued" result may come from the 200MB floor and the 2.0 multiplier, not from real
estimates. Ask the author for the per-query plan-cost accuracy data that they offered.

## Design problems

1. **The wait has to be asynchronous.**
   - `start()` runs on a thread from the cached, unbounded `dispatcher-query-%s` pool (`DispatchExecutor.java:52`, via `LocalDispatchQuery.startExecution` → `QueryManager.createQuery`), renamed `Query-<id>`. Blocking it would create one thread per waiting query. *(Corrected 2026-11-11, after the second, independent review. This item originally said the `query-execution` pool.)*
   - `start()` needs to split into "plan, then compute N" and a future-chained "distribute, then schedule", following the pattern in `LocalDispatchQuery`.
2. **Time limits.**
   - A wait inside `PLANNING` counts against `query.max-planning-time` (`QueryTracker.java:189-199`) and `query.max-execution-time`.
   - It also doesn't appear in `resourceWaitingTime`, because `QueryStateTimer` records each phase only once.
   - A query can wait twice: once at dispatch and again after planning.
3. **The planner shapes the plan for the current cluster size.**
   - `TaskCountEstimator` feeds `DeterminePartitionCount` and the choice between broadcast and partitioned joins.
   - On a small cluster the planner picks broadcast, and new workers then don't reduce memory per node.
   - Fix: plan with the target worker count (`cost_estimation_worker_count`) or re-plan after the wait.
4. **The gauge is a poor autoscaling signal.**
   - `RequiredWorkers` is the *largest* count requested by any waiting query. Ten queries needing 5 workers each show as 5, not 50.
   - It drops to 0 when queries start running, so an autoscaler could scale down mid-query.
   - Needed: a summed-demand gauge and a gauge for queries already running. Keep `RequiredWorkers` for compatibility.
5. **A waiting query holds a resource-group running slot.** `InternalResourceGroup.startInBackground` adds it to `runningQueries` before waiting, so queued queries are stuck behind it until it times out.
6. **Fault-tolerant execution (`retry_policy=TASK`):** `BinPackingNodeAllocatorService` already queues work by memory per node and retries out-of-memory tasks with more memory. Skip the feature in that mode.
7. **Statement types:**
   - EXPLAIN (rewritten into a query with no tables) would wait because of the floor. Reuse `shouldWaitForMinWorkers()`, and treat NaN as "skip", not "use the floor".
   - EXPLAIN ANALYZE runs the real query, so it should go through the check.
   - DDL uses `DataDefinitionExecution` and never waits.
8. **Project conventions:**
   - Use `query-manager.*` properties in `QueryManagerConfig` rather than a new `capacity-planner.*` prefix, plus session overrides for the enable flag and the safety multiplier.
   - Keep the SPI (`QueryStatistics`) unchanged in v1 and expose the new numbers through JMX.
   - Document `query-manager.required-workers*`, which has no docs today.

## Scope tiers

| Tier | Contents | Size |
|---|---|---|
| **A – fixed-up version of the issue** | Asynchronous two-phase `start()`. An estimator (NaN means skip). Config and session properties. Summed-demand and running-query gauges. A planning-time adjustment. Skipping fault-tolerant execution. Tests (based on the `TestMinWorkerRequirement` + `addServers` pattern). Docs. | ~800–1,000 LOC, 1 PR |
| **B – actually useful** | A + a per-stage estimator (broadcast vs partitioned, reports "infeasible" when no worker count helps), planning with the target worker count, a capacity-wait statistic | +600–900 LOC |
| **C – cost-based optimizer work** | Cost visitors and statistics rules for Sort, TopN, Window, partial aggregation, MarkDistinct, GroupId, Unnest and TableWriter. See [05-tier-c-assessment.md](05-tier-c-assessment.md). | ~500 LOC production code + 600–800 LOC tests, 3–4 PRs |

Recommended per-stage memory model:

- Per-node need ≈ Σ(broadcast builds) + Σ(partitioned state) / min(N, hash partitions). Solve for N.
- If the broadcast part alone exceeds per-node capacity, report "infeasible", not "more workers".

Per-node capacity comes from `ClusterMemoryManager.getWorkersMemoryInfo()`, using
`min(pool maxBytes, query.max-memory-per-node)`.
