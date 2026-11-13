# Query log analyzer: specification

## Goal

Answer three questions from an existing Trino query log, with no change to Trino:

1. How many queries had an earlier run of the same shape (fingerprint)? How does this compare with queries that repeat as exact text?
2. How stable is the peak memory of one shape?
3. How many memory failures came from a shape that ran before?

A fourth question, if the log has input sizes: does a check of the input size make the prediction better?

## Input

One record for each completed query. The tool reads CSV or JSON Lines. A mapping names the fields, because each log has different names.

| Field | Necessary | Meaning |
|---|---|---|
| `query` | yes | The SQL text |
| `end_time` | yes | When the query ended (ISO 8601, or epoch seconds or milliseconds) |
| `peak_memory_bytes` | yes | Peak user memory of the query |
| `state` | yes | `FINISHED` or `FAILED` (other values are mapped with an option) |
| `error_code` | no | For example `EXCEEDED_LOCAL_MEMORY_LIMIT` |
| `catalog`, `schema` | no | Session catalog and schema, to complete table names |
| `query_id`, `user`, `source` | no | For the report only |
| `input_rows`, `input_bytes` | no | Physical input of the query |

The tool can also read the `query_history` table of the listener directly.

## Steps

1. **Normalize** the input to JSON Lines with the field names above.
2. **Fingerprint** each record with the same code as the listener and the predictor (`QueryFingerprint`). The record keeps `fingerprint`, or `supported: false`.
3. **Report.**

## Report

- **Volume:** records, supported records, time range, shapes.
- **Repeat rate:** the part of the queries that had an earlier run of the same shape. The same number for exact text. Both numbers for all queries, for queries with a peak of 1 GB or more (the limit is an option), and for memory failures.
- **Memory spread of one shape:** for shapes with 3 or more finished runs, the ratio of the largest to the smallest peak. Median, p90 and maximum across shapes, and the 20 worst shapes. The same for shapes with a peak of 1 GB or more.
- **Replay of the predictor rule:** go through the log in time order. For each query, predict from the earlier runs with the rule of the predictor (largest peak of the last 10 finished runs. After a memory failure that is newer than the last finished run: largest known peak × 2). Report the hit rate, the ratio predicted to actual (median, p10, p90), the part predicted too low by more than 20%, and the part predicted too high by more than a factor of 2. All of this also for queries of 1 GB or more.
- **Memory failures:** how many, and how many had an earlier run of the same shape. For those, what the rule predicted.
- **Input size check (only with input sizes):** the same replay, but an earlier run counts only if its input bytes are within 10% of the input bytes of this query (the limit is an option). Report the hit rate and the ratio again, next to the numbers without the check. Note: the real input size is not known before a query runs, so this shows the best case.
- **What was not possible:** records that the parser did not accept, with the 10 most frequent reasons.

The report is one Markdown file. The tool also writes the fingerprinted records, so that a person can look at one shape.

## Rules

- The tool does not send data to a network. It reads files and writes files.
- The SQL text can contain sensitive data. The report shows normalized SQL (literals replaced) only, cut to 300 characters.
