# Code analysis: query lifecycle and hook point

Date: 2026-11-08. The analysis refers to trinodb/trino master `8ae1f91f853`. Paths are relative to `core/trino-main/src/main/java/io/trino/`.

> **Correction (2026-11-11):** I made an error in §1. `start()` runs on the cached `dispatcher-query-%s` pool (`DispatchExecutor.java:52`, `LocalDispatchQueryFactory.java:108`), not on the `query-execution` pool that §1 describes. See `05-second-review.md`.

## 1. Dispatch flow, threads, and how the existing wait works
- `InternalResourceGroup.startInBackground` (`execution/resourcegroups/InternalResourceGroup.java:743-760`) first adds the query to `runningQueries`. Then it calls `executor.execute(query::startWaitingForResources)`. Thus, today a query already holds a running slot while it is in WAITING_FOR_RESOURCES.
- `LocalDispatchQuery.startWaitingForResources/waitForMinimumWorkers` (`dispatcher/LocalDispatchQuery.java:117-142`) is the existing required-workers wait. It does not block a thread. It chains on `queryExecutionFuture` and on `ClusterSizeMonitor.waitForMinimumWorkers`. `queryExecutionFuture` is the `SqlQueryExecution` constructor, which runs analysis at `SqlQueryExecution.java:221`. `ClusterSizeMonitor.waitForMinimumWorkers` returns a ListenableFuture.
  - The ListenableFuture is cancelled when the query gets to a terminal state. It times out with `GENERIC_INSUFFICIENT_RESOURCES` (`execution/ClusterSizeMonitor.java:97-131`).
  - The wait does not occur for DDL or for queries that touch only internal catalogs (`SqlQueryExecution.java:747-762`).
- If the wait succeeds, `startExecution` runs on `queryExecutor`. It moves the query to DISPATCHING and calls `QueryManager.createQuery` (`execution/QueryManager.java:316-335`). That method calls `queryExecution.start()` synchronously.
- Thus, `start()` runs on a `query-execution-%s` thread. By default, that pool has 1000 threads and a bounded queue of 1000 (`server/CoordinatorModule.java:472-483`, `QueryManagerConfig.java:94`). Planning runs synchronously on that thread (`SqlQueryExecution.java:397-450`).
- It is technically possible to block there. A cancel moves the query to FAILED, and the PLANNING listener interrupts the thread (`:406-416`). But each waiting query would hold a pool thread for minutes. Thus, the wait must be async.

## 2. State machine
- The order is QUEUED, WAITING_FOR_RESOURCES, DISPATCHING, PLANNING, STARTING, RUNNING, FINISHING, FINISHED, FAILED (`execution/QueryState.java`).
- An ordinal check guards each transition (`QueryStateMachine.java:1227-1254`). Thus, the query cannot go from PLANNING back to WAITING_FOR_RESOURCES. `transitionToWaitingForResources()` returns false.
- `QueryStateTimer` records each phase one time with `compareAndSet(null, ...)` (`execution/QueryStateTimer.java:72-118`). At that point, `queuedTime` and `resourceWaitingTime` are already frozen. Thus, the timer would not count a second wait there in any case.
- Other code depends on the order of the states:
  - `LocalDispatchQuery.java:107` treats any state ≥ PLANNING as submitted.
  - `QueryStateMachine.java:1375` uses it for the final info.
  - `server/protocol/Query.java:466` uses it.
  - `DispatchManager.java:364-380` uses it for the WAITING_FOR_RESOURCES metrics.
  - The web UI (`QueryList.tsx`) uses it.
- A new enum value would move the ordinals and change the state strings that clients see.
- **Recommendation:** Keep the query in PLANNING. Add an indicator that is not a state. Examples are a `capacityWaitTime` / "blocked on capacity" field in QueryStats, plus a span event or a warning.

## 3. What the wait would hold or count against
- **Time limits:** `getExecutionStartTime()` is the moment when planning started (`QueryStateTimer.java:198-201`). Planning time continues until STARTING. Thus, the wait counts against `query.max-planning-time` (default 10m, `QueryTracker.java:189-199`) and against `query.max-execution-time`. A 5m wait plus slow planning could fail with EXCEEDED_TIME_LIMIT. Either subtract the wait from the planning time, or document the behavior.
- **Resource group:** The query keeps its running slot, as it does during the existing wait. Its group usage stays at zero. Thus, memory-based group limits will not slow other queries.
- **Transaction and metadata:** The autocommit transaction (`QueryStateMachine.java:331`) stays open. The connector handles from analysis and planning also stay open. Table handles that pin a snapshot (for example, Iceberg) become stale.
- **Locks:** The wait holds no locks. `registerDynamicFilteringQuery` is synchronized, but it is short.
- **Coordinator memory:** The coordinator keeps the plan in memory while the query waits.
- **Double wait:** If the feature uses the existing max-wait setting again, a query can wait up to two times that value. It waits one time at dispatch and one time after planning.

## 4. Statement types
- `SqlQueryExecution` handles all statements from `getNonDataDefinitionStatements` (`server/QueryExecutionFactoryModule.java:145-148`). These include Query, INSERT, CTAS, DELETE/UPDATE/MERGE, ANALYZE, EXPLAIN, EXPLAIN ANALYZE, and others.
- DDL goes through `DataDefinitionExecution`, which never waits. Its `shouldWaitForMinWorkers()` returns false (`DataDefinitionExecution.java:248`).
- `sql/rewrite/ExplainRewrite.java:111` rewrites EXPLAIN (not ANALYZE) into a query with no tables. The existing bypass skips it. But a `floor` that is not zero would make it wait. Thus, the new hook must use `shouldWaitForMinWorkers()` again.
- EXPLAIN ANALYZE plans and runs the real query (`SqlQueryExecution.java:519-520`). Thus, it should go through the hook.

## 5. Pipelined vs fault-tolerant
- `planDistribution` selects the scheduler (`SqlQueryExecution.java:535-586`). Thus, a hook before it covers NONE, QUERY and TASK.
- TASK retries already use node allocation that knows about memory, and adaptive re-planning. Thus, the feature adds little value there. Consider to make it off by default for TASK.

## Errors in the proposal
1. "Transitions the query to the existing WAITING_FOR_RESOURCES state": This is impossible from PLANNING, and the timers would not record it.
2. "On timeout throw TrinoException(GENERIC_INSUFFICIENT_RESOURCES)": `ClusterSizeMonitor` already fails the future with that error. The feature only has to propagate it.
3. "Waits via `waitForMinimumWorkers`": This implies a blocking wait, but the method returns a future. Also, it counts only active nodes (with the coordinator as an option). It does not count memory per node.
4. A root `getMaxMemory()` can be NaN or unknown when stats are missing.

## Recommended design
Split `start()` into two phases:
1. On the current thread, run `planQuery` (still with interrupt protection). Then calculate N.
2. If N ≤ the current worker count, continue inline. If not, register `waitForMinimumWorkers(N, timeout)` with success and failure callbacks on `queryExecutor`. The failure callback calls `fail()`. Cancel the future when the query gets to a terminal state, as `LocalDispatchQuery.java:136-140` does.
3. Phase 2 runs `registerDynamicFilteringQuery`, `planDistribution`, `transitionToStarting` and `scheduler.start()`. It needs its own interrupt-on-fail handling and a second `isDone()` check.

The query stays in PLANNING. The code records the capacity wait time separately.

## Files to change (estimated size)
- `execution/SqlQueryExecution.java` needs an async two-phase `start()`. The estimate is ~80-120 lines.
- `SqlQueryExecutionFactory` must inject `ClusterSizeMonitor` and the planner. The estimate is ~15 lines.
- A new planner service and config, plus a binding in `server/CoordinatorModule.java`, need ~150-200 lines.
- `SystemSessionProperties.java` needs the enable and override properties. The estimate is ~30 lines.
- An optional wait-time stat touches `QueryStateTimer`, `QueryStateMachine`, `QueryStats`/`BasicQueryStats` and the SPI `QueryStatistics`. The estimate is ~100-200 lines. The SPI change needs agreement from the reviewers.
- A fix to the planning-time accounting in `QueryTracker` / `QueryStateTimer` needs ~20 lines.
- The docs (`docs/.../properties-query-management.md`) need ~40 lines.
- The tests (config, unit and DistributedQueryRunner) need ~300 lines.
