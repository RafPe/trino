# Query log analyzer

This tool reads an existing Trino query log and estimates, with no change to Trino, how well the
memory-history predictor would work on that cluster. [SPEC.md](SPEC.md) is the contract.

It answers:

1. How many queries had an earlier run of the same shape (fingerprint), and how many had an earlier run of the exact same text.
2. How stable the peak memory of one shape is.
3. How many memory failures came from a shape that ran before.
4. If the log has input bytes: whether a check of the input size makes the prediction better.

The tool reads files and writes files. It does not send data to a network. The fingerprint step
runs in Docker with `--network none`.

## Run it

You need Docker, the image `trino-memory-history-predictor:poc` (see `../docker/build-images.sh`)
and Python 3.8 or later. No Python packages.

```bash
./analyze.sh --input query_log.csv --format csv \
    --map query=query_text --map peak_memory_bytes=peak_user_memory_bytes --map end_time=ended \
    --out out/
```

The three steps:

1. `normalize.py`: CSV or JSON Lines → `out/normalized.jsonl`, with the field names of SPEC.md.
2. Fingerprint: `io.trino.memoryhistory.predictor.FingerprintTool` in the predictor jar → `out/fingerprints.jsonl`.
   It calls `QueryFingerprint.compute`, the same code as the listener and the predictor.
   With `--jar <path to trino-memory-history-predictor-*-executable.jar>`, a local `java` (JDK 25) runs it instead of Docker.
3. `report.py` → `out/REPORT.md`, `out/shapes.csv` and `out/fingerprinted.jsonl`.

Options of `analyze.sh`:

| option | meaning |
|---|---|
| `--map field=source` | the source column of a field. A field without `--map` is read from a column of the same name. In JSON Lines, `a.b` reads a nested field. |
| `--state-map VALUE=FINISHED` | map a state value of the log to `FINISHED` or `FAILED`. Other values are rejected. |
| `--delimiter <c>` | CSV delimiter (`tab` for a tab) |
| `--time-unit auto\|s\|ms` | unit of epoch numbers. `auto`: above 10^11 is milliseconds. |
| `--large-bytes <n>` | peak of a "large" query, default 1 GiB (1073741824) |
| `--input-tolerance <f>` | limit of the input size check, default 0.10 |
| `--jar <path>`, `--image <name>` | how the fingerprint step runs |

Fields (SPEC.md, plus `start_time`):

| field | necessary | note |
|---|---|---|
| `query` | yes, unless `fingerprint` is mapped | SQL text |
| `end_time` | yes | ISO 8601 (a time without zone is UTC) or epoch seconds or milliseconds |
| `start_time` | no | When it is there, a query sees only runs that ended before it started. Without it, the end time is used. |
| `peak_memory_bytes` | yes | peak user memory |
| `state` | yes | `FINISHED` or `FAILED` |
| `error_code` | no | memory errors: `EXCEEDED_LOCAL_MEMORY_LIMIT`, `EXCEEDED_GLOBAL_MEMORY_LIMIT`, `CLUSTER_OUT_OF_MEMORY` |
| `catalog`, `schema` | no | to complete unqualified table names. Without them, the table is `?.?.name`. |
| `query_id`, `user`, `source` | no | kept in `fingerprinted.jsonl` |
| `input_rows`, `input_bytes` | no | `input_bytes` enables the input size check |
| `fingerprint`, `normalized_sql`, `tables` | no | for a log that already has a fingerprint. `--map fingerprint=...` skips the fingerprint step. |

## Export a log

### The PoC `query_history` table (PostgreSQL)

This table has no SQL text, only the fingerprint and the normalized SQL. The report then has no
exact-text repeat rate. Python has no PostgreSQL driver in its standard library, so export with `psql`:

```bash
psql "postgresql://USER@HOST:5432/DB" -c "\copy (SELECT query_id, fingerprint, normalized_sql, tables, query_state, error_code, peak_user_memory_bytes, physical_input_rows, physical_input_bytes, user_name, source, create_time, end_time FROM query_history) TO 'query_history.csv' CSV HEADER"

./analyze.sh --input query_history.csv --format csv --out out/ \
    --map fingerprint=fingerprint --map state=query_state --map peak_memory_bytes=peak_user_memory_bytes \
    --map input_rows=physical_input_rows --map input_bytes=physical_input_bytes \
    --map user=user_name --map start_time=create_time
```

### The MySQL event listener table `trino_queries`

Columns from `plugin/trino-mysql-event-listener/.../QueryDao.java`: `query`, `query_state`, `catalog`,
`schema`, `user`, `source`, `error_code`, `peak_memory_bytes` (the listener writes peak user memory here),
`physical_input_bytes`, `physical_input_rows`, `wall_time_millis`. The table has no start or end time.
The export takes the start time from the query ID (`YYYYMMDD_hhmmss_...`, UTC, to the second) and
adds `wall_time_millis` (the elapsed time from creation to end). The export writes JSON Lines, because
SQL text has line breaks:

```bash
mysql --batch --raw --skip-column-names -h HOST -u USER -p DB -e "
SELECT JSON_OBJECT(
  'query_id', query_id, 'query', query, 'state', query_state, 'error_code', error_code,
  'catalog', catalog, 'schema', \`schema\`, 'user', user, 'source', source,
  'peak_memory_bytes', peak_memory_bytes,
  'input_rows', physical_input_rows, 'input_bytes', physical_input_bytes,
  'start_time', DATE_FORMAT(STR_TO_DATE(LEFT(query_id, 15), '%Y%m%d_%H%i%s'), '%Y-%m-%dT%H:%i:%sZ'),
  'end_time', DATE_FORMAT(STR_TO_DATE(LEFT(query_id, 15), '%Y%m%d_%H%i%s')
                          + INTERVAL wall_time_millis * 1000 MICROSECOND, '%Y-%m-%dT%H:%i:%s.%fZ'))
FROM trino_queries" > trino_queries.jsonl

./analyze.sh --input trino_queries.jsonl --format jsonl --out out/
```

The field names of this export are the normalized names, so no `--map` is needed. I did not run this
export against a MySQL server.

### A generic CSV

Any CSV with a header works. Example: a log with columns `sql_text`, `finished_at` (epoch milliseconds),
`peak_user_memory`, `status` (`SUCCEEDED` or `FAILED`) and `default_catalog`:

```bash
./analyze.sh --input log.csv --format csv --out out/ \
    --map query=sql_text --map end_time=finished_at --map peak_memory_bytes=peak_user_memory \
    --map state=status --state-map SUCCEEDED=FINISHED --map catalog=default_catalog
```

## Output

| file | content |
|---|---|
| `REPORT.md` | the report (normalized SQL only, cut to 300 characters) |
| `shapes.csv` | one row per shape: runs, finished runs, memory failures, min, median and max peak of the finished runs, max/min, first and last seen, tables, normalized SQL (300 characters) |
| `fingerprinted.jsonl` | the normalized records without the SQL text, with `supported`, `fingerprint` or `error`. Use it to find all runs of one shape. |
| `normalized.jsonl`, `fingerprints.jsonl` | the results of steps 1 and 2. `normalized.jsonl` holds the SQL text. |
| `normalize-stats.json` | records read, written, and the reasons for rejected records |

## How to read the report

- **Volume.** Records, records with a fingerprint, time range, number of shapes, shapes seen once.
- **Repeat rate.** For each query: did a run of the same shape end before this query started? The same for the
  exact text (byte for byte). For all queries, for large queries and for memory failures. The gap between the two
  columns is what the fingerprint adds over a cache by text. Only queries with a fingerprint count.
- **Memory spread of one shape.** For shapes with 3 or more finished runs: largest / smallest finished peak.
  A ratio near 1 means that the literals do not change the memory much. A large ratio means that one shape
  covers queries of very different size (for example a date range). Small queries often have large ratios
  (bytes to megabytes); they do not matter for capacity. Look at the "large" table first.
- **Replay of the predictor rule.** The log is replayed in time order. Each query gets the prediction that
  the predictor would have given at its start, from the runs that ended before. A hit is a query with a prediction.
  The ratio is predicted / actual for finished queries. "Too low" (ratio below 0.8) is the risk: the query gets
  fewer workers than it needs. "Too high" (above 2) costs workers.
- **Memory failures.** How many memory failures had an earlier run of the same shape, and whether the prediction
  was at least the peak at which the query failed. That peak is a lower bound of the real need.
- **Input size check.** The replay again, but only earlier runs with input bytes within 10% of this query count.
  This uses the real input bytes of the query, which are not known before it runs. It shows the best case of a
  check of the input size (for example from table statistics or the plan estimate), not a real check.
- **What was not possible.** Records that normalization rejected, and records without a fingerprint with the 10
  most frequent reasons.

The rule is the one of `PredictionRule.java` with `HISTORY_RUNS=10` and `FAILURE_GROWTH_FACTOR=2`:

- The window is the last 10 runs of the shape, of any state. A run that failed for another reason takes a place
  in the window but is ignored, as in `HistoryRepository`. SPEC.md says "the last 10 finished runs"; the
  replay follows the predictor code.
- Prediction: the largest peak of the finished runs in the window. If a memory failure is newer than the newest
  finished run (or there is no finished run): the largest of those peaks × 2. No finished run and no memory
  failure: no prediction.
- The input size check filters this same window of 10 runs.

## Limits

- The fingerprint is a text fingerprint. Two queries that differ only in literals have one shape, also when the
  literals change the data volume by 100 times. The same SQL on other tables or schemas is another shape.
- Without `catalog` and `schema` in the log, unqualified names become `?.?.name`. Queries of different schemas
  can then share a shape.
- The fingerprint library does not say why it gives no fingerprint. The reason in the report names the first
  keyword only. For SELECT, WITH, VALUES, TABLE, `(` and INSERT it is a parse error, because these statements are
  supported when they parse. For other keywords it is an unsupported statement or a parse error.
- The input size check is a best case (see above).
- The replay counts the peak user memory of the whole query. It does not model workers, memory per node, the
  safety factor or concurrent queries.
- When the log has no start time, a query can see a run that ended after the query really started.
- Speed on this machine: the fingerprint step does about 5,000 records per second in Docker; `report.py` reads
  about 17,000 records per second and used 140 MB of memory for 196,400 records.

## Sample run

`make_sample_log.py` writes a made-up log from the PoC workload (seeded, the same file each time). Its docstring
says what the log holds. [sample/REPORT-sample.md](sample/REPORT-sample.md) is the report on this log:

```bash
python3 make_sample_log.py --output sample/sample-log.csv
./analyze.sh --input sample/sample-log.csv --format csv --out /tmp/sample-out \
    --map query=query_text --map peak_memory_bytes=peak_user_memory_bytes \
    --map start_time=created --map end_time=ended
```

Numbers that I checked against the way the log is made (a separate script that groups by the `template`
column of the log, not by fingerprint):

1. 491 records = 39 day-0 runs + 13 templates × 5 runs × 6 days + 18 dashboard runs + 28 date-range runs
   + 10 ad hoc + 6 without fingerprint. 485 have a fingerprint. 24 shapes = 13 templates + the date-range query
   + 10 ad hoc queries; the dashboard has the shape of tpcds_q96 (51 runs = 3 + 30 + 18). 10 shapes are seen once.
2. Earlier run of the same shape: 461 = 485 − 24. Only the first run of each shape has no earlier run.
3. Memory failures: 12 = 9 heavy runs of day 0 + 3 later ones. 9 had an earlier run: the first day-0 run of each
   of the 3 heavy templates has none. 6 predictions come from a memory failure × 2 (the other 2 day-0 runs of each
   heavy template, which see only failures); 3 come from finished runs (the later failures).
4. The date-range shape has a max/min of 109.29. The generator makes 100-day peaks 100 × the 1-day peaks,
   each × 0.95 to 1.05, so the ratio is between 90.5 and 110.5.
5. The fingerprints of the 13 templates are the same as those that the listener stored in the lab
   (`../results/REPORT.md`), for example `ab8d4a96cbb8` for tpch_q06 and `38e26399cfb1` for tpch_q21.
6. Exact-text repeats: 108, which includes the 18 dashboard runs (same text as tpcds_q96 variant 0 on day 0).
   The rest are literal values that the generator drew twice.
7. Large queries, ratio above 2: 4 without the input check, 0 with it. These 4 are the first finished run after a
   memory failure (prediction = failed peak × 2). In the generator, a failed run has read only part of its
   input, so the input check removes the failed run from the window. This effect comes from the generator.

## Tests

```bash
python3 -m unittest            # in this directory
mvnd -B -pl service/trino-memory-history-predictor test -Dair.check.skip-all=true -Dtest=TestFingerprintTool
```
