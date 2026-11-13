# Code analysis: config, wiring, tests, docs and conventions

Date: 2026-11-08. The analysis refers to trinodb/trino master `8ae1f91f853`. I checked the state-order and property-name findings a second time by hand.

## Factual errors in the proposal
1. **Wrong property name.** `query.required-workers-max-wait-time` does not exist. The real config is `query-manager.required-workers-max-wait` (`QueryManagerConfig.java:647`). Its session override is `required_workers_max_wait_time` (`SystemSessionProperties.java:155, :751`). Use `getRequiredWorkersMaxWait(session)` again (:1733).
2. **The state machine cannot go back.** WAITING_FOR_RESOURCES comes before PLANNING in `QueryState.java:30-38`. `transitionToWaitingForResources()` fires only when `currentState.ordinal() < WAITING_FOR_RESOURCES` (`QueryStateMachine.java:1227-1231`). In `SqlQueryExecution.start()`, the query is already in PLANNING (:401). The existing required-workers wait occurs before execution starts, in `LocalDispatchQuery.waitForMinimumWorkers()` (`LocalDispatchQuery.java:123-143`).
3. **The wait would count against the planning time limit.** When the planning time is more than `query.max-planning-time`, `QueryTracker` would kill the query with EXCEEDED_TIME_LIMIT (`QueryTracker.java:190-199`). Also, the wait would not show in `QueryStats.resourceWaitingTime`.
4. **The wait must not block a thread.** `start()` runs on the cached `dispatcher-query-%s` pool (`DispatchExecutor.java:52`). Correction (2026-11-11): I first wrote that it runs on the query executor. The PLANNING listener interrupts that thread when the query fails (:406-416).
5. **`getMaxMemory()` can be unknown.** It returns a `double` and uses NaN for unknown (`PlanCostEstimate.java:95-104`).
6. **Small detail:** `registerDynamicFilteringQuery(plan)` is between `planQuery` and `planDistribution` (`SqlQueryExecution.java:420-424`).
7. **The 4GB fallback is probably not necessary.** The coordinator already has the real pool sizes of each worker from `ClusterMemoryManager.getWorkersMemoryInfo()` (`ClusterMemoryManager.java:489`). It also has `query.max-memory-per-node` (`NodeMemoryConfig.java:42`, bound on all nodes at `ServerMainModule.java:274`).

## Error code
`GENERIC_INSUFFICIENT_RESOURCES(131072)` exists (`StandardErrorCode.java:199`). The required-workers timeout in `ClusterSizeMonitor` already throws it. If the feature uses `waitForMinimumWorkers` again, it gets that error with no more work. Thus, the feature needs no SPI change.

## Config conventions (`.github/DEVELOPMENT.md:183-220`)
- Config names use dashes. Session names use snake_case.
- Each setter needs `@ConfigDescription`. Each session property needs a description.
- Validation annotations go on getters. Examples are `@MinDataSize`, `@DecimalMin("1.0")` (see `TaskManagerConfig.java:466`, `DynamicFilterConfig.java:112`), `@Min(1)` and `@NotNull`.
- Do not keep the config object as a field. Copy the values in the constructor.
- Each config class needs a `TestXxxConfig` with `assertRecordedDefaults(recordDefaults(...))` and `assertFullMapping` (`TestQueryManagerConfig.java:49, :309`).
- **Naming and placement:** A top-level `capacity-planner.*` prefix does not agree with the code. The nearby properties are `query-manager.required-workers*` in QueryManagerConfig. If the new properties go there (for example, `query-manager.memory-based-required-workers.*`), the feature needs no new class and no new Guice binding.
- **Session overrides:** The code convention expects them, because the required-workers properties already have them (`required_workers_count`, `SystemSessionProperties.java:745-754`, added in commit `f8a14e1e42e`). At a minimum, the enable flag and the safety multiplier should have one.

## Guice wiring
- `ClusterSizeMonitor` runs only on the coordinator (`CoordinatorModule.java:327-328`). Thus, a new service also belongs there, as a SINGLETON. If it has metrics, it also needs a JMX export.
- If the config gets its own class, bind it in CoordinatorModule with `configBinder(binder).bindConfig(...)`. ServerMainModule already binds QueryManagerConfig (:225).
- If the check stays in `SqlQueryExecution`, the new service must go through the `@Inject` constructor of `SqlQueryExecutionFactory` and through the private constructor. The private constructor already takes 36 parameters (:153-189).

## Tests
- **Integration:** `testing/trino-tests/.../tests/TestMinWorkerRequirement.java` already tests the addition of workers while a query waits. Copy the pattern of `testMultipleRequiredWorkerNodesSessionOverride` (:194-240):
  1. It starts with 0 workers.
  2. It submits the query async.
  3. It asserts that the gate holds the query.
  4. It calls `queryRunner.addServers(1)` (`DistributedQueryRunner.java:355`).
  5. It checks `getResourceWaitingTime()`.
- **Unit:** `core/trino-main/src/test/.../dispatcher/TestLocalDispatchQuery.java` uses a hand-written `TestClusterSizeMonitor` subclass (:230). The project bans mocks (`DEVELOPMENT.md:102`). State-transition tests are in `TestQueryStateMachine.java` (:172 `testStateChangesWithResourceWaiting`). Add a pure unit test for the required-workers maths (NaN, floor, cap, multiplier).

## Observability and SPI
`QueryStats` and the SPI field `QueryStatistics.resourceWaitingTime` (`QueryStatistics.java:40`) already cover the wait, but only if the query really is in WAITING_FOR_RESOURCES. Revapi checks new SPI fields such as predicted memory and predicted workers (`core/trino-spi/pom.xml:197-298`). These fields also need new constructor overloads. Keep them out of v1. JMX `@Managed` counters are sufficient.

## Docs
- Add the new properties to `docs/src/main/sphinx/admin/properties-query-management.md` (or `properties-resource-management.md`).
- Today, the docs do not describe `query-manager.required-workers*`. It shows only in release notes (release-330, 312, 0.214, 0.182).

## Prior work
- No autoscaling or capacity-planner code exists.
- The related commits are `975e95d79ce` (JMX gauges for WAITING_FOR_RESOURCES), `f8a14e1e42e` (required-workers session overrides), `ea27dc4f95c` and `e8f5821b763`.

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

The total is about 500-700 LOC.
