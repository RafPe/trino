#!/usr/bin/env python3
"""Step 1 of the query log analyzer: convert a query log to normalized JSON Lines.

Reads CSV or JSON Lines. A mapping names the source field of each normalized field:

  normalize.py --input log.csv --format csv --output normalized.jsonl \
      --map query=query_text --map peak_memory_bytes=peak_user_memory_bytes \
      --state-map SUCCEEDED=FINISHED

A normalized field without --map is read from the source field of the same name.
Each output record has an `id` (the input record number, from 1) and the normalized fields.
Times become UTC ISO 8601 with milliseconds (for example 2026-11-12T15:35:29.123Z), so a
text sort is also a time sort. Records without a necessary field are not written; the
counts and reasons go to the --stats file.
"""

import argparse
import csv
import json
import re
import sys
from datetime import datetime, timedelta, timezone

FIELDS = [
    "query", "end_time", "start_time", "peak_memory_bytes", "state", "error_code",
    "catalog", "schema", "query_id", "user", "source", "input_rows", "input_bytes",
    # only for a log that already has the fingerprint, for example the query_history table
    "fingerprint", "normalized_sql", "tables",
]
TEXT_FIELDS = ["error_code", "catalog", "schema", "query_id", "user", "source", "fingerprint", "normalized_sql", "tables"]
INTEGER_FIELDS = ["input_rows", "input_bytes"]
STATES = {"FINISHED", "FAILED"}

ISO_TIME = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?"
    r"\s*(Z|z|UTC|[+-]\d{2}(?::?\d{2})?)?$")
NUMBER = re.compile(r"^-?\d+(\.\d+)?([eE][+-]?\d+)?$")
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


class RecordError(Exception):
    """The record cannot be normalized. The message is the reason (it never holds SQL text)."""


def parse_time_ms(value, unit="auto"):
    """Returns epoch milliseconds for an ISO 8601 time or an epoch number, or None for an empty value.

    unit is "s", "ms" or "auto" (auto: a number above 10^11 is milliseconds, else seconds).
    An ISO time without a time zone is UTC.
    """
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValueError("not a time")
    if isinstance(value, (int, float)):
        number = float(value)
    else:
        text = str(value).strip()
        if not text:
            return None
        if NUMBER.match(text):
            number = float(text)
        else:
            return _parse_iso_ms(text)
    if unit == "auto":
        unit = "ms" if abs(number) >= 1e11 else "s"
    if unit == "s":
        return int(round(number * 1000))
    if unit == "ms":
        return int(round(number))
    raise ValueError(f"unknown time unit {unit}")


def _parse_iso_ms(text):
    match = ISO_TIME.match(text)
    if not match:
        raise ValueError("not an ISO 8601 time or epoch number")
    year, month, day, hour, minute, second, fraction, zone = match.groups()
    moment = datetime(int(year), int(month), int(day), int(hour), int(minute), int(second or 0), tzinfo=timezone.utc)
    millis = int((fraction or "0").ljust(3, "0")[:3])
    if zone and zone not in ("Z", "z", "UTC"):
        sign = 1 if zone[0] == "+" else -1
        digits = zone[1:].replace(":", "")
        offset = timedelta(hours=int(digits[:2]), minutes=int(digits[2:4] or 0))
        moment -= sign * offset
    return (moment - EPOCH) // timedelta(milliseconds=1) + millis


def format_time_ms(millis):
    moment = EPOCH + timedelta(milliseconds=millis)
    return f"{moment:%Y-%m-%dT%H:%M:%S}.{millis % 1000:03d}Z"


def parse_mapping(pairs, allowed=None, upper_keys=False):
    mapping = {}
    for pair in pairs or []:
        key, sep, value = pair.partition("=")
        if not sep or not key:
            raise SystemExit(f"bad mapping '{pair}': use name=value")
        key = key.strip()
        if upper_keys:
            key = key.upper()
        if allowed is not None and key not in allowed:
            raise SystemExit(f"unknown field '{key}'; known fields: {', '.join(allowed)}")
        mapping[key] = value.strip()
    return mapping


def _get(raw, source):
    """Reads a field; for JSON a dotted name reads a nested object."""
    if source in raw:
        return raw[source]
    value = raw
    for part in source.split("."):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def _text(value):
    if value is None:
        return None
    if isinstance(value, (list, dict)):
        return json.dumps(value, sort_keys=True)
    text = str(value)
    return text if text.strip() and text.strip().upper() not in ("NULL", "\\N") else None


def _integer(value, name):
    text = _text(value)
    if text is None:
        return None
    try:
        return int(float(text)) if not isinstance(value, int) else value
    except ValueError:
        raise RecordError(f"{name} is not a number")


def normalize_record(raw, mapping, state_map, time_unit="auto"):
    """Returns the normalized record (without id), or raises RecordError."""
    def source(name):
        return _get(raw, mapping.get(name, name))

    record = {}
    query = _text(source("query"))
    fingerprint = _text(source("fingerprint"))
    if query is None and fingerprint is None:
        raise RecordError("no query text")
    if query is not None:
        record["query"] = query

    try:
        end_ms = parse_time_ms(source("end_time"), time_unit)
        start_ms = parse_time_ms(source("start_time"), time_unit)
    except (ValueError, OverflowError):
        raise RecordError("time not readable")
    if end_ms is None:
        raise RecordError("no end_time")
    record["end_time"] = format_time_ms(end_ms)
    if start_ms is not None:
        record["start_time"] = format_time_ms(start_ms)

    peak = _integer(source("peak_memory_bytes"), "peak_memory_bytes")
    if peak is None:
        raise RecordError("no peak_memory_bytes")
    record["peak_memory_bytes"] = peak

    state = _text(source("state"))
    if state is None:
        raise RecordError("no state")
    state = state.strip().upper()
    state = state_map.get(state, state)
    if state not in STATES:
        raise RecordError(f"state {state[:40]} is not FINISHED or FAILED (use --state-map)")
    record["state"] = state

    for name in TEXT_FIELDS:
        value = _text(source(name))
        if value is not None:
            record[name] = value.strip() if name != "normalized_sql" else value
    for name in INTEGER_FIELDS:
        value = _integer(source(name), name)
        if value is not None:
            record[name] = value
    return record


def read_records(path, file_format, delimiter=","):
    """Yields (raw record or None, reason) per input record."""
    if file_format == "csv":
        csv.field_size_limit(sys.maxsize)
        with open(path, newline="", encoding="utf-8", errors="replace") as f:
            for row in csv.DictReader(f, delimiter=delimiter):
                yield row, None
    elif file_format == "jsonl":
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    raw = json.loads(line)
                except ValueError:
                    yield None, "invalid JSON line"
                    continue
                if not isinstance(raw, dict):
                    yield None, "JSON line is not an object"
                    continue
                yield raw, None
    else:
        raise SystemExit(f"unknown format {file_format}")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    p.add_argument("--format", required=True, choices=["csv", "jsonl"])
    p.add_argument("--delimiter", default=",", help="CSV delimiter; 'tab' for a tab")
    p.add_argument("--map", action="append", default=[], metavar="FIELD=SOURCE",
                   help=f"source field of a normalized field ({', '.join(FIELDS)})")
    p.add_argument("--state-map", action="append", default=[], metavar="VALUE=STATE",
                   help="map a source state value to FINISHED or FAILED, for example SUCCEEDED=FINISHED")
    p.add_argument("--time-unit", default="auto", choices=["auto", "s", "ms"], help="unit of epoch numbers")
    p.add_argument("--output", required=True)
    p.add_argument("--stats", help="write counts and reject reasons to this JSON file")
    args = p.parse_args(argv)

    mapping = parse_mapping(args.map, FIELDS)
    state_map = parse_mapping(args.state_map, upper_keys=True)
    for value in state_map.values():
        if value not in STATES:
            raise SystemExit(f"--state-map target must be FINISHED or FAILED, not {value}")
    delimiter = "\t" if args.delimiter == "tab" else args.delimiter

    count = written = 0
    rejected = {}
    with open(args.output, "w", encoding="utf-8") as out:
        for raw, reason in read_records(args.input, args.format, delimiter):
            count += 1
            if raw is not None:
                try:
                    record = normalize_record(raw, mapping, state_map, args.time_unit)
                except RecordError as e:
                    reason = str(e)
            if reason is not None:
                rejected[reason] = rejected.get(reason, 0) + 1
                continue
            out.write(json.dumps({"id": count, **record}, ensure_ascii=False) + "\n")
            written += 1
    stats = {"input_records": count, "written": written, "rejected": rejected}
    if args.stats:
        with open(args.stats, "w") as f:
            json.dump(stats, f, indent=2, sort_keys=True)
    print(f"normalize: {count} records read, {written} written, {count - written} rejected", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
