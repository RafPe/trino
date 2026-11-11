# Tier C assessment: cost visitors for missing plan nodes (trinodb/trino#29598)

Research only. No source changes. Paths are relative to `core/trino-main/src/main/java/io/trino/` unless stated.

> **Correction (2026-11-08, verified after this assessment):** the plan-selection analysis below covers
> *costs* only. About 20 optimizer rules read *statistics* directly, for example `DetermineJoinDistributionType`
> (build-side stats), `DeterminePartitionCount`, `AddExchanges`, `MultipleDistinctAggregationToMarkDistinct`
> and `PushPartialAggregationThroughJoin`. New **stats rules** can therefore change plans even with the
> calculator split. Each stats-rule PR must check the cost-based plan golden files and report the diffs.

> **Correction (2026-11-11, verified):** only `ReorderJoins` uses the cost calculator to make
> plan decisions. `DetermineJoinDistributionType` and `DetermineSemiJoinDistributionType` compute their
> own local join cost from *statistics* (`calculateJoinCostWithoutOutput`), so new cost visitors don't
> affect them, but new stats rules do. `CostCalculatorWithEstimatedExchanges` delegates to
> `CostCalculatorUsingExchanges`, so the proposed split needs an explicit mode or a separate instance.
> EXPLAIN prints each node's *local* cost. The "10–25 golden files" figure in §2/§3 was never measured.

## TL;DR

- **Two causes of NaN.** Cost visitors are missing, and about half the affected nodes also have **no stats rule**. Window, MarkDistinct, GroupId, Unnest, Offset, TableWriter and grouping-set aggregation have no stats, so a cost visitor alone would still produce NaN. Stats rules are roughly half of the work.
- **Low regression risk if wired correctly.** Only `ReorderJoins`, `DetermineJoinDistributionType` and `DetermineSemiJoinDistributionType` use costs to make decisions. All three run *before* `AddExchanges` and go through the `@EstimatedExchanges` calculator. If that path keeps today's NaN semantics, plan selection does not change, and the final-plan calculator (`StatsAndCosts`, EXPLAIN, capacity) gets the new costs.
- **Size:** about 450–550 LOC of production code plus about 600–800 LOC of tests, in 3–4 PRs. Review difficulty is moderate. The hard part is agreeing on semantics, not code.

## 1. Per-node table

Notation:
- *in* is the source output bytes; *out* is the node's output bytes.
- **Acc** is `costForAccumulation`, **Str** is `costForStreaming` and **LJ** is `costForLookupJoin`.
- Visitors must return unknown when their input stats are unknown. The existing `TestCostCalculator.assertCostHasUnknownComponentsForUnknownStats` test relies on that.

| Node | Proposed cost (cpu / mem / net) | Stats rule? | Effort |
|---|---|---|---|
| Aggregation PARTIAL/INTERMEDIATE | cpu = *in*; mem = `min(out, task.max-partial-aggregation-memory)` (16MB default, `TaskManagerConfig:60`), so the config would need injecting; Str (it flushes) | **Yes.** `AggregationStatsRule:59` forwards source stats pessimistically | M (~40) |
| Aggregation with >1 grouping set | same as SINGLE/FINAL | **No.** The rule returns empty for `groupingSetCount != 1` (`AggregationStatsRule:53`) | M (stats) |
| Sort | cpu = *in*; mem = *in*; Acc | Yes (`SortStatsRule`) | S (~10) |
| TopN (PARTIAL/FINAL) | cpu = *in*; mem = `min(count, rows) × rowSize`; Acc | Yes | S (~12) |
| TopNRanking | cpu = *in*; mem = `min(in, maxRank × partitions × rowSize)`; Acc | Yes | M (~20) |
| DistinctLimit | cpu = *in*; mem = `min(limit, ndv) × rowSize`; Str | Yes | S (~10) |
| Sample, DynamicFilterSource | cpu = *in*; mem = 0; Str | Yes | S (~5 each) |
| Window | cpu = *in*; mem = *in* (worst case for partition buffering; exact when there is no PARTITION BY); Acc | **No.** Needs a pass-through rule (~25) | M |
| PatternRecognition | same as Window | **No** | M |
| MarkDistinct | cpu = *in*; mem = `ndv(keys) × keySize`, falling back to *in*; Str | **No** (~20) | M |
| GroupId | cpu = *out*; mem = 0; Str | **No.** Rows × number of sets (~30) | M |
| Unnest | cpu = *out*; mem = 0; Str | **No.** Cardinality needs a heuristic (~30) | M |
| Offset | cpu = *in*; mem = 0; Str | **No** (trivial) | S |
| TableFunctionProcessor | cpu = *in*; mem = *in* if partitioned, otherwise 0; Acc | **No** | M |
| TableWriter / MergeWriter / MergeProcessor | cpu = *in*; mem = bounded writer buffers (constant or `min(in, writers × buffer)`); Str | **No** | M |
| TableFinish, StatisticsWriter, TableExecute, SimpleTableExecute, TableDelete, TableUpdate, ExplainAnalyze | about zero; Str | **No** (trivial rules) | S each |
| IndexJoin / IndexSource | LJ with the index as build side | **No** | M (rare, can defer) |
| Intersect/Except, Apply/CorrelatedJoin, TableFunction | Memo-only; rewritten away before the final plan. Leave unknown | No | skip |
| RemoteSource, AdaptivePlanNode | Never reach the calculator | n/a | skip |

**Accuracy caveat for capacity planning:** `maxMemory` is a whole-plan sum, not per task or per worker. Window and Sort use *in* as an upper bound and will over-estimate, as upstream #3036 shows for OrderBy. The capacity feature should treat the value as a coarse ceiling.

## 2. Optimizer-regression analysis

**Cost-driven decisions** (verified; no other rule calls `getCostProvider()`):

| Rule | When it runs | Today, when the cost is NaN |
|---|---|---|
| `ReorderJoins` (`PlanOptimizers:824`) | before AddExchanges | `UNKNOWN_COST_RESULT` → `Result.empty()`, so the syntactic join order is kept |
| `DetermineJoinDistributionType` (`:886`) | before AddExchanges | Falls back to `getSizeBasedJoin` (table-scan sizes, 8x flip threshold) |
| `DetermineSemiJoinDistributionType` (`:889`) | before AddExchanges | Falls back to size-based: REPLICATED if within `join_max_broadcast_table_size` |

All three use `costCalculatorWithEstimatedExchanges` (`PlanOptimizers:332`). The switch to the plain calculator happens at `:925`, after `AddExchanges`. `CostComparator.compare` throws on unknown input, so every caller checks first. `join_reordering_strategy` and `join_distribution_type` both default to `AUTOMATIC`, so default users are exposed to any change.

**What would change if the visitors were simply turned on:**

- **Partial and intermediate aggregation: no plan impact.** `PushPartialAggregationThroughJoin`/`Exchange` run at `PlanOptimizers:1024`, after the join rules, so partial aggregations never sit under a join while costs drive decisions.
- **Window, TopN, TopNRanking, Sort, DistinctLimit, Unnest, GroupId and MarkDistinct (from `MultipleDistinctAggregationToMarkDistinct`, `:702`) do exist during join rules.** Once their subtree costs become known, these shapes switch from heuristics to CBO:
  - a join with a top-N-per-group or `ORDER BY … LIMIT` derived table;
  - a join with an UNNEST side, or with a COUNT(DISTINCT) side that uses MarkDistinct;
  - a semi-join whose filtering source contains these nodes;
  - **multi-way join chains with one such source**, where reordering newly turns on. This is the largest behavior change.
- **Output-only consumers.** EXPLAIN shows numbers instead of `?` (`TextRenderer:308-337`). `QueryCompletedEvent.planNodeStatsAndCosts` JSON gains values. `JoinNode.reorderJoinStatsAndCost` changes only if `ReorderJoins` changes.

**De-risking recommendation (ranked):**

1. **Structural split by calculator binding (recommended).** Give `CostCalculatorUsingExchanges` a mode, for example a constructor flag or a small subclass. The `@EstimatedExchanges` wrapper (`CoordinatorModule:339`) delegates to a "plan-selection" instance that keeps `unknown()` for the new node types. The plain `CostCalculator` binding (`CoordinatorModule:338`, used for the final `StatsAndCosts` at `LogicalPlanner:306`, EXPLAIN and capacity) uses full visitors. Plan selection stays byte-identical by construction, and capacity, EXPLAIN and event listeners get known costs. A session property can later let the CBO opt in.
2. **A session property gating the new visitors everywhere** (default off). Simpler, but EXPLAIN and events stay NaN by default and the plan-selection path is not isolated.
3. **A separate memory-only estimator.** Zero planner risk, but it duplicates the tree walk and can drift. Use only if maintainers refuse to extend `CostCalculator`.
4. **Enabling the visitors for everyone with no flag.** Not recommended: it needs a full TPC-H/TPC-DS benchmark pass.

## 3. Test churn

| Area | With option 1 | If CBO consumes the new costs |
|---|---|---|
| `TestCostCalculator` (only direct test, 12 tests, none assert NaN for new nodes) | +10–15 new tests, 0 edits | same |
| New `Test*StatsRule` classes (Window, MarkDistinct, GroupId, Unnest, Offset, TableWriter, …) | ~6–8 new files | same |
| Cost-based plan golden files (`testing/trino-tests/src/test/resources/sql/trino/**`, 125 `*.plan.txt`, shape only, no costs) | 0 | likely 10–25, high-end ~80 (TPC-DS q04/q11/q14/q23/q24/q64/q72/q74/q75/q78/q80 most exposed); regenerated via `main()` in `TestTpchCostBasedPlan` / `TestPartitionedTpcdsCostBasedPlan` |
| Rule tests (`TestReorderJoins`, `TestDetermine*DistributionType`, …) | 0 | 0–5 files |
| EXPLAIN text assertions | 0 (`TestRuleTester:110` is a TableScan; `AbstractDistributedEngineOnlyQueries` uses a `.*` regex) | 0 |
| Event listener and product tests | 0 | 0 |

## 4. Size and PR split

Production code is about 200 LOC of visitors plus about 250 LOC of stats rules, and about 50 LOC of wiring for the mode/binding. Tests add about 600–800 LOC.

1. **PR 1: wiring plus cheap visitors.** Calculator mode/binding (option 1), plus visitors for nodes that already have stats: Sort, TopN, TopNRanking, DistinctLimit, Sample, DynamicFilterSource and partial/intermediate aggregation. About 200 LOC. This alone fixes GROUP BY and ORDER BY plans. Medium review, because the semantics of partial aggregation memory and the binding split need discussion.
2. **PR 2: Window family.** Stats rules plus visitors for Window, PatternRecognition, MarkDistinct and TopNRanking edge cases. About 150 LOC. Medium review.
3. **PR 3: row-multiplying nodes.** GroupId, Unnest, Offset and grouping-set aggregation stats plus visitors. About 120 LOC. Medium review, since the Unnest cardinality heuristic will get scrutiny.
4. **PR 4: DML nodes.** TableWriter, MergeWriter, TableFinish and the TableExecute/Delete/Update family. About 100 LOC. Easy review.
5. **(Later, optional)** A session property to let the CBO use the full costs, together with golden-file regeneration and benchmarks.

Use one commit per node, pairing the stats rule and cost visitor with tests.

## 5. Prior art and people

- **History.** The TODO dates from `349b554a29b` (2018, "Introduce CostCalculator"). Partial aggregation started returning unknown in `2484f47de8d` (2018, Findeisen): the formulas were valid only for SINGLE. There were no reverted or abandoned attempts and there is no `CODEOWNERS` file.
- **Overlap.** Open PR #31287 (wendigo), "Track statistics confidence and credit partitioned join inputs in cost", touches `PlanCostEstimate`, `CostComparator`, `CostCalculatorWithEstimatedExchanges`, `AggregationStatsRule` and `TestCostCalculator`. Coordinate before starting, and expect a rebase.
- **#29598** has no maintainer feedback yet.
- **Suggested reviewers.** Piotr Findeisen (designed `PlanCostEstimate`/`LocalCostEstimate` and the partial-aggregation decision), Mateusz Gajewski (most active recently, #31287), Martin Traverso (top committer in the package and on the join rules), Raunaq Morarka (stats and estimates) and Karol Sobczak (original visitors).

## 6. Open questions for maintainers

1. Is splitting the "plan-selection" and "reporting" cost modes acceptable, or would you rather the new costs feed the CBO immediately, behind a flag?
2. Partial aggregation memory: should it be bounded by `task.max-partial-aggregation-memory`, which means injecting `TaskManagerConfig` into the calculator, or reported unbounded?
3. Window, Sort and PatternRecognition memory: is the *in*-bytes upper bound acceptable, or should it be per-partition from NDV of the partition keys?
4. Unnest cardinality: a fixed multiplier, a session property, or unknown?
5. Should `maxMemory` stay a whole-plan sum, or should a per-stage or per-task peak be exposed for capacity use?
6. How should this work be sequenced with #31287?
