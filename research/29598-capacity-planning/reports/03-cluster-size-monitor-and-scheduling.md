# Agent report: ClusterSizeMonitor, scheduling and multi-query behavior

Opus research agent, 2026-11-08. Paths are relative to `core/trino-main/src/main/java/io/trino/`.

## 1. How ClusterSizeMonitor works today (`execution/ClusterSizeMonitor.java`)
- `waitForMinimumWorkers` (97-129) is synchronized. It returns an already-completed future if `currentCount >= N`. Otherwise it adds a `MinNodesFuture` to a `PriorityQueue` ordered by N, smallest first (60). It schedules a timeout task on one single-thread "node-monitor" executor (75, 111).
- When the timeout fires, it fails the future with `TrinoException(GENERIC_INSUFFICIENT_RESOURCES, ...)` (114). This is a TrinoException, not a raw RuntimeException.
- When the future finishes for any reason (set, timeout, cancel), a listener cancels the timer and removes the entry from the queue (123-126). So the gauge drops correctly on cancel, timeout or kill.
- No waiter leak: LocalDispatchQuery cancels the future when the query reaches a done state (`dispatcher/LocalDispatchQuery.java:139-143`).
- `updateAllNodes` (136-157) pops every waiter whose N is now met, smallest first.
- `getRequiredWorkers()` (159-166) returns the **max N across pending waiters**, or 0 when nothing waits.
- **Only caller:** `LocalDispatchQuery.waitForMinimumWorkers` (124-145). It uses N=1, or the session property `required_workers_count` when `shouldWaitForMinWorkers()` is true; that is false only for queries that touch nothing but internal catalogs (`SqlQueryExecution.java:752-763`). Timeout is `required_workers_max_wait_time`. Defaults come from `query-manager.required-workers` and `query-manager.required-workers-max-wait` (`QueryManagerConfig:628-650`; `SystemSessionProperties:746-755`).

## 2. What counts as a worker; draining and scale-down
- Only `activeNodes` count. The coordinator is excluded unless `node-scheduler.include-coordinator=true` (138-143). Draining, drained, shutting-down and inactive nodes are excluded (`node/AllNodes.java:22-29`; `node/CoordinatorNodeManager.java:191-238`).
- The gauge reflects queries that are **waiting**, not queries that are running. Once a wait finishes it drops back to the next waiter or 0. An autoscaler driven by it alone could scale down mid-query.
- Graceful shutdown keeps a node alive until its tasks finish, so the damage is limited to new stages, FTE retries, and data moving away from the cluster. But the "scale back down" side of the signal is unsound.

## 3. Will a query actually use the new workers?
- Physical node selection happens at `scheduler.start()`, inside `DistributedStagesScheduler.create` (`execution/scheduler/PipelinedQueryScheduler.java:310, 879`). Waiting before `scheduler.start()` would therefore see the new nodes, after up to 5s of node-map caching (`scheduler/UniformNodeSelectorFactory.java:78`).
- However, plan-shaping decisions are made **during planning using the current node count**: `TaskCountEstimator` (`cost/TaskCountEstimator.java:44-87`) feeds `DeterminePartitionCount` (`sql/planner/optimizations/DeterminePartitionCount.java:167-203`) and the choice between broadcast and partitioned joins. With few workers the planner may pick broadcast joins, which copy the build side to every node, so adding nodes does not lower per-node memory.
- Hash fragments use at most `max_hash_partition_count` nodes (default 100) or the fragment's partition count (`PipelinedQueryScheduler.java:1191-1196`; `sql/planner/NodePartitioningManager.java:165, 210`). For FTE the cap is `fault_tolerant_execution_max_partition_count`.
- Skewed keys get no benefit, and the cluster-wide `query.max-memory` cap does not grow with nodes.
- **Required change:** re-plan after the wait, or plan with `cost_estimation_worker_count` set to the target count.

## 4. Concurrency and resource groups
- Today the wait happens before planning: the query is in WAITING_FOR_RESOURCES and is dispatched after the wait; planning happens later in `SqlQueryExecution.start()` (397-446). A wait after planning is a new wait point, and since `start()` runs synchronously, it must be asynchronous.
- The query already holds a resource-group running slot while waiting. `startInBackground` puts it in `runningQueries` before calling `startWaitingForResources` (`execution/resourcegroups/InternalResourceGroup.java:744-759`). Queued queries can stall behind waiters for up to the timeout.
- If 10 queries each need 5 workers, the gauge reads 5, not 50. All 10 then run at once on the 5 workers, and the memory shortage comes back.
- With mixed needs, max() can never ask for more than the largest single query needs. "No new MBean" is technically true but the signal needs aggregation (sum, or memory-weighted).

## 5. Fault-tolerant execution
- `BinPackingNodeAllocatorService` already queues per-task memory requests and waits for nodes (`execution/scheduler/faulttolerant/BinPackingNodeAllocatorService.java:117, 250-316`).
  - With no matching node it fails with `NO_NODES_AVAILABLE` after `node-scheduler.allowed-no-matching-node-period` (10m default).
  - When nodes are full it falls back after `exhausted-node-wait-period` (2m default; 886).
- FTE also retries tasks that run out of memory with a larger memory request, and already exposes demand gauges (`PendingStandardNotEnoughResources` and related, 1042-1096).
- So for FTE a pre-flight wait duplicates existing behavior. Skip it for `retry_policy=TASK`, or expose aggregate pending memory from the allocator.

## Recommendations
- Keep `RequiredWorkers` as it is for compatibility.
- Add separate managed gauges: pending demand (sum, or count of waiters plus their memory) and in-flight demand, held from start to finish of each admitted query, so the autoscaler does not scale down mid-query.
- Report the reason for a timeout in the error message.
- Make the feature opt-in through a session property, and cap N at a configured maximum cluster size so waits are not doomed.

## Files to change (estimated size)
- `execution/ClusterSizeMonitor.java`: aggregated and in-flight gauges, ~80 LOC
- `execution/SqlQueryExecution.java`: asynchronous wait before `scheduler.start()`, plus re-planning or a target task count, ~80-150 LOC
- New worker-count estimator from the memory estimate, ~150 LOC
- `SystemSessionProperties.java` and `QueryManagerConfig.java`: enable flag and cap, ~60 LOC
- `cost/TaskCountEstimator.java`: honor the target count, ~20 LOC
- Tests: TestClusterSizeMonitor, a query-runner test, ~250 LOC
- Docs: properties and JMX, ~40 lines
