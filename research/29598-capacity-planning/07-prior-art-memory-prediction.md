# Prior art: predicting query memory before execution (2026-11-11)

Four Sonnet researchers (PrestoDB HBO, industry, academic, Trino-internal). Key claims were re-checked by Claude, as marked. **VERIFIED** means the source was read; **UNVERIFIED** means it comes from memory or a summary only.

## Bottom line
- **No public system predicts per-query peak memory from history and uses it for admission.** The closest are Redshift Auto-WLM (ML on plan features, memory and time) and Databricks serverless IWM (ML, details not public). Microsoft AutoToken predicts peak *tokens* for recurring jobs only.
- **History keyed by a plan fingerprint is proven for plan statistics** (PrestoDB HBO) and for resource sizing of recurring jobs (AutoToken). Coverage is partial: AutoToken covered 21.8% of jobs in one sample, and 40–60% of SCOPE jobs are new.
- **Plan statistics alone are weak for multi-join queries.** Cardinality errors grow with the number of joins (Leis et al., PVLDB 2015). SCOPE estimates were off by 10⁴× too low to 10⁶× too high.
- **No peer-reviewed paper reports SQL peak-memory prediction accuracy.** We would be measuring something new, so the TPC-DS measurement is a real contribution, not a formality.
- **The proposed oracle order matches the closest production design.** Redshift's "Stage" predictor (SIGMOD 2024, run time only) uses an exact-match cache, then a local model, then a global model.

## PrestoDB History-Based Optimization (HBO)
Code checked by Claude in prestodb/presto master.
- **Plan fingerprint:** per plan node, a SHA-256 hash of a canonical plan string with variable names normalized. Strategies run from strict to loose: `DEFAULT(0)`, `CONNECTOR(1)`, `IGNORE_SAFE_CONSTANTS(2)`, `IGNORE_SCAN_CONSTANTS(3)`. The default is `IGNORE_SAFE_CONSTANTS`. Lookup tries the strategies in order and takes the first hit. Connectors can drop partition-key constants (Hive does), so `ds='2020-01-01'` and `ds='2020-01-02'` hash the same.
- **What's stored (`PlanStatistics`):** rowCount, outputSize, confidence, join, table-writer and partial-aggregation statistics. **No peak memory, CPU or time.** Each history entry also stores the input tables' row counts and sizes. A run only matches if every input table is within `hbo.history-matching-threshold` (0.1) of the current one. The last 10 runs are kept.
- **Used by:** only plan-shaping rules (join distribution and reordering, partial aggregation pushdown, scaled writers). **Not used for memory, admission or scheduling.**
- **SPI:** `HistoryBasedPlanStatisticsProvider` with `getStats(List<PlanNodeWithHash>, timeoutMs)` and `putStats(...)`. Redis is the only open-source backend. Fails open after a timeout (10s). Off by default, with separate *use* and *track* flags.
- **Papers:** "Presto: A Decade of SQL Analytics at Meta" (SIGMOD 2023, §5.2) and "Presto's History-based Query Optimizer" (VLDB 2024). Impact numbers **not extracted**; don't quote any yet.
- **What transfers:** the canonical hash with a ladder of strategies; connector hooks to ignore partition constants; storing input sizes and refusing to match when they drift; keeping the last N runs; a key-value SPI with a timeout that fails open.
- **What doesn't:** it's per node, while admission needs a query-level key (e.g. the root hash). Peak memory isn't recorded. The code is built on Presto's PlanNode types and doesn't port directly.

## Industry

| System | Trigger | Prediction | Source |
|---|---|---|---|
| Redshift Auto-WLM | predicted memory and time drive admission and concurrency scaling | XGBoost per cluster on optimizer plan features, trained on recent queries | SIGMOD 2023 Companion (VERIFIED via a blog summary); accuracy not public |
| Redshift Stage | run-time prediction only | exact-match cache → local model with uncertainty → global GCN; >2× more accurate than the Auto-WLM predictor, −20% average latency | arXiv 2403.02286 (abstract VERIFIED) |
| Microsoft SCOPE AutoToken | predicted peak tokens replace the user's request | per-template models over plan signatures; recurring jobs only | VLDB 2020 (UNVERIFIED; PDF not extracted) |
| Databricks serverless IWM | predicts each query's resources on arrival; queues if there's no capacity; scales on queue wait | ML, not public | docs (VERIFIED) |
| Snowflake, BigQuery, Starburst Enterprise, trinodb/charts | queue length, slot or CPU usage | **reactive** | docs (VERIFIED) |
| Starburst Galaxy | CPU, *estimated* CPU time, queue, completed-query insights | proprietary | blog (VERIFIED) |
| Trino Gateway | static or external routing rules | none built in; maintainers run `EXPLAIN` externally and hit **NaN cost with partial aggregation** (trino-gateway#667, VERIFIED by Claude) | GitHub |

A Trino-on-Kubernetes write-up reports that the queued-queries and `RequiredWorkers` triggers stay at 0 in practice, because QUEUED counts only queries waiting in a resource group. A predictive signal needs its own metric.

## Academic
- Leis et al., "How Good Are Query Optimizers, Really?", PVLDB 9(3), 2015, https://doi.org/10.14778/2850583.2850594: errors grow quickly with the number of joins, and cost-model errors are small next to cardinality errors (VERIFIED).
- Wang et al., "Are We Ready For Learned Cardinality Estimation?", PVLDB 14(9), 2021: learned estimators are not production-ready under data updates (VERIFIED).
- Jindal et al. (SIGMOD 2018, SoCC 2019, EDBT 2021): >60% to ~80% of SCOPE jobs are recurring (VERIFIED via summaries). That's batch analytics; Trino's repeat rate needs measuring.
- CardLearner (PVLDB 2019): learned per-subgraph micromodels from history, covering about 60% of jobs (VERIFIED to exist).
- Ganapathi (ICDE 2009), Akdere (ICDE 2012), Li (PVLDB 2012): accuracy figures UNVERIFIED; don't cite them.

## Trino today (checked in code)
- **No query or plan fingerprint and no persistent history.** Every adaptive mechanism (FTE `AdaptivePlanner`, `ExponentialGrowthPartitionMemoryEstimator`, adaptive partial aggregation) is per query and in memory.
- **No upstream proposal for history-based statistics, accepted or rejected.** Issue #29395 wrongly says `collect-plan-statistics-for-all-queries` is HBO; that setting persists nothing.
- **Per-node per-query peak memory is not recorded.** Only query-level (`peakUserMemoryBytes`, `peakTaskUserMemory`, `peakTaskTotalMemory`), stage-level (`peakUserMemoryReservation`) and task-level (`TaskStatus.peakMemoryReservation`) peaks exist.
- **The hook seam** (`SqlQueryExecution.start()` between `planQuery` and `planDistribution`) has the fragmented `SubPlan`, the `Plan` with `StatsAndCosts`, the inputs and the session.
- **The plan reaches event listeners only at completion** (`QueryMonitor`), as text and JSON, with literals and node ids included.

## Gaps an oracle would need to fill
1. A canonical, literal-normalizing plan fingerprint (query-level key, PrestoDB-style ladder of strategies).
2. Per-node per-query peak memory, aggregated from task peaks and the node each task ran on.
3. A persistent history store behind an SPI (get/put with a timeout, fails open), storing `{peak per node, input table sizes, time}` for the last N runs.
4. A post-planning hook API: proceed / wait for N (with a timeout) / infeasible, plus a demand metric for the autoscaler.
5. A plan-statistics fallback that tolerates NaN (depends on #32089).
