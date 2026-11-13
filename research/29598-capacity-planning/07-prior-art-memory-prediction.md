# Prior art: predicting query memory before execution (2026-11-11)

I examined four areas: PrestoDB HBO, industry, academic work and Trino internals. I re-checked the key claims, as marked. "Checked" means that I read the source. "Not checked" means that the claim comes from memory or from a summary only.

## Bottom line
- **No public system predicts per-query peak memory from history and uses it for admission.** The closest systems are Redshift Auto-WLM (ML on plan features, memory and time) and Databricks serverless IWM (ML, details not public). Microsoft AutoToken predicts peak *tokens* for recurring jobs only.
- **History keyed by a plan fingerprint is proven for plan statistics** (PrestoDB HBO) and for resource sizing of recurring jobs (AutoToken). Coverage is partial: AutoToken covered 21.8% of jobs in one sample, and 40–60% of SCOPE jobs are new.
- **Plan statistics alone are weak for multi-join queries.** Cardinality errors grow with the number of joins (Leis et al., PVLDB 2015). SCOPE estimates were off by 10⁴× too low to 10⁶× too high.
- **No peer-reviewed paper reports the accuracy of SQL peak-memory prediction.** The TPC-DS measurement would thus measure something new. It is a real contribution, not a formality.
- **The proposed oracle order matches the closest production design.** The Redshift "Stage" predictor (SIGMOD 2024, run time only) uses an exact-match cache, then a local model, then a global model.

## PrestoDB History-Based Optimization (HBO)
Checked in the code of prestodb/presto master.
- **Plan fingerprint:** per plan node, a SHA-256 hash of a canonical plan string with normalized variable names. Strategies go from strict to loose: `DEFAULT(0)`, `CONNECTOR(1)`, `IGNORE_SAFE_CONSTANTS(2)`, `IGNORE_SCAN_CONSTANTS(3)`. The default is `IGNORE_SAFE_CONSTANTS`. Lookup tries the strategies in order and takes the first hit. Connectors can drop partition-key constants (Hive does). Thus `ds='2020-01-01'` and `ds='2020-01-02'` give the same hash.
- **What HBO stores (`PlanStatistics`):** rowCount, outputSize, confidence, join, table-writer and partial-aggregation statistics. **No peak memory, CPU or time.** Each history entry also stores the row counts and sizes of the input tables. A run matches only if each input table is within `hbo.history-matching-threshold` (0.1) of the current one. HBO keeps the last 10 runs.
- **Used by:** only rules that shape the plan (join distribution and reordering, partial aggregation pushdown, scaled writers). **Not used for memory, admission or scheduling.**
- **SPI:** `HistoryBasedPlanStatisticsProvider` with `getStats(List<PlanNodeWithHash>, timeoutMs)` and `putStats(...)`. Redis is the only open-source backend. The lookup fails open after a timeout (10s). HBO is off by default, with separate *use* and *track* flags.
- **Papers:** "Presto: A Decade of SQL Analytics at Meta" (SIGMOD 2023, §5.2) and "Presto's History-based Query Optimizer" (VLDB 2024). I did **not extract** the impact numbers. Do not quote any yet.
- **What transfers:**
  - the canonical hash with a ladder of strategies.
  - connector hooks that ignore partition constants.
  - storage of input sizes, and no match when they drift.
  - the last N runs.
  - a key-value SPI with a timeout that fails open.
- **What does not transfer:** HBO works per node, but admission needs a query-level key (e.g. the root hash). HBO does not record peak memory. The code uses the PlanNode types of Presto and does not port directly.

## Industry

| System | Trigger | Prediction | Source |
|---|---|---|---|
| Redshift Auto-WLM | predicted memory and time drive admission and concurrency scaling | XGBoost per cluster on optimizer plan features, trained on recent queries | SIGMOD 2023 Companion (checked through a blog summary only). Accuracy not public |
| Redshift Stage | run-time prediction only | exact-match cache → local model with uncertainty → global GCN. >2× more accurate than the Auto-WLM predictor, −20% average latency | arXiv 2403.02286 (abstract checked) |
| Microsoft SCOPE AutoToken | predicted peak tokens replace the user's request | per-template models over plan signatures. Recurring jobs only | VLDB 2020 (not checked. PDF not extracted) |
| Databricks serverless IWM | predicts the resources of each query on arrival. Queues if there is no capacity. Scales on queue wait | ML, not public | docs (checked) |
| Snowflake, BigQuery, Starburst Enterprise, trinodb/charts | queue length, slot or CPU usage | **reactive** | docs (checked) |
| Starburst Galaxy | CPU, *estimated* CPU time, queue, completed-query insights | proprietary | blog (checked) |
| Trino Gateway | static or external routing rules | none built in. Maintainers run `EXPLAIN` externally and get **NaN cost with partial aggregation** (trino-gateway#667, checked) | GitHub |

A write-up about Trino on Kubernetes reports that the queued-queries and `RequiredWorkers` triggers stay at 0 in practice. The cause is that QUEUED counts only queries that wait in a resource group. A predictive signal needs its own metric.

## Academic
- Leis et al., "How Good Are Query Optimizers, Really?", PVLDB 9(3), 2015, https://doi.org/10.14778/2850583.2850594 (checked). Errors grow quickly with the number of joins. Cost-model errors are small in comparison to cardinality errors.
- Wang et al., "Are We Ready For Learned Cardinality Estimation?", PVLDB 14(9), 2021: learned estimators are not ready for production when the data changes (checked).
- Jindal et al. (SIGMOD 2018, SoCC 2019, EDBT 2021): >60% to ~80% of SCOPE jobs are recurring (checked through summaries only). That is batch analytics. The repeat rate of Trino needs a measurement.
- CardLearner (PVLDB 2019): learned per-subgraph micromodels from history, with coverage of about 60% of jobs (checked only that the paper exists).
- Ganapathi (ICDE 2009), Akdere (ICDE 2012), Li (PVLDB 2012): accuracy figures not checked. Do not cite them.

## Trino today (checked in code)
- **No query or plan fingerprint and no persistent history.** Each adaptive mechanism (FTE `AdaptivePlanner`, `ExponentialGrowthPartitionMemoryEstimator`, adaptive partial aggregation) is per query and in memory.
- **No upstream proposal for history-based statistics, accepted or rejected.** Issue #29395 incorrectly says that `collect-plan-statistics-for-all-queries` is HBO. That setting persists nothing.
- **Trino does not record per-node per-query peak memory.** Only these peaks exist:
  - query-level (`peakUserMemoryBytes`, `peakTaskUserMemory`, `peakTaskTotalMemory`).
  - stage-level (`peakUserMemoryReservation`).
  - task-level (`TaskStatus.peakMemoryReservation`).
- **The hook seam** (`SqlQueryExecution.start()` between `planQuery` and `planDistribution`) has the fragmented `SubPlan`, the `Plan` with `StatsAndCosts`, the inputs and the session.
- **Event listeners get the plan only at completion** (`QueryMonitor`), as text and JSON, with literals and node ids included.

## Gaps an oracle would need to fill
1. A canonical plan fingerprint that normalizes literals (query-level key, PrestoDB-style ladder of strategies).
2. Per-node per-query peak memory, aggregated from task peaks and the node where each task ran.
3. A persistent history store behind an SPI (get/put with a timeout, fails open). It stores `{peak per node, input table sizes, time}` for the last N runs.
4. A post-planning hook API: proceed / wait for N (with a timeout) / infeasible, plus a demand metric for the autoscaler.
5. A plan-statistics fallback that tolerates NaN (depends on #32089).
