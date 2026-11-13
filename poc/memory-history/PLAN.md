# Memory-history PoC: implementation plan and contracts

Branch: `RafPe/memory-history-poc` on https://github.com/RafPe/trino (base: trinodb/trino `8ae1f91f853`).

## Goal (the only goal)

Show, with a setup that a person can run, that for **repeated queries** it is possible to:

1. record the fingerprint and peak memory of each completed query (**collect**)
2. predict the peak memory of a new query with a known fingerprint (**predict**)
3. use the prediction to get more workers *before* the query runs (**act**)
4. report how good the predictions are (**measure**).

No Trino core changes. Everything is a plugin, a small service, scripts and config.

## Out of scope (do not build)

- Changes under `core/` (except the registration of new modules in the root `pom.xml`).
- Plan-based fingerprints, per-node peak memory, hooks inside Trino.
- Machine-learning models. The predictor is a lookup with simple arithmetic.
- Trino Gateway. Authentication, TLS, HA, persistence tuning, upstream docs.
- A transparent HTTP proxy. The gate is a client-side script.
- Any data source other than the built-in `tpcds` / `tpch` connectors.

If a change seems to need an item from this list, do not build it.

## Architecture

```
client ──▶ gate.py ──(1) POST /v1/predict──────────▶ predictor ──reads──▶ PostgreSQL
              │       (2) POST /v1/reservations ───▶    │  ▲                  ▲
              │                                         │  └─ GET /v1/required-workers ◀── scaler (KEDA, or scaler.py for compose)
              │ (3) submit query with session props     │                     │ writes
              ▼     required_workers_count=N            │                     │
        Trino coordinator ── waits for N workers ── runs query ── QueryCompletedEvent ──▶ memory-history event listener
```

- Trino itself holds the query. The gate sets the existing session properties `required_workers_count` and `required_workers_max_wait_time`.
- Unknown fingerprint → no reservation, no wait. The gate submits the query unchanged.

## Components and file layout

```
lib/trino-memory-history-fingerprint/        Java. Fingerprint only (plain jar, depends on trino-parser; no Guice, no SPI).
plugin/trino-memory-history/                 Java. Event listener + PostgreSQL store (uses the fingerprint library).
service/trino-memory-history-predictor/      Java. HTTP predictor service (uses the fingerprint library).
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

Java package roots: `io.trino.memoryhistory.fingerprint` (fingerprint library), `io.trino.plugin.memoryhistory` (plugin) and `io.trino.memoryhistory.predictor` (service).

## Contract 1: fingerprint

`io.trino.memoryhistory.fingerprint.QueryFingerprint` (module `lib/trino-memory-history-fingerprint`)

```java
public record QueryFingerprint(String hash, String normalizedSql, List<String> tables)
{
    public static Optional<QueryFingerprint> compute(String sql, Optional<String> catalog, Optional<String> schema);
}
```

Rules (the plugin and the predictor both call this same method):

1. Parse with `io.trino.sql.parser.SqlParser`. On `ParsingException`, return `Optional.empty()`. Never throw.
2. `EXECUTE IMMEDIATE '<sql>'`: unwrap the inner SQL and fingerprint it.
3. Supported statements: `Query`, `Insert`, `CreateTableAsSelect`. Everything else → `Optional.empty()`.
4. Tables: all `Table` nodes, minus single-part names that match a CTE name visible at that point.
   - A CTE is visible in the query body of its `WITH` (including subqueries).
   - A CTE is visible in the definitions of later CTEs of the same `WITH`.
   - A CTE is not visible in its own definition (except with `WITH RECURSIVE`).
   - A CTE is not visible in earlier CTEs, and not outside the query that has the `WITH`.
   - Thus in `WITH orders AS (SELECT * FROM orders) …` the inner `orders` is the table.
   - An `INSERT` target is always a table.
   - Complete unqualified names with the session catalog/schema. Use `?` when the catalog or schema is unknown.
   - Lower-case, `catalog.schema.table`, distinct, sorted.
5. Normalized SQL:
   - Tokenize the SQL with the Trino lexer (`SqlBaseLexer`).
   - Drop whitespace and comments.
   - Replace string, binary, integer, decimal and double literals with `?`.
   - Lower-case every other token.
   - Join the tokens with single spaces.
   - Keep the integer after `LIMIT`.
   - Collapse an IN list whose every element is a literal to `in ( ? )`.
   - A literal element is `[+|-] [type] ?`, or `interval [+|-] ? field [to field]`. Here `type` is a single-keyword typed-literal prefix (`date`, `time`, `timestamp`, `decimal`, `real`, `double`, `bigint`, `integer`, `int`, `smallint`, `tinyint`, `varchar`, `char`, `varbinary`, `json`, `uuid`, `ipaddress`).
   - Thus `in ( - ? , - ? )` and `in ( date ? )` become `in ( ? )`.
   - Do not collapse anything else (function arguments, `VALUES` rows, IN lists with a non-literal element).
6. `hash` = lower-case hex SHA-256 of `normalizedSql + "\n" + String.join(",", tables)`.

Required unit tests:

- Different literals → same hash.
- Different table/schema → different hash.
- Whitespace, case and comments are ignored.
- IN-list length is ignored.
- The LIMIT value is kept.
- A CTE name is not a table.
- An unsupported statement and invalid SQL → empty.

## Contract 2: history table (PostgreSQL)

The listener creates the table at startup (`CREATE TABLE IF NOT EXISTS`). The predictor only reads it.

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
- SQL to fingerprint: `metadata.getPreparedQuery().orElse(metadata.getQuery())`. Catalog/schema come from `QueryContext`.
- Only `queryCompleted`. Skip queries with no fingerprint. Never throw from the listener. Log the error and continue.
- The calling thread only copies the event fields and puts them in the queue.
- Parsing, fingerprinting and the insert run off the calling thread:
  - single-thread executor, queue of 1,000
  - when the queue is full, drop the record and count it
  - log the dropped count at most once per minute.
- Store at most 100,000 characters of `normalized_sql` and `session_properties`. Compute the hash on the full normalized SQL first.
- JDBC `connectTimeout`, `socketTimeout` and `loginTimeout` are 10 s. Parameters in `memory-history.db.url` take precedence.
- Create the table once. Retry the creation until it succeeds.
- When an insert fails because the table is missing (SQLSTATE `42P01`, e.g. after a replacement of PostgreSQL), create the table again and retry the insert once.
- Statement timeout 10 s.
- Runs on the coordinator only.

## Contract 3: predictor HTTP API (JSON, port 8090)

Config by environment variables:

- `DB_URL`, `DB_USER`, `DB_PASSWORD`
- `PER_WORKER_MEMORY_BYTES` (the `query.max-memory-per-node` of the cluster)
- `MIN_WORKERS` (default 2), `MAX_WORKERS` (default 6)
- `SAFETY_FACTOR` (service default 1.2. The PoC deployments set 1.5, see README.)
- `FAILURE_GROWTH_FACTOR` (default 2.0), `HISTORY_RUNS` (default 10), `PORT` (default 8090).

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

- `S` = rows with `query_state = 'FINISHED'`.
- `F` = rows whose `error_code` is one of `EXCEEDED_LOCAL_MEMORY_LIMIT`, `EXCEEDED_GLOBAL_MEMORY_LIMIT`, `CLUSTER_OUT_OF_MEMORY`. Ignore other failures.
- No rows in `S` or `F` → `known: false`, prediction fields `null`.
- `maxS` = max peak over `S`. The peak of a failed run is a *lower bound*.
- `F_recent` = rows in `F` with an `end_time` after the newest row in `S`. When `S` is empty, `F_recent` = `F`.
- If `S` is empty: predicted = `max peak over F × FAILURE_GROWTH_FACTOR`, basis `"memory_failure_growth"`.
- If `F_recent` is not empty (the query ran out of memory after its last success): predicted = `max(maxS, max peak over F_recent) × FAILURE_GROWTH_FACTOR`, basis `"memory_failure_growth"`.
- Otherwise predicted = `maxS`, basis `"max_of_last_successful_runs"`. Ignore memory failures that are older than the last success.
- `runs`, `successfulRuns` and `memoryFailures` count all rows in the window.
- The same rule applies to `peak_task_user_memory_bytes`.
- `requiredWorkers = clamp(ceil(predictedPeakUserMemoryBytes × SAFETY_FACTOR / PER_WORKER_MEMORY_BYTES), MIN_WORKERS, MAX_WORKERS)`.
- Unsupported or unparsable SQL → `{"supported": false, "known": false}`.

**`POST /v1/reservations`** — `{"id": "<uuid>", "predictedBytes": 3400000000, "ttlSeconds": 900}` → `201`. The predictor holds reservations in memory.
**`DELETE /v1/reservations/{id}`** → `204` (also when the id is unknown).
**`GET /v1/required-workers`** → `{"workers": 4, "reservations": 1}` where
`workers = clamp(ceil(sum(predictedBytes of live reservations) × SAFETY_FACTOR / PER_WORKER_MEMORY_BYTES), MIN_WORKERS, MAX_WORKERS)`. With no live reservation, `workers` is `MIN_WORKERS`. The predictor deletes expired reservations.
**`GET /v1/health`** → `200 {"status": "ok"}`.

Implementation: JDK `com.sun.net.httpserver.HttpServer`, plain JDBC, Jackson for JSON. No framework.

Added on 2026-11-12, after a review:

- Database errors return `503` (connect timeout 3 s, socket and statement timeout 10 s).
- At most 16 `/v1/predict` requests run at once. Extra requests get `503`.

## Contract 4: gate and scaler (Python 3, `trino` client package)

`gate.py --server http://localhost:8080 --predictor http://localhost:8090 --catalog tpcds --schema sf10 [--user poc] [--no-gate] [--tag k=v ...] (-f query.sql | -e "sql")`

1. With `--no-gate`: submit the query as is.
2. Otherwise call `/v1/predict`. If `known`: create a reservation and submit with session properties `required_workers_count=<N>` and `required_workers_max_wait_time='10m'`. (Changed on 2026-11-12, during integration: the gate makes the reservation also when the cluster already has enough workers. Thus the scaler does not remove workers under a running query.)
3. Always delete the reservation when the query ends (success or failure).
4. Print one JSON line: query id, state, error code, prediction, workers before/after, wait time.

`gate.py` is also importable (`run_query(...)`), so the workload driver uses it again.

`scaler.py` (compose only, stands in for KEDA):

- Every 5 s, read `/v1/required-workers` and run `docker compose up -d --no-recreate --scale worker=<N>`.
- Scale up at once.
- Scale down only after the value stays lower for 120 s.

## Contract 5: cluster shape (compose and k8s use the same numbers)

- Image `trino-memory-history:poc` = `FROM trinodb/trino:484` + the plugin unpacked to `/usr/lib/trino/plugin/memory-history/`.
- Coordinator: 4 GB heap, `node-scheduler.include-coordinator=false`, `etc/event-listener.properties` with the listener.
- Workers: 4 GB heap each, `-Xms` = `-Xmx`. Start with 2, scale to at most 6.
- `query.max-memory-per-node=1GB`, `query.max-memory=6GB`. Then `PER_WORKER_MEMORY_BYTES=1073741824`.
- Catalogs: `tpcds` (`tpcds.splits-per-node=4`) and `tpch`.
- Workload session properties: `join_distribution_type=PARTITIONED`, `task_concurrency=4`.
- These numbers are a starting point. Calibrate them so that at least one heavy query **fails with a memory error on 2 workers and succeeds on 4–6**. The final numbers are in `workload/CALIBRATION.md`.

## Contract 6: workload and report

- `workload/queries/`:
  - About 12–16 templates from `testing/trino-benchmark-queries/src/main/resources/sql/trino/tpcds` and `.../tpch`.
  - `{{name}}` placeholders mark the literal(s) to vary.
  - `workload.yaml` lists each template, its schema(s) and 3 literal variants.
  - Spread: small (MBs), medium (100s of MB), heavy (GBs). At least 3 heavy ones.
- `run_workload.py --pass learn|predict`:
  - Runs every (template, variant) through `gate.run_query`.
  - Tags each query with client tags (`poc_pass`, `poc_template`, `poc_variant`).
  - Appends one JSON line per query to `results/runs.jsonl`, including the prediction made *before* the run.
- `report.py` writes `results/REPORT.md` with:
  - hit rate
  - predicted vs actual peak (ratio distribution: median, p90, worst) for hits
  - spread of actual peak within one fingerprint across literal variants
  - table of heavy queries (failed ungated on 2 workers? succeeded gated? workers before/after, wait time)
  - cold-start behavior
  - list of known limits.
- Total run time target: under 30 minutes.

## Milestones

Each milestone proves one part of the loop. The last column names the evidence.

| # | What it proves | Evidence |
|---|---|---|
| M1 | The fingerprint library, the listener and the store build and pass their tests. | `mvnd … test` output |
| M2 | The plugin loads in `trinodb/trino:484`, and a query produces a row in `query_history`. | `SELECT` from PostgreSQL |
| M3 | The predictor answers `/v1/predict` for a seen and an unseen query. | `curl` output |
| M4 | The compose cluster is calibrated: a heavy query fails on 2 workers and passes on 4–6. | query ids + error codes |
| M5 | Gate + scaler: the same heavy query, gated, scales the workers up and passes. | `gate.py` JSON line |
| M6 | The full workload runs, and `REPORT.md` shows the results. | report file |
| M7 | (optional) The kind + Helm + KEDA setup repeats M5. | `kubectl get pods -w` log |

## Working rules

- Build only what these contracts say. If a contract is wrong, change this file first.
- Keep changes inside the PoC directories listed in "Components and file layout".
- Commit small. Push only to the remote `fork`. Never push to `origin`. Do not open PRs, issues or comments for this PoC.
- Do not run `mvn install` or `deploy` into a shared `~/.m2` cache.
- Use `-Dair.check.skip-all=true` for speed. Keep the license headers, and run `airstyle:format` on the Java files that you change.
- Disk is limited (about 25 GB free). Do not pull large images. Do not generate more data than the contracts need. Remove the containers that you start.
