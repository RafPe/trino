# Memory-history PoC: implementation plan and contracts

Branch: `RafPe/memory-history-poc` on https://github.com/RafPe/trino (base: trinodb/trino `8ae1f91f853`).

## Goal (the only goal)

Show, with a setup a person can run, that for **repeated queries** we can:

1. record each completed query's fingerprint and peak memory (**collect**);
2. predict the peak memory of a new query with a known fingerprint (**predict**);
3. use the prediction to get more workers *before* the query runs (**act**);
4. report how good the predictions are (**measure**).

No Trino core changes. Everything is a plugin, a small service, scripts and config.

## Out of scope (do not build)

- Changes under `core/` (except registering new modules in the root `pom.xml`).
- Plan-based fingerprints, per-node peak memory, hooks inside Trino.
- Machine-learning models. The predictor is a lookup with simple arithmetic.
- Trino Gateway. Authentication, TLS, HA, persistence tuning, upstream docs.
- A transparent HTTP proxy. The gate is a client-side script.
- Any data source other than the built-in `tpcds` / `tpch` connectors.

If a task seems to need something from this list, stop and report it instead of building it.

## Architecture

```
client ──▶ gate.py ──(1) POST /v1/predict──────────▶ predictor ──reads──▶ PostgreSQL
              │       (2) POST /v1/reservations ───▶    │  ▲                  ▲
              │                                         │  └─ GET /v1/required-workers ◀── scaler (KEDA, or scaler.py for compose)
              │ (3) submit query with session props     │                     │ writes
              ▼     required_workers_count=N            │                     │
        Trino coordinator ── waits for N workers ── runs query ── QueryCompletedEvent ──▶ memory-history event listener
```

- Trino itself holds the query: the gate sets the existing session properties `required_workers_count` and `required_workers_max_wait_time`.
- Unknown fingerprint → no reservation, no wait, the query is submitted unchanged.

## Components and file layout

```
plugin/trino-memory-history/                 Java. Fingerprint + event listener + PostgreSQL store.
service/trino-memory-history-predictor/      Java. HTTP predictor service (depends on the plugin jar for the fingerprint).
poc/memory-history/
  PLAN.md                                    this file
  README.md                                  step-by-step setup and walkthrough for a human
  docker/                                    Dockerfile.trino, Dockerfile.predictor, build-images.sh
  compose/                                   docker-compose.yml, Trino etc/ files
  gate/                                      gate.py, scaler.py, requirements.txt
  workload/                                  queries/*.sql, workload.yaml, run_workload.py, report.py
  k8s/                                       kind + Helm + KEDA demo (only if the full-loop demo is confirmed)
  results/                                   generated reports (commit the final one)
```

Java package root: `io.trino.plugin.memoryhistory` (plugin) and `io.trino.memoryhistory.predictor` (service).

## Contract 1: fingerprint

`io.trino.plugin.memoryhistory.QueryFingerprint`

```java
public record QueryFingerprint(String hash, String normalizedSql, List<String> tables)
{
    public static Optional<QueryFingerprint> compute(String sql, Optional<String> catalog, Optional<String> schema);
}
```

Rules (both the plugin and the predictor call this same method):

1. Parse with `io.trino.sql.parser.SqlParser`. On `ParsingException` return `Optional.empty()`. Never throw.
2. `EXECUTE IMMEDIATE '<sql>'`: unwrap and fingerprint the inner SQL.
3. Supported statements: `Query`, `Insert`, `CreateTableAsSelect`. Everything else → `Optional.empty()`.
4. Tables: all `Table` nodes, minus single-part names that match a CTE name. Unqualified names are completed with the session catalog/schema (use `?` when unknown). Lower-case, `catalog.schema.table`, distinct, sorted.
5. Normalized SQL: tokenize the SQL with Trino's lexer (`SqlBaseLexer`). Drop whitespace and comments. Replace string, binary, integer, decimal and double literals with `?`. Lower-case every other token. Join with single spaces. Keep the integer after `LIMIT`. Collapse `in ( ? , ? , … )` to `in ( ? )`.
6. `hash` = lower-case hex SHA-256 of `normalizedSql + "\n" + String.join(",", tables)`.

Required unit tests: literals differ → same hash; different table/schema → different hash; whitespace/case/comments ignored; IN-list length ignored; LIMIT value kept; CTE name not treated as a table; unsupported statement and invalid SQL → empty.

## Contract 2: history table (PostgreSQL)

The listener creates it at startup (`CREATE TABLE IF NOT EXISTS`). The predictor only reads it.

```sql
CREATE TABLE IF NOT EXISTS query_history (
    query_id                     text PRIMARY KEY,
    fingerprint                  text NOT NULL,
    normalized_sql               text NOT NULL,
    tables                       text NOT NULL,          -- comma-separated, sorted
    query_state                  text NOT NULL,          -- FINISHED | FAILED
    error_code                   text,                   -- e.g. EXCEEDED_LOCAL_MEMORY_LIMIT
    error_type                   text,                   -- e.g. INSUFFICIENT_RESOURCES
    peak_user_memory_bytes       bigint NOT NULL,
    peak_task_user_memory_bytes  bigint NOT NULL,
    peak_task_total_memory_bytes bigint NOT NULL,
    physical_input_rows          bigint NOT NULL,
    physical_input_bytes         bigint NOT NULL,
    wall_time_ms                 bigint NOT NULL,
    cpu_time_ms                  bigint NOT NULL,
    queued_time_ms               bigint NOT NULL,
    user_name                    text,
    source                       text,
    client_tags                  text,                   -- comma-separated, sorted
    session_properties           text,                   -- JSON object
    create_time                  timestamptz NOT NULL,
    end_time                     timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS query_history_fingerprint_idx ON query_history (fingerprint, end_time DESC);
```

Listener rules:

- Event-listener name: `memory-history`. Config: `memory-history.db.url` (required), `memory-history.db.user`, `memory-history.db.password`.
- SQL to fingerprint: `metadata.getPreparedQuery().orElse(metadata.getQuery())`; catalog/schema from `QueryContext`.
- Only `queryCompleted`. Skip queries with no fingerprint. Never throw from the listener: log and continue.
- Do the insert off the calling thread (single-thread executor with a bounded queue; drop and count when full).
- Runs on the coordinator only.

## Contract 3: predictor HTTP API (JSON, port 8090)

Config by environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PER_WORKER_MEMORY_BYTES` (the cluster's `query.max-memory-per-node`), `MIN_WORKERS` (default 2), `MAX_WORKERS` (default 6), `SAFETY_FACTOR` (default 1.2), `FAILURE_GROWTH_FACTOR` (default 2.0), `HISTORY_RUNS` (default 10), `PORT` (default 8090).

**`POST /v1/predict`** — request `{"sql": "...", "catalog": "tpcds", "schema": "sf10"}`

```json
{
  "supported": true,
  "fingerprint": "ab12…",
  "known": true,
  "runs": 5,
  "successfulRuns": 4,
  "memoryFailures": 1,
  "predictedPeakUserMemoryBytes": 3400000000,
  "predictedPeakTaskUserMemoryBytes": 900000000,
  "requiredWorkers": 4,
  "basis": "max_of_last_successful_runs"
}
```

Prediction rule, over the last `HISTORY_RUNS` rows for the fingerprint (newest first):

- `S` = rows with `query_state = 'FINISHED'`; `F` = rows whose `error_code` is one of `EXCEEDED_LOCAL_MEMORY_LIMIT`, `EXCEEDED_GLOBAL_MEMORY_LIMIT`, `CLUSTER_OUT_OF_MEMORY`. Other failures are ignored.
- No rows in `S` or `F` → `known: false`, prediction fields `null`.
- `maxS` = max peak over `S`; `maxF` = max peak over `F` (a failed run's peak is a *lower bound*).
- If `S` is empty, or `maxF > maxS`: predicted = `maxF × FAILURE_GROWTH_FACTOR`, basis `"memory_failure_growth"`.
- Otherwise predicted = `maxS`, basis `"max_of_last_successful_runs"`.
- The same rule applies to `peak_task_user_memory_bytes`.
- `requiredWorkers = clamp(ceil(predictedPeakUserMemoryBytes × SAFETY_FACTOR / PER_WORKER_MEMORY_BYTES), MIN_WORKERS, MAX_WORKERS)`.
- Unsupported or unparsable SQL → `{"supported": false, "known": false}`.

**`POST /v1/reservations`** — `{"id": "<uuid>", "predictedBytes": 3400000000, "ttlSeconds": 900}` → `201`. Held in memory.
**`DELETE /v1/reservations/{id}`** → `204` (also when the id is unknown).
**`GET /v1/required-workers`** → `{"workers": 4, "reservations": 1}` where
`workers = clamp(ceil(sum(predictedBytes of live reservations) × SAFETY_FACTOR / PER_WORKER_MEMORY_BYTES), MIN_WORKERS, MAX_WORKERS)`; with no live reservation it is `MIN_WORKERS`. Expired reservations are removed.
**`GET /v1/health`** → `200 {"status": "ok"}`.

Implementation: JDK `com.sun.net.httpserver.HttpServer`, plain JDBC, Jackson for JSON. No framework.

## Contract 4: gate and scaler (Python 3, `trino` client package)

`gate.py --server http://localhost:8080 --predictor http://localhost:8090 --catalog tpcds --schema sf10 [--user poc] [--no-gate] [--tag k=v ...] (-f query.sql | -e "sql")`

1. `--no-gate`: submit as is.
2. Otherwise call `/v1/predict`. If `known`: create a reservation and submit with session properties `required_workers_count=<N>` and `required_workers_max_wait_time='10m'`. (Changed during integration: the reservation is made even when the cluster already has enough workers, so the scaler does not remove workers under a running query.)
3. Always delete the reservation when the query ends (success or failure).
4. Print one JSON line: query id, state, error code, prediction, workers before/after, wait time.

`gate.py` is also importable (`run_query(...)`) so the workload driver reuses it.

`scaler.py` (compose only; stands in for KEDA): every 5 s read `/v1/required-workers` and run `docker compose up -d --no-recreate --scale worker=<N>`. It scales up at once and scales down only after the value has been lower for 120 s.

## Contract 5: cluster shape (compose and k8s use the same numbers)

- Image `trino-memory-history:poc` = `FROM trinodb/trino:484` + the plugin unpacked to `/usr/lib/trino/plugin/memory-history/`.
- Coordinator: 4 GB heap, `node-scheduler.include-coordinator=false`, `etc/event-listener.properties` with the listener.
- Workers: 4 GB heap each, `-Xms` = `-Xmx`. Start with 2, scale to at most 6.
- `query.max-memory-per-node=1GB`, `query.max-memory=6GB`. Then `PER_WORKER_MEMORY_BYTES=1073741824`.
- Catalogs: `tpcds` (`tpcds.splits-per-node=4`) and `tpch`.
- Workload session properties: `join_distribution_type=PARTITIONED`, `task_concurrency=4`.
- These numbers are a starting point. Calibrate them so that at least one heavy query **fails with a memory error on 2 workers and succeeds on 4–6**. Record the final numbers in `README.md`.

## Contract 6: workload and report

- `workload/queries/`: about 12–16 templates from `testing/trino-benchmark-queries/src/main/resources/sql/trino/tpcds` and `.../tpch`, with `{{name}}` placeholders for the literal(s) to vary; `workload.yaml` lists each template, its schema(s) and 3 literal variants. Spread: small (MBs), medium (100s of MB), heavy (GBs). At least 3 heavy ones.
- `run_workload.py --pass learn|predict`: runs every (template, variant) through `gate.run_query`, tags each query with client tags (`poc_pass`, `poc_template`, `poc_variant`), and appends one JSON line per query to `results/runs.jsonl` (including the prediction made *before* the run).
- `report.py`: writes `results/REPORT.md` with: hit rate; predicted vs actual peak (ratio distribution: median, p90, worst) for hits; spread of actual peak within one fingerprint across literal variants; table of heavy queries (failed ungated on 2 workers? succeeded gated? workers before/after, wait time); cold-start behavior; list of known limits.
- Total run time target: under 30 minutes.

## Milestones and checkpoints (coordinator runs these)

| # | Checkpoint | Proof |
|---|---|---|
| M1 | Fingerprint + listener + store build and pass tests | `mvnd … test` output |
| M2 | Plugin loads in `trinodb/trino:484`; a query produces a row in `query_history` | `SELECT` from PostgreSQL |
| M3 | Predictor answers `/v1/predict` for a seen and an unseen query | `curl` output |
| M4 | Compose cluster calibrated: a heavy query fails on 2 workers, passes on 4–6 | query ids + error codes |
| M5 | Gate + scaler: the same heavy query, gated, scales workers up and passes | `gate.py` JSON line |
| M6 | Full workload run and `REPORT.md` | report file |
| M7 | (if confirmed) kind + Helm + KEDA demo of M5 | `kubectl get pods -w` log |

## Rules for every agent

- Read this file first. Build only what your task and these contracts say. Do not change a contract; if one is wrong or blocks you, stop and report.
- Work only in your own directories. Commit small, push to remote `fork`. Never push to `origin`. Never open PRs, issues or comments.
- Never run `mvn install` / `deploy` (the `~/.m2` cache is shared). Use `-Dair.check.skip-all=true` for speed; still keep license headers and run `airstyle:format` on Java you touch.
- End every commit message with: `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`
- Disk is limited (about 25 GB free). Do not pull large images or generate data beyond what the contracts need. Clean up containers you start.
- Final report: what was built, commits, exact commands to verify, what was verified by running it and what was not, and open problems.
