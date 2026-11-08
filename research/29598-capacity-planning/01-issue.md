# Issue trinodb/trino#29598 (snapshot 2026-11-08)

Source: https://github.com/trinodb/trino/issues/29598

**Title:** Pre-flight cluster capacity planning to prevent mid-execution OOMs

**Author:** ravidhu

---

### Feature Request / Improvement

Trino currently dispatches a query as soon as planning completes. If the cluster cannot fit the query, the failure surfaces as an OOM mid-execution — typically `EXCEEDED_LOCAL_MEMORY_LIMIT` after the query has already consumed CPU, network, and worker memory. From a user's perspective this is the worst class of failure: minutes of waiting, no result, and no signal it was coming. Operators today either over-provision the cluster permanently or accept these failures as the cost of running memory-heavy workloads.

CPU/memory-based autoscaling (the existing `server.autoscaling` path in the Helm chart) is reactive: it scales after pressure manifests, which is too late for the query that triggered it.

Trino already ships two mechanisms that overlap part of this problem space, and the proposal is shaped around what they don't cover:

- [Spill-to-disk](https://trino.io/docs/current/admin/spill.html) lets joins, aggregations, sorts, and window functions exceed memory by writing operator state to local disk. The docs now call it *"a legacy functionality"* and recommend FTE instead; [`trinodb/trino#22845`](https://github.com/trinodb/trino/issues/22845) tracks removing it. Even where it works, the docs warn it *"does not guarantee execution of all memory intensive queries"* and spilled queries can run *"orders of magnitude slower."* Coverage is operator-specific — non-spillable operators still OOM.
- [Fault-tolerant execution](https://trino.io/docs/current/admin/fault-tolerant-execution.html) with `retry-policy=TASK` materializes exchanges to external storage and retries failed tasks, escalating to a full-node memory slice on OOM. It rescues transient worker failures and some memory failures, but (a) requires an Exchange Manager backed by S3/GCS/Azure/HDFS, (b) adds materialization overhead the docs flag as unsuitable for short interactive queries — the standard recommendation is a dedicated FTE cluster — and (c) cannot exceed the largest single node: a task that genuinely doesn't fit anywhere keeps failing after retries.

Spill survives within a fixed cluster at a heavy latency cost; FTE retries at the cost of object-store I/O and interactive latency, capped at one node's memory. Neither right-sizes the cluster for the query, which is what this proposal is for. The three are complementary — an FTE-enabled cluster still benefits from being sized correctly before the query starts.

### Proposal

Add an optional pre-flight step between `planQuery` and `planDistribution` in `SqlQueryExecution.start()`. When enabled, the coordinator:

1. Reads `io.trino.cost.PlanCostEstimate.getMaxMemory()` from the freshly planned query.
2. Computes `requiredWorkers = clamp(ceil(max(estimate, FLOOR) × SAFETY / perWorkerCapacity), 1, MAX_WORKERS)`.
3. Transitions the query to the existing `WAITING_FOR_RESOURCES` state.
4. Waits via the existing `io.trino.execution.ClusterSizeMonitor.waitForMinimumWorkers(N, timeout)`.

The required worker count is exposed through the existing `@Managed ClusterSizeMonitor.getRequiredWorkers()` attribute (MBean `io.trino.execution:name=ClusterSizeMonitor`, attribute `RequiredWorkers`, `int`). **No new MBean.** The patch widens the conditions under which this gauge is non-zero; existing consumers see strictly more signal.

External autoscalers — HPA + custom-metrics-adapter, KEDA, etc. — consume the gauge to size the cluster *before* the query starts running. Trino itself takes no Kubernetes dependency. The upstream `trinodb/charts` Helm chart already documents this gauge as the canonical `server.keda.triggers` example.

### Why this shape

- **Reuses existing primitives.** `WAITING_FOR_RESOURCES`, `ClusterSizeMonitor.waitForMinimumWorkers`, the `@Managed` gauge, `query.required-workers-max-wait-time`, and `PlanCostEstimate` all already exist. The patch is a wiring change plus one new service and config class.
- **Off by default.** New properties live under `capacity-planner.*` and default to disabled. When disabled, the only code path executed is an `isEnabled()` check returning `false`.
- **Predictive, not reactive.** The signal is computed before execution, so the autoscaler reacts before resource pressure rather than after a query starts spilling or OOMing.

### Configuration sketch

| Property | Default | Purpose |
|---|---|---|
| `capacity-planner.enabled` | `false` | Master switch |
| `capacity-planner.floor` | `200MB` | Minimum estimate before applying safety |
| `capacity-planner.safety-multiplier` | `2.0` | Applied to `max(estimate, floor)` |
| `capacity-planner.per-worker-capacity-fallback` | `4GB` | Cold-start only; otherwise discovered from `ClusterMemoryManager` |
| `capacity-planner.max-workers` | `20` | Upper bound on predicted worker count |

Wait timeout reuses `query.required-workers-max-wait-time`. Full property docs and rationale will live in the PR.

### Validation summary

A separate POC validated the design end-to-end on Minikube with TPC-DS at SF 100:

- Mechanism: plan → predict → wait → external autoscaler scales → query proceeds. Closed-loop demo with a fake autoscaler polling the JMX gauge and calling `kubectl scale`.
- OOM-class rescue rate at SF 100: 3 of 4 queries rescued. The remaining escape (q67) combines two effects that the hardware budget couldn't isolate: `PlanCostEstimate.maxMemory` under-predicts there, and manually scaling to 10 workers hit a node-memory ceiling on the laptop-class test rig. Re-running on cluster-class hardware is on the follow-up list.
- No regression on small queries: at-or-below-floor queries still schedule on 1 worker.

### Open questions for reviewers

1. **Placement.** Is between `planQuery` and `registerDynamicFilteringQuery` the right hook point? The plan is fully cost-annotated by then; nothing past that point currently depends on cluster size. Open to alternatives.
2. **One gauge or two?** Should `RequiredWorkers` stay a single value (max across all waiting queries, the current semantics) or split into separate gauges for the pre-existing pre-execution-min wait versus the new admission wait? Splitting clarifies observability but breaks the chart's single-trigger contract.
3. **Resource-group integration.** `getRequiredWorkers()` is a single global value. Multi-tenant production setups likely want per-resource-group capacity. Is that a v1 concern or a follow-up?
4. **Trivial-query bypass.** `EXPLAIN`, `EXPLAIN ANALYZE`, DDL, `USE`, single-row literals all currently transition through `WAITING_FOR_RESOURCES` briefly even when the wait resolves immediately. Preference for skipping by `analysis.getStatement()` type, or short-circuiting on `estimate == 0 && floor doesn't bump`?
5. **Timeout error.** The wait currently throws `RuntimeException`; the PR will switch to `TrinoException(GENERIC_INSUFFICIENT_RESOURCES, ...)` with predicted-vs-available counts. Any preference on a different error code?

### What I'm asking for

- Sanity check that the approach is wanted at all before I invest in finalizing the PR.
- A maintainer with bandwidth to review.
- Direction on the open questions above so the PR lands close to the shape reviewers want.

Happy to share more from the POC — formula sweep across `(FLOOR, SAFETY)`, plan-cost-accuracy data per query, full benchmark catalog — kept out of the issue to stay focused on the design.


---

## Comments

**RafPe:** @ravidhu quite interesting tackle of the challenge - would you be able to share more of the details of your PoC ?
