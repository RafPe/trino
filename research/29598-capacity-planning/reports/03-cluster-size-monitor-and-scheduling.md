# Code analysis: ClusterSizeMonitor, scheduling and multi-query behavior

Date: 2026-11-08. The analysis refers to trinodb/trino master `8ae1f91f853`. Paths are relative to `core/trino-main/src/main/java/io/trino/`.

## 1. How ClusterSizeMonitor works today (`execution/ClusterSizeMonitor.java`)
- `waitForMinimumWorkers` (97-129) is a synchronized method. It returns a completed future if `currentCount >= N`. If not, it adds a `MinNodesFuture` to a `PriorityQueue` that it sorts by N, smallest first (60). Then it schedules a timeout task on one single-thread "node-monitor" executor (75, 111).
- When the timeout occurs, the task fails the future with `TrinoException(GENERIC_INSUFFICIENT_RESOURCES, ...)` (114). This is a TrinoException, not a raw RuntimeException.
- When the future completes for any reason (set, timeout, cancel), a listener cancels the timer and removes the entry from the queue (123-126). Thus, the gauge decreases correctly on cancel, timeout or kill.
- Waiters do not leak. LocalDispatchQuery cancels the future when the query gets to a done state (`dispatcher/LocalDispatchQuery.java:139-143`).
- `updateAllNodes` (136-157) removes each waiter whose N the cluster now satisfies, smallest first.
- `getRequiredWorkers()` (159-166) returns the **max N across pending waiters**. It returns 0 when no query waits.
- **Only caller:** `LocalDispatchQuery.waitForMinimumWorkers` (124-145) is the only caller. It uses N=1 or the session property `required_workers_count`, when `shouldWaitForMinWorkers()` is true.
  - `shouldWaitForMinWorkers()` is false only for queries that touch nothing but internal catalogs (`SqlQueryExecution.java:752-763`).
  - The timeout is `required_workers_max_wait_time`.
  - The defaults come from `query-manager.required-workers` and `query-manager.required-workers-max-wait` (`QueryManagerConfig:628-650`, `SystemSessionProperties:746-755`).

## 2. What counts as a worker, draining and scale-down
- Only `activeNodes` count. The count excludes the coordinator, unless `node-scheduler.include-coordinator=true` (138-143). The count also excludes draining, drained, shutting-down and inactive nodes (`node/AllNodes.java:22-29`, `node/CoordinatorNodeManager.java:191-238`).
- The gauge shows queries that **wait**, not queries that run. When a wait completes, the gauge goes back to the next waiter or to 0. An autoscaler that uses only this gauge could scale down while a query runs.
- Graceful shutdown keeps a node alive until its tasks finish. Thus, the damage is limited to new stages, FTE retries, and data that moves away from the cluster. But the "scale back down" side of the signal is not correct.

## 3. Will a query actually use the new workers?
- Physical node selection occurs at `scheduler.start()`, in `DistributedStagesScheduler.create` (`execution/scheduler/PipelinedQueryScheduler.java:310, 879`). Thus, a wait before `scheduler.start()` would see the new nodes. The node map has a cache of up to 5s (`scheduler/UniformNodeSelectorFactory.java:78`).
- But the planner makes plan-shaping decisions **during planning, with the current node count**. `TaskCountEstimator` (`cost/TaskCountEstimator.java:44-87`) supplies `DeterminePartitionCount` (`sql/planner/optimizations/DeterminePartitionCount.java:167-203`) and the choice between broadcast and partitioned joins. With few workers, the planner may select broadcast joins, which copy the build side to each node. Then more nodes do not decrease the memory per node.
- Hash fragments use at most `max_hash_partition_count` nodes (default 100) or the partition count of the fragment (`PipelinedQueryScheduler.java:1191-1196`, `sql/planner/NodePartitioningManager.java:165, 210`). For FTE, the cap is `fault_tolerant_execution_max_partition_count`.
- Skewed keys get no benefit. The cluster-wide `query.max-memory` cap does not increase with more nodes.
- **Required change:** Plan the query again after the wait, or plan with `cost_estimation_worker_count` set to the target count.

## 4. Concurrency and resource groups
- Today, the wait occurs before planning. The query is in WAITING_FOR_RESOURCES, and the dispatcher dispatches it after the wait. Planning occurs later, in `SqlQueryExecution.start()` (397-446). A wait after planning is a new wait point. Because `start()` runs synchronously, the new wait must be asynchronous.
- The query already holds a resource-group running slot while it waits. `startInBackground` puts it in `runningQueries` before it calls `startWaitingForResources` (`execution/resourcegroups/InternalResourceGroup.java:744-759`). Queued queries can wait behind these waiters for up to the timeout.
- If 10 queries each need 5 workers, the gauge shows 5, not 50. Then all 10 queries run at the same time on the 5 workers, and the memory shortage occurs again.
- With mixed needs, max() can never ask for more than the need of the largest single query. "No new MBean" is technically true. But the signal needs aggregation (sum, or weighted by memory).

## 5. Fault-tolerant execution
- `BinPackingNodeAllocatorService` already queues per-task memory requests and waits for nodes (`execution/scheduler/faulttolerant/BinPackingNodeAllocatorService.java:117, 250-316`).
  - If no node matches, it fails with `NO_NODES_AVAILABLE` after `node-scheduler.allowed-no-matching-node-period` (10m default).
  - When the nodes are full, it uses a fallback after `exhausted-node-wait-period` (2m default, 886).
- FTE also tries again with a larger memory request when a task runs out of memory. It already exposes demand gauges (`PendingStandardNotEnoughResources` and related gauges, 1042-1096).
- Thus, for FTE, a pre-flight wait duplicates existing behavior. Skip it for `retry_policy=TASK`, or expose the total pending memory from the allocator.

## Recommendations
- Keep `RequiredWorkers` as it is, for compatibility.
- Add separate managed gauges:
  - Pending demand: the sum, or the count of waiters plus their memory.
  - In-flight demand: hold it from the start to the end of each admitted query. Then the autoscaler does not scale down while a query runs.
- Give the reason for a timeout in the error message.
- Make the feature opt-in through a session property. Cap N at a configured maximum cluster size, so that a wait does not become certain to fail.

## Files to change (estimated size)
- `execution/ClusterSizeMonitor.java` needs aggregated and in-flight gauges. The estimate is ~80 LOC.
- `execution/SqlQueryExecution.java` needs an asynchronous wait before `scheduler.start()`, plus a second planning or a target task count. The estimate is ~80-150 LOC.
- A new worker-count estimator that uses the memory estimate needs ~150 LOC.
- `SystemSessionProperties.java` and `QueryManagerConfig.java` need an enable flag and a cap. The estimate is ~60 LOC.
- `cost/TaskCountEstimator.java` must use the target count. The estimate is ~20 LOC.
- The tests (TestClusterSizeMonitor and a query-runner test) need ~250 LOC.
- The docs (properties and JMX) need ~40 lines.
