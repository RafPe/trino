# Agent report: config, wiring, tests, docs and conventions

Opus research agent, 2026-11-08. The state-order and property-name findings were re-checked by hand.

## Factual errors in the proposal
1. **Wrong property name.** `query.required-workers-max-wait-time` does not exist. The real config is `query-manager.required-workers-max-wait` (`QueryManagerConfig.java:647`). Its session override is `required_workers_max_wait_time` (`SystemSessionProperties.java:155, :751`). Reuse `getRequiredWorkersMaxWait(session)` (:1733).
2. **The state machine cannot go back.** WAITING_FOR_RESOURCES comes before PLANNING in `QueryState.java:30-38`, and `transitionToWaitingForResources()` only fires when `currentState.ordinal() < WAITING_FOR_RESOURCES` (`QueryStateMachine.java:1227-1231`). Inside `SqlQueryExecution.start()` the query is already PLANNING (:401). The existing required-workers wait happens before execution starts, in `LocalDispatchQuery.waitForMinimumWorkers()` (`LocalDispatchQuery.java:123-143`).
3. **The wait would count against the planning time limit.** `QueryTracker` would kill the query with EXCEEDED_TIME_LIMIT once `query.max-planning-time` is exceeded (`QueryTracker.java:190-199`). It also would not show up in `QueryStats.resourceWaitingTime`.
4. **The wait must not block a thread.** `start()` runs on the query executor, and the PLANNING listener interrupts that thread when the query fails (:406-416).
5. **`getMaxMemory()` can be unknown.** It returns a `double` and uses NaN for unknown (`PlanCostEstimate.java:95-104`).
6. **Small detail:** `registerDynamicFilteringQuery(plan)` sits between `planQuery` and `planDistribution` (`SqlQueryExecution.java:420-424`).
7. **The 4GB fallback is probably unnecessary.** The coordinator already has real per-worker pool sizes from `ClusterMemoryManager.getWorkersMemoryInfo()` (`ClusterMemoryManager.java:489`) and `query.max-memory-per-node` (`NodeMemoryConfig.java:42`, bound on all nodes at `ServerMainModule.java:274`).

## Error code
`GENERIC_INSUFFICIENT_RESOURCES(131072)` exists (`StandardErrorCode.java:199`). The required-workers timeout already throws it from `ClusterSizeMonitor`. Reusing `waitForMinimumWorkers` gives that error for free, so no SPI change is needed.

## Config conventions (`.github/DEVELOPMENT.md:183-220`)
- Config names use dashes; session names use snake_case.
- Every setter needs `@ConfigDescription`; every session property needs a description.
- Validation annotations go on getters: `@MinDataSize`, `@DecimalMin("1.0")` (examples at `TaskManagerConfig.java:466`, `DynamicFilterConfig.java:112`), `@Min(1)`, `@NotNull`.
- Don't store the config object as a field; copy the values out in the constructor.
- Each config class needs `TestXxxConfig` with `assertRecordedDefaults(recordDefaults(...))` and `assertFullMapping` (`TestQueryManagerConfig.java:49, :309`).
- **Naming and placement:** a top-level `capacity-planner.*` prefix doesn't match the code. The neighbouring properties are `query-manager.required-workers*` in QueryManagerConfig. Putting the new properties there (e.g. `query-manager.memory-based-required-workers.*`) avoids a new class and its Guice binding.
- **Session overrides** are expected, because the required-workers properties already have them (`required_workers_count`, `SystemSessionProperties.java:745-754`, added in commit `f8a14e1e42e`). At least the enable flag and the safety multiplier should get one.

## Guice wiring
- `ClusterSizeMonitor` is coordinator-only (`CoordinatorModule.java:327-328`). Any new service belongs there too, as a SINGLETON plus a JMX export if it has metrics.
- If the config gets its own class, bind it in CoordinatorModule with `configBinder(binder).bindConfig(...)`; QueryManagerConfig is already bound in ServerMainModule (:225).
- If the check stays in `SqlQueryExecution`, the new service has to be passed through the `SqlQueryExecutionFactory` @Inject constructor and the private constructor, which already takes 36 parameters (:153-189).

## Tests
- **Integration:** `testing/trino-tests/.../tests/TestMinWorkerRequirement.java` already tests adding workers while a query waits. `testMultipleRequiredWorkerNodesSessionOverride` (:194-240) starts with 0 workers, submits async, asserts the query is gated, calls `queryRunner.addServers(1)` (`DistributedQueryRunner.java:355`), then checks `getResourceWaitingTime()`. Copy this pattern.
- **Unit:** `core/trino-main/src/test/.../dispatcher/TestLocalDispatchQuery.java` uses a hand-written `TestClusterSizeMonitor` subclass (:230); mocks are banned (`DEVELOPMENT.md:102`). State-transition tests live in `TestQueryStateMachine.java` (:172 `testStateChangesWithResourceWaiting`). Add a pure unit test for the required-workers maths (NaN, floor, cap, multiplier).

## Observability and SPI
`QueryStats` and the SPI's `QueryStatistics.resourceWaitingTime` (`QueryStatistics.java:40`) already cover the wait, as long as it really is in WAITING_FOR_RESOURCES. New SPI fields (predicted memory, predicted workers) are checked by revapi (`core/trino-spi/pom.xml:197-298`) and need new constructor overloads. Keep them out of v1; JMX `@Managed` counters are enough.

## Docs
- Add the new properties to `docs/src/main/sphinx/admin/properties-query-management.md` (or `properties-resource-management.md`).
- `query-manager.required-workers*` isn't documented today; it appears only in release notes (release-330, 312, 0.214, 0.182).

## Prior work
- No autoscaling or capacity-planner code exists.
- Related commits: `975e95d79ce` (JMX gauges for WAITING_FOR_RESOURCES), `f8a14e1e42e` (required-workers session overrides), `ea27dc4f95c`, `e8f5821b763`.

## File checklist (estimated LOC)
| File | Change | LOC |
|---|---|---|
| `execution/QueryManagerConfig.java`, or a new config class | properties | ~60-90 |
| `SystemSessionProperties.java` | 2-3 session properties and getters | ~30 |
| New estimator service | new | ~80-120 |
| `dispatcher/LocalDispatchQuery.java` or `SqlQueryExecution.java` + factory | hook | ~30-60 |
| `server/CoordinatorModule.java` | binding | ~3-5 |
| `TestQueryManagerConfig.java` (or new TestXxxConfig) | defaults and mapping | ~20-60 |
| New estimator unit test | new | ~100 |
| `TestLocalDispatchQuery.java` / `TestQueryStateMachine.java` | cases | ~40 |
| `TestMinWorkerRequirement.java` (or new `TestMemoryBasedWorkerRequirement`) | integration with `addServers` | ~80-120 |
| `docs/.../admin/properties-query-management.md` | docs | ~40-60 |

Total is about 500-700 LOC.
