# Implementation plan

## Local environment (set up on 2026-11-08)

- **Host:** aarch64, 16 cores, 124 GB RAM, Ubuntu 26.04.
- **JDK:** `openjdk-25-jdk-headless` 25.0.4, matching the repo's `.java-version` of 25.
- **mvnd 1.0.6** (Maven 3.9.16), installed in `~/.local/opt`. `java.home` is set in `~/.m2/mvnd.properties`.
- **Warm-up build:** `mvnd -pl core/trino-main -am install -DskipTests -Dair.check.skip-all=true` took 2 min 10 s.
- `trino-tests` is not built yet. The integration test phase needs it.

## Phases (wall-clock agent time, not human review)

| # | Phase | Parallel? | Estimate |
|---|---|---|---|
| 0 | Setup and warm-up build | no | done |
| 1 | Workstreams WS1–WS3, each in its own git worktree with no shared files | yes | 1–2 h |
| 2 | Integration: wire the estimator into the planning/wait path, inject it in `SqlQueryExecutionFactory`, bind it in `CoordinatorModule` | no | ~1 h |
| 3 | Integration test `TestMemoryBasedWorkerRequirement`, modelled on `TestMinWorkerRequirement` (start with 0 workers, `addServers(N)`, assert on the wait) | no | 45–90 min |
| 4 | Verification: `airstyle:check`, the targeted tests in `trino-main`, `TestQueryManagerConfig`, and a code review | partly | ~1 h |
| 5 | Docs, commit split per the commit policy, PR text from the template | no | 30 min |

The total for Tiers A+B is about 5–7 hours of agent wall-clock time, ending as 3–4 commits.

## Workstreams

| Workstream | Files it owns | Approx. lines |
|---|---|---|
| **WS1 – gauges** | `ClusterSizeMonitor` (summed-demand and running-query gauges, keeping `RequiredWorkers`), `TestClusterSizeMonitor` | ~200 |
| **WS2 – estimator and config** | New pure `QueryCapacityEstimator` (per stage, **statistics-based**, NaN means skip, an "infeasible" result), `QueryManagerConfig` + `SystemSessionProperties` properties, unit tests, `TestQueryManagerConfig` | ~600 |
| **WS3 – asynchronous start** | Two-phase `SqlQueryExecution.start()` behind an interface with a stub estimator, `QueryStateTimer` capacity-wait time, `QueryTracker` excluding the wait from planning time, `TestQueryStateMachine` | ~400 |
| **Tier C** | Research done ([05-tier-c-assessment.md](05-tier-c-assessment.md)). Proposed next step: the missing statistics rules only. | ~250 production code |

WS2 alone edits `SystemSessionProperties`. The others get their values through the interface, so
merging their branches doesn't conflict.

## Child workspaces (Orca)

All four are children of `RafPe/pre-flight-cluster-capacity-planning-to-prevent`:

- `RafPe/capacity-ws1-gauges`
- `RafPe/capacity-ws2-estimator`
- `RafPe/capacity-ws3-async-start`
- `RafPe/capacity-tierc-cbo-cost-visitors` (research complete)

## Bottlenecks and risks

- The build, not the coding. Run specific test classes, never the whole `trino-main` test suite.
- Accuracy can't be shown locally; it needs a cluster and a TPC-DS run. Log predicted vs. actual peak memory so the multiplier can be calibrated.
- Tier C cost visitors could change query plans unless the plan-selection calculator keeps returning NaN for the new nodes (see the assessment, §2).
