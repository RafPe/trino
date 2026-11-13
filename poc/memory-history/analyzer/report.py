#!/usr/bin/env python3
"""Step 3 of the query log analyzer: write the report.

  report.py --records normalized.jsonl --fingerprints fingerprints.jsonl --out DIR

--records is the output of normalize.py. --fingerprints is the output of the fingerprint
step, one line per record line in the same order. Without --fingerprints, the records must
have a `fingerprint` field (for example a copy of the query_history table).

Writes DIR/REPORT.md, DIR/shapes.csv and DIR/fingerprinted.jsonl (the records without the
SQL text, with their fingerprint).

The replay of the predictor rule uses only runs that ended before a query started. When a
record has no start_time, its end_time is used, so it sees only runs that ended earlier.
The SQL text is not kept in memory: pass 1 writes the few numbers that the replay needs to a
temporary file with fixed-size rows; pass 2 reads them in time order.
"""

import argparse
import csv
import hashlib
import json
import math
import os
import statistics
import struct
import sys
import tempfile
from collections import Counter, deque

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from normalize import format_time_ms, parse_time_ms  # noqa: E402

MEMORY_ERROR_CODES = {"EXCEEDED_LOCAL_MEMORY_LIMIT", "EXCEEDED_GLOBAL_MEMORY_LIMIT", "CLUSTER_OUT_OF_MEMORY"}
BASIS_SUCCESSFUL_RUNS = "max_of_last_successful_runs"
BASIS_MEMORY_FAILURE_GROWTH = "memory_failure_growth"
SQL_LIMIT = 300
GIB = 1024 ** 3

# shape index, text digest, peak, input bytes (-1: unknown), flags
ROW = struct.Struct("<I16sqqB")
FINISHED = 1
MEMORY_FAILURE = 2
HAS_TEXT = 4


# ---------------------------------------------------------------- prediction rule

class Run:
    __slots__ = ("end_ms", "finished", "memory_failure", "peak", "input_bytes")

    def __init__(self, end_ms, finished, memory_failure, peak, input_bytes=None):
        self.end_ms = end_ms
        self.finished = finished
        self.memory_failure = memory_failure
        self.peak = peak
        self.input_bytes = input_bytes


def predict(runs, growth_factor=2.0):
    """The rule of PredictionRule.predict for peak user memory.

    Returns (predicted bytes or None, basis or None). `runs` is the window of the last
    HISTORY_RUNS runs of one fingerprint, in any order; it includes runs that failed for
    other reasons, which the rule ignores.
    """
    successful = [run for run in runs if run.finished]
    memory_failures = [run for run in runs if run.memory_failure]
    if not successful and not memory_failures:
        return None, None
    last_success = max((run.end_ms for run in successful), default=None)
    recent_failures = [run for run in memory_failures if last_success is None or run.end_ms > last_success]
    max_successful = max((run.peak for run in successful), default=0)
    if not recent_failures:
        return max_successful, BASIS_SUCCESSFUL_RUNS
    base = max(max_successful, max(run.peak for run in recent_failures))
    return int(math.ceil(base * growth_factor)), BASIS_MEMORY_FAILURE_GROWTH


def similar_input(runs, input_bytes, tolerance):
    """The runs whose input bytes are within `tolerance` (a fraction) of `input_bytes`."""
    return [run for run in runs
            if run.input_bytes is not None and abs(run.input_bytes - input_bytes) <= tolerance * input_bytes]


# ---------------------------------------------------------------- statistics

def percentile(values, fraction):
    """Nearest-rank percentile of a sorted list, or None for an empty list."""
    if not values:
        return None
    rank = max(1, math.ceil(fraction * len(values)))
    return values[rank - 1]


class Accuracy:
    """Hit rate and predicted/actual ratio of one group of queries."""

    def __init__(self):
        self.queries = 0
        self.hits = 0
        self.ratios = []
        self.zero_peak = 0

    def add(self, predicted, actual_peak, finished):
        self.queries += 1
        if predicted is None:
            return
        self.hits += 1
        if not finished:
            return
        if actual_peak <= 0:
            self.zero_peak += 1
            return
        self.ratios.append(predicted / actual_peak)

    def summary(self):
        ratios = sorted(self.ratios)
        return {
            "queries": self.queries,
            "hits": self.hits,
            "rated": len(ratios),
            "median": statistics.median(ratios) if ratios else None,
            "p10": percentile(ratios, 0.10),
            "p90": percentile(ratios, 0.90),
            "too_low": sum(1 for r in ratios if r < 0.8),
            "too_high": sum(1 for r in ratios if r > 2.0),
        }


class Repeats:
    def __init__(self):
        self.queries = 0
        self.shape = 0
        self.text = 0

    def add(self, shape_repeat, text_repeat):
        self.queries += 1
        self.shape += shape_repeat
        self.text += text_repeat


class Shape:
    __slots__ = ("fingerprint", "normalized_sql", "tables", "runs", "finished_peaks", "memory_failures", "first", "last")

    def __init__(self, fingerprint, normalized_sql, tables):
        self.fingerprint = fingerprint
        self.normalized_sql = (normalized_sql or "")[:SQL_LIMIT]
        self.tables = tables
        self.runs = 0
        self.finished_peaks = []
        self.memory_failures = 0
        self.first = None
        self.last = None

    def ratio(self):
        """Largest / smallest finished peak, None with fewer than 3 finished runs, inf when the smallest is 0."""
        if len(self.finished_peaks) < 3:
            return None
        low, high = min(self.finished_peaks), max(self.finished_peaks)
        return high / low if low > 0 else math.inf


# ---------------------------------------------------------------- pass 1: read records

def _lines(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            yield line


def read_and_index(records_path, fingerprints_path, out_dir, replay_file):
    """Pass 1. Writes fingerprinted.jsonl and the replay rows; returns the aggregates."""
    shapes = []
    shape_index = {}
    times = []  # (start_ms, end_ms) per supported record, in replay-file row order
    volume = Counter()
    reasons = Counter()
    unsupported_memory_failures = 0
    first_end = last_end = None
    has_text = has_input_bytes = False

    fingerprint_lines = _lines(fingerprints_path) if fingerprints_path else None
    with open(os.path.join(out_dir, "fingerprinted.jsonl"), "w", encoding="utf-8") as out:
        for line in _lines(records_path):
            if not line.strip():
                continue
            record = json.loads(line)
            if fingerprint_lines is not None:
                try:
                    result = json.loads(next(fingerprint_lines))
                except StopIteration:
                    raise SystemExit("the fingerprint file has fewer lines than the record file")
                if result.get("id") != record.get("id"):
                    raise SystemExit(f"fingerprint line for id {result.get('id')} does not match record id {record.get('id')}")
                supported = bool(result.get("supported"))
                fingerprint = result.get("fingerprint")
                normalized_sql = result.get("normalizedSql")
                tables = ",".join(result.get("tables") or [])
                error = result.get("error")
            else:
                fingerprint = record.get("fingerprint")
                supported = bool(fingerprint)
                normalized_sql = record.get("normalized_sql")
                tables = record.get("tables") or ""
                error = None if supported else "no fingerprint in the record"

            query = record.pop("query", None)
            end_ms = parse_time_ms(record["end_time"])
            start_ms = parse_time_ms(record["start_time"]) if "start_time" in record else end_ms
            start_ms = min(start_ms, end_ms)
            finished = record["state"] == "FINISHED"
            memory_failure = not finished and record.get("error_code") in MEMORY_ERROR_CODES
            peak = int(record["peak_memory_bytes"])
            input_bytes = record.get("input_bytes")

            volume["records"] += 1
            first_end = end_ms if first_end is None else min(first_end, end_ms)
            last_end = end_ms if last_end is None else max(last_end, end_ms)

            out_record = dict(record, supported=supported)
            if supported:
                out_record["fingerprint"] = fingerprint
            else:
                out_record["error"] = error
            out.write(json.dumps(out_record, ensure_ascii=False) + "\n")

            if not supported:
                reasons[error or "unknown"] += 1
                unsupported_memory_failures += memory_failure
                continue
            volume["supported"] += 1

            index = shape_index.get(fingerprint)
            if index is None:
                index = shape_index[fingerprint] = len(shapes)
                shapes.append(Shape(fingerprint, normalized_sql, tables))
            shape = shapes[index]
            shape.runs += 1
            shape.memory_failures += memory_failure
            if finished:
                shape.finished_peaks.append(peak)
            shape.first = end_ms if shape.first is None else min(shape.first, end_ms)
            shape.last = end_ms if shape.last is None else max(shape.last, end_ms)

            digest = hashlib.blake2b(query.encode("utf-8"), digest_size=16).digest() if query is not None else bytes(16)
            has_text |= query is not None
            has_input_bytes |= input_bytes is not None
            flags = (FINISHED if finished else 0) | (MEMORY_FAILURE if memory_failure else 0) | (HAS_TEXT if query is not None else 0)
            replay_file.write(ROW.pack(index, digest, peak, -1 if input_bytes is None else int(input_bytes), flags))
            times.append((start_ms, end_ms))

    if fingerprint_lines is not None and any(line.strip() for line in fingerprint_lines):
        raise SystemExit("the fingerprint file has more lines than the record file")
    return {
        "shapes": shapes,
        "times": times,
        "volume": volume,
        "reasons": reasons,
        "unsupported_memory_failures": unsupported_memory_failures,
        "first_end": first_end,
        "last_end": last_end,
        "has_text": has_text,
        "has_input_bytes": has_input_bytes,
    }


# ---------------------------------------------------------------- pass 2: replay

def replay_events(times):
    """Event order of the replay: (time, kind, row). Kind 0 = predict at start, 1 = add to history at end.

    At the same time, predictions come first: a query never sees its own run, nor a run that ended
    at or after its start.
    """
    events = [(start, 0, row) for row, (start, _) in enumerate(times)]
    events.extend((end, 1, row) for row, (_, end) in enumerate(times))
    events.sort()
    return events


def replay(rows, times, large_bytes, history_runs=10, growth_factor=2.0, input_tolerance=0.10):
    """Pass 2. `rows(row)` returns the unpacked replay row (shape, digest, peak, input_bytes, flags)."""
    history = {}
    texts = set()
    repeats = {"all": Repeats(), "large": Repeats(), "memory_failures": Repeats()}
    accuracy = {"all": Accuracy(), "large": Accuracy()}
    input_check = {"all": (Accuracy(), Accuracy()), "large": (Accuracy(), Accuracy())}
    failures = {"total": 0, "earlier_shape": 0, "predicted": 0, "at_least_failed_peak": 0, "ratios": [], "basis": Counter()}
    bases = Counter()

    for time_ms, kind, row in replay_events(times):
        shape, digest, peak, input_bytes, flags = rows(row)
        finished = bool(flags & FINISHED)
        memory_failure = bool(flags & MEMORY_FAILURE)
        if kind == 1:
            window = history.get(shape)
            if window is None:
                window = history[shape] = deque(maxlen=history_runs)
            window.append(Run(times[row][1], finished, memory_failure, peak, None if input_bytes < 0 else input_bytes))
            if flags & HAS_TEXT:
                texts.add(digest)
            continue

        window = history.get(shape, ())
        shape_repeat = len(window) > 0
        text_repeat = bool(flags & HAS_TEXT) and digest in texts
        large = peak >= large_bytes
        predicted, basis = predict(window, growth_factor)
        if basis:
            bases[basis] += 1

        groups = ["all"] + (["large"] if large else [])
        for group in groups:
            repeats[group].add(shape_repeat, text_repeat)
            accuracy[group].add(predicted, peak, finished)
        if memory_failure:
            repeats["memory_failures"].add(shape_repeat, text_repeat)
            failures["total"] += 1
            failures["earlier_shape"] += shape_repeat
            if predicted is not None:
                failures["predicted"] += 1
                failures["basis"][basis] += 1
                failures["at_least_failed_peak"] += predicted >= peak
                if peak > 0:
                    failures["ratios"].append(predicted / peak)
        if input_bytes >= 0:
            checked, _ = predict(similar_input(window, input_bytes, input_tolerance), growth_factor)
            for group in groups:
                input_check[group][0].add(predicted, peak, finished)
                input_check[group][1].add(checked, peak, finished)

    return {
        "repeats": repeats,
        "accuracy": accuracy,
        "input_check": input_check,
        "failures": failures,
        "bases": bases,
    }


# ---------------------------------------------------------------- output

def human_bytes(value):
    if value is None:
        return "-"
    for unit, size in (("GiB", GIB), ("MiB", 1024 ** 2), ("KiB", 1024)):
        if abs(value) >= size:
            return f"{value / size:.2f} {unit}"
    return f"{value} B"


def pct(part, whole):
    return f"{100 * part / whole:.1f}%" if whole else "-"


def ratio_text(value):
    if value is None:
        return "-"
    if math.isinf(value):
        return "inf"
    return f"{value:.2f}"


def iso(millis):
    return format_time_ms(millis) if millis is not None else "-"


def cell(text):
    return (text or "").replace("|", "\\|").replace("\n", " ")


def write_shapes_csv(path, shapes):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["fingerprint", "runs", "finished_runs", "memory_failures", "min_peak_bytes",
                         "median_peak_bytes", "max_peak_bytes", "max_min_ratio", "first_seen", "last_seen",
                         "tables", "normalized_sql"])
        for shape in sorted(shapes, key=lambda s: (-s.runs, s.fingerprint)):
            peaks = shape.finished_peaks
            ratio = None
            if peaks:
                low, high = min(peaks), max(peaks)
                ratio = high / low if low > 0 else math.inf
            writer.writerow([
                shape.fingerprint, shape.runs, len(peaks), shape.memory_failures,
                min(peaks) if peaks else "", int(statistics.median(peaks)) if peaks else "", max(peaks) if peaks else "",
                ratio_text(ratio) if peaks else "", iso(shape.first), iso(shape.last), shape.tables, shape.normalized_sql])


def render_report(data, result, options):
    shapes = data["shapes"]
    volume = data["volume"]
    large_label = f"peak ≥ {human_bytes(options.large_bytes)}"
    lines = []
    add = lines.append

    add("# Query log analysis")
    add("")
    add(f"Input: `{options.input_name or os.path.basename(options.records)}`. Rule settings: last {options.history_runs} runs per shape, "
        f"failure growth factor {options.growth_factor:g}. Large queries: {large_label}.")
    add("")

    # volume
    add("## Volume")
    add("")
    stats = options.normalize_stats or {}
    if stats:
        add(f"- Records read: {stats.get('input_records', '-')}, rejected by normalization: "
            f"{stats.get('input_records', 0) - stats.get('written', 0)}")
    add(f"- Records: {volume['records']}")
    add(f"- Records with a fingerprint: {volume['supported']} ({pct(volume['supported'], volume['records'])})")
    add(f"- Time range (end time): {iso(data['first_end'])} to {iso(data['last_end'])}")
    add(f"- Shapes (fingerprints): {len(shapes)}")
    once = sum(1 for s in shapes if s.runs == 1)
    add(f"- Shapes seen once: {once}")
    add("")

    # repeat rate
    add("## Repeat rate")
    add("")
    add("Part of the queries that had an earlier run: of the same shape, and of the exact same text. "
        "An earlier run is a run that ended before the query started. Only records with a fingerprint count.")
    add("")
    add("| queries | n | earlier run of the same shape | earlier run of the same text |")
    add("|---|---|---|---|")
    labels = {"all": "all", "large": large_label, "memory_failures": "memory failures"}
    for group, label in labels.items():
        r = result["repeats"][group]
        text = f"{r.text} ({pct(r.text, r.queries)})" if data["has_text"] else "no SQL text in the log"
        add(f"| {label} | {r.queries} | {r.shape} ({pct(r.shape, r.queries)}) | {text} |")
    add("")

    # spread
    add("## Memory spread of one shape")
    add("")
    add("For shapes with 3 or more finished runs: largest peak / smallest peak of the finished runs.")
    add("")
    add("| shapes | n | median | p90 | max | smallest peak is 0 |")
    add("|---|---|---|---|---|---|")
    spread_groups = {
        "all": [s for s in shapes if s.ratio() is not None],
        large_label: [s for s in shapes if s.ratio() is not None and max(s.finished_peaks) >= options.large_bytes],
    }
    for label, group in spread_groups.items():
        finite = sorted(s.ratio() for s in group if not math.isinf(s.ratio()))
        zero = sum(1 for s in group if math.isinf(s.ratio()))
        add(f"| {label} | {len(group)} | {ratio_text(statistics.median(finite) if finite else None)} | "
            f"{ratio_text(percentile(finite, 0.9))} | {ratio_text(finite[-1] if finite else None)} | {zero} |")
    add("")
    for label, group in spread_groups.items():
        worst = sorted(group, key=lambda s: (-s.ratio(), s.fingerprint))[:options.top]
        add(f"Worst {len(worst)} shapes ({label}):")
        add("")
        add("| fingerprint | finished runs | min peak | max peak | max/min | normalized SQL |")
        add("|---|---|---|---|---|---|")
        for s in worst:
            add(f"| `{s.fingerprint[:12]}` | {len(s.finished_peaks)} | {human_bytes(min(s.finished_peaks))} | "
                f"{human_bytes(max(s.finished_peaks))} | {ratio_text(s.ratio())} | `{cell(s.normalized_sql)}` |")
        add("")

    # replay
    add("## Replay of the predictor rule")
    add("")
    add("The log is replayed in time order. For each query, the rule predicts the peak from the last "
        f"{options.history_runs} runs of the same shape that ended before the query started: the largest peak "
        "of the finished runs, or, after a memory failure that is newer than the last finished run, the "
        f"largest known peak × {options.growth_factor:g}. A hit is a query with a prediction. "
        "The ratio is predicted / actual peak, for finished queries with a hit and a peak above 0.")
    add("")
    add("| queries | n | hits | ratio median | p10 | p90 | too low (< 0.8) | too high (> 2) |")
    add("|---|---|---|---|---|---|---|---|")
    for group, label in (("all", "all"), ("large", large_label)):
        s = result["accuracy"][group].summary()
        add(f"| {label} | {s['queries']} | {s['hits']} ({pct(s['hits'], s['queries'])}) | {ratio_text(s['median'])} | "
            f"{ratio_text(s['p10'])} | {ratio_text(s['p90'])} | {s['too_low']} ({pct(s['too_low'], s['rated'])}) | "
            f"{s['too_high']} ({pct(s['too_high'], s['rated'])}) |")
    add("")
    bases = result["bases"]
    add(f"Basis of the hits: {bases[BASIS_SUCCESSFUL_RUNS]} from finished runs, "
        f"{bases[BASIS_MEMORY_FAILURE_GROWTH]} from a memory failure × {options.growth_factor:g}. "
        f"Percentages of too low and too high are of the {result['accuracy']['all'].summary()['rated']} rated queries "
        f"(all); finished hits with a peak of 0 are not rated ({result['accuracy']['all'].zero_peak}).")
    add("")

    # failures
    f = result["failures"]
    add("## Memory failures")
    add("")
    add(f"- Memory failures with a fingerprint: {f['total']}. Without a fingerprint: {data['unsupported_memory_failures']}.")
    add(f"- With an earlier run of the same shape: {f['earlier_shape']} ({pct(f['earlier_shape'], f['total'])})")
    add(f"- With a prediction: {f['predicted']} (from finished runs: {f['basis'][BASIS_SUCCESSFUL_RUNS]}, "
        f"from an earlier memory failure: {f['basis'][BASIS_MEMORY_FAILURE_GROWTH]})")
    ratios = sorted(f["ratios"])
    add(f"- Prediction ≥ the peak at which the query failed: {f['at_least_failed_peak']} of {f['predicted']}. "
        f"Predicted / failed peak: median {ratio_text(statistics.median(ratios) if ratios else None)}, "
        f"min {ratio_text(ratios[0] if ratios else None)}, max {ratio_text(ratios[-1] if ratios else None)}.")
    add("- The failed peak is a lower bound of the real need. A prediction below it was too low for sure; "
        "a prediction above it was not necessarily enough.")
    add("")

    # input check
    add("## Input size check")
    add("")
    if not data["has_input_bytes"]:
        add("The log has no input bytes. This check was not possible.")
    else:
        add(f"The same replay, but an earlier run counts only if its input bytes are within "
            f"{options.input_tolerance:.0%} of the input bytes of the query. Only queries with input bytes count. "
            "The real input size is not known before a query runs, so this shows the best case.")
        add("")
        add("| queries | check | n | hits | ratio median | p10 | p90 | too low (< 0.8) | too high (> 2) |")
        add("|---|---|---|---|---|---|---|---|---|")
        for group, label in (("all", "all"), ("large", large_label)):
            for name, acc in zip(("without", "with"), result["input_check"][group]):
                s = acc.summary()
                add(f"| {label} | {name} | {s['queries']} | {s['hits']} ({pct(s['hits'], s['queries'])}) | "
                    f"{ratio_text(s['median'])} | {ratio_text(s['p10'])} | {ratio_text(s['p90'])} | "
                    f"{s['too_low']} ({pct(s['too_low'], s['rated'])}) | {s['too_high']} ({pct(s['too_high'], s['rated'])}) |")
    add("")

    # not possible
    add("## What was not possible")
    add("")
    rejected = (stats.get("rejected") or {}) if stats else {}
    if rejected:
        add(f"Records that normalization rejected: {sum(rejected.values())}.")
        add("")
        add("| reason | records |")
        add("|---|---|")
        for reason, count in sorted(rejected.items(), key=lambda item: (-item[1], item[0]))[:10]:
            add(f"| {cell(reason)} | {count} |")
        add("")
    unsupported = volume["records"] - volume["supported"]
    add(f"Records without a fingerprint: {unsupported} ({pct(unsupported, volume['records'])}). "
        "The 10 most frequent reasons:")
    add("")
    add("| reason | records |")
    add("|---|---|")
    for reason, count in data["reasons"].most_common(10):
        add(f"| {cell(reason)} | {count} |")
    add("")
    return "\n".join(lines)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--records", required=True, help="normalized JSON Lines")
    p.add_argument("--fingerprints", help="fingerprint JSON Lines, one line per record line")
    p.add_argument("--normalize-stats", help="stats JSON file of normalize.py")
    p.add_argument("--out", required=True, help="output directory")
    p.add_argument("--large-bytes", type=int, default=GIB, help="peak of a large query (default 1 GiB)")
    p.add_argument("--input-tolerance", type=float, default=0.10, help="input size check limit (default 0.10)")
    p.add_argument("--history-runs", type=int, default=10)
    p.add_argument("--growth-factor", type=float, default=2.0)
    p.add_argument("--input-name", help="name of the original log, for the report")
    p.add_argument("--top", type=int, default=20, help="number of worst shapes to list")
    options = p.parse_args(argv)
    os.makedirs(options.out, exist_ok=True)
    options.normalize_stats = json.load(open(options.normalize_stats)) if options.normalize_stats else None

    with tempfile.TemporaryFile(dir=options.out) as replay_file:
        data = read_and_index(options.records, options.fingerprints, options.out, replay_file)

        def rows(row):
            replay_file.seek(row * ROW.size)
            return ROW.unpack(replay_file.read(ROW.size))

        replay_file.flush()
        result = replay(rows, data["times"], options.large_bytes, options.history_runs,
                        options.growth_factor, options.input_tolerance)

    write_shapes_csv(os.path.join(options.out, "shapes.csv"), data["shapes"])
    with open(os.path.join(options.out, "REPORT.md"), "w", encoding="utf-8") as f:
        f.write(render_report(data, result, options))
    print(f"report: {data['volume']['records']} records, {len(data['shapes'])} shapes; "
          f"wrote {os.path.join(options.out, 'REPORT.md')}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
