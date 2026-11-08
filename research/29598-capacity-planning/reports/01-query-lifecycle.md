# Agent report: query lifecycle and hook point

Opus research agent, 2026-11-08. Paths are relative to `core/trino-main/src/main/java/io/trino/`.

## 1. Dispatch flow, threads, and how the existing wait works
- `InternalResourceGroup.startInBackground` (`execution/resourcegroups/InternalResourceGroup.java:743-760`) adds the query to `runningQueries` first, then calls `executor.execute(query::startWaitingForResources)`. So a query already holds a running slot while it is in WAITING_FOR_RESOURCES today.
- `LocalDispatchQuery.startWaitingForResources/waitForMinimumWorkers` (`dispatcher/LocalDispatchQuery.java:117-142`) is the existing required-workers wait. It does not block a thread. It chains on `queryExecutionFuture` (the `SqlQueryExecution` constructor, which runs analysis at `SqlQueryExecution.java:221`) and on `ClusterSizeMonitor.waitForMinimumWorkers`, which returns a ListenableFuture.
  - That future is cancelled when the query reaches a terminal state, and it times out with `GENERIC_INSUFFICIENT_RESOURCES` (`execution/ClusterSizeMonitor.java:97-131`).
  - It is skipped for DDL and for queries that only touch internal catalogs (`SqlQueryExecution.java:747-762`).
- On success, `startExecution` runs on `queryExecutor`, moves the query to DISPATCHING and calls `QueryManager.createQuery` (`execution/QueryManager.java:316-335`). That calls `queryExecution.start()` synchronously.
- So `start()` runs on a `query-execution-%s` thread. That pool defaults to 1000 threads with a bounded queue of 1000 (`server/CoordinatorModule.java:472-483`, `QueryManagerConfig.java:94`). Planning is synchronous on that thread (`SqlQueryExecution.java:397-450`).
- Blocking there is technically possible: cancel moves the query to FAILED, and the PLANNING listener interrupts the thread (`:406-416`). But every waiting query would hold a pool thread for minutes, so the wait must be async.

## 2. State machine
- Order: QUEUED, WAITING_FOR_RESOURCES, DISPATCHING, PLANNING, STARTING, RUNNING, FINISHING, FINISHED, FAILED (`execution/QueryState.java`).
- Every transition is guarded by an ordinal check (`QueryStateMachine.java:1227-1254`). Going from PLANNING back to WAITING_FOR_RESOURCES is therefore impossible: `transitionToWaitingForResources()` returns false.
- `QueryStateTimer` records each phase once with `compareAndSet(null, ...)` (`execution/QueryStateTimer.java:72-118`). `queuedTime` and `resourceWaitingTime` are already frozen at that point, so a second wait would not be counted there anyway.
- Other code depends on the ordering:
  - `LocalDispatchQuery.java:107` (any state ≥ PLANNING means submitted)
  - `QueryStateMachine.java:1375` (final info)
  - `server/protocol/Query.java:466`
  - `DispatchManager.java:364-380` (WAITING_FOR_RESOURCES metrics)
  - the web UI (`QueryList.tsx`)
- A new enum value would shift ordinals and change client-visible state strings.
- **Recommendation:** stay in PLANNING and add a non-state indicator, e.g. a `capacityWaitTime` / "blocked on capacity" field in QueryStats plus a span event or warning.

## 3. What the wait would hold or count against
- **Time limits:** `getExecutionStartTime()` is the moment planning began (`QueryStateTimer.java:198-201`), and planning time keeps running until STARTING. The wait therefore counts against `query.max-planning-time` (default 10m, `QueryTracker.java:189-199`) and `query.max-execution-time`. A 5m wait plus slow planning could fail with EXCEEDED_TIME_LIMIT. Either subtract the wait from planning time or document it.
- **Resource group:** the query keeps its running slot, as it already does during the existing wait. Its group usage stays at zero, so memory-based group limits will not throttle other queries.
- **Transaction and metadata:** the autocommit transaction (`QueryStateMachine.java:331`) and the connector handles from analysis/planning stay open. Snapshot-pinned table handles (e.g. Iceberg) go stale.
- **Locks:** none. `registerDynamicFilteringQuery` is synchronized but short.
- **Coordinator memory:** the plan is retained in memory while the query waits.
- **Double wait:** reusing the existing max-wait setting means a query can wait up to twice that value (once at dispatch, once after planning).

## 4. Statement types
- `SqlQueryExecution` handles everything from `getNonDataDefinitionStatements` (`server/QueryExecutionFactoryModule.java:145-148`): Query, INSERT, CTAS, DELETE/UPDATE/MERGE, ANALYZE, EXPLAIN, EXPLAIN ANALYZE, and others.
- DDL goes through `DataDefinitionExecution`, which never waits (`shouldWaitForMinWorkers()` returns false, `DataDefinitionExecution.java:248`).
- EXPLAIN (not ANALYZE) is rewritten by `sql/rewrite/ExplainRewrite.java:111` into a query with no tables. The existing bypass skips it, but a non-zero `floor` would make it wait, so the new hook must reuse `shouldWaitForMinWorkers()`.
- EXPLAIN ANALYZE plans and runs the real query (`SqlQueryExecution.java:519-520`), so it should go through the hook.

## 5. Pipelined vs fault-tolerant
- The scheduler is chosen inside `planDistribution` (`SqlQueryExecution.java:535-586`), so a hook before it covers NONE, QUERY and TASK.
- TASK retries already use memory-aware node allocation and adaptive re-planning, so the feature adds little there. Consider defaulting it off for TASK.

## Errors in the proposal
1. "Transitions the query to the existing WAITING_FOR_RESOURCES state": impossible from PLANNING, and the timers would not record it.
2. "On timeout throw TrinoException(GENERIC_INSUFFICIENT_RESOURCES)": `ClusterSizeMonitor` already fails the future with that error. Only propagation is needed.
3. "Waits via `waitForMinimumWorkers`" implies a blocking wait, but the method returns a future. It also counts active nodes only (optionally including the coordinator), not memory per node.
4. A root `getMaxMemory()` can be NaN or unknown when stats are missing.

## Recommended design
Split `start()` into two phases:
1. On the current thread: `planQuery` (still under interrupt protection), then compute N.
2. If N ≤ the current worker count, continue inline. Otherwise register `waitForMinimumWorkers(N, timeout)`, with success and failure callbacks on `queryExecutor` that call `fail()` on error. Cancel the future when the query reaches a terminal state, mirroring `LocalDispatchQuery.java:136-140`.
3. Phase 2 runs `registerDynamicFilteringQuery`, `planDistribution`, `transitionToStarting` and `scheduler.start()`. It needs its own interrupt-on-fail handling and an `isDone()` re-check.

The query stays in PLANNING, and capacity wait time is recorded separately.

## Files to change (estimated size)
- `execution/SqlQueryExecution.java`: async two-phase `start()`, ~80-120 lines.
- `SqlQueryExecutionFactory`: inject `ClusterSizeMonitor` and the planner, ~15.
- New planner service and config, plus binding in `server/CoordinatorModule.java`: ~150-200.
- `SystemSessionProperties.java`: enable and override properties, ~30.
- Optional wait-time stat in `QueryStateTimer`, `QueryStateMachine`, `QueryStats`/`BasicQueryStats` and SPI `QueryStatistics`: ~100-200; the SPI change needs reviewer buy-in.
- Planning-time accounting fix in `QueryTracker` / `QueryStateTimer`: ~20.
- Docs (`docs/.../properties-query-management.md`): ~40.
- Tests: config, unit and DistributedQueryRunner, ~300.
