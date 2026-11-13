#!/usr/bin/env python3
"""Report for the memory-history PoC (Contract 6).

Reads results/runs.jsonl (written by run_workload.py) and, when reachable, the
PostgreSQL table query_history (Contract 2). Writes results/REPORT.md.
Without PostgreSQL it uses the actual values that gate.py read from the Trino
REST API and stored in runs.jsonl.

  python report.py
  python report.py --db-url postgresql://memoryhistory:memoryhistory@localhost:15432/memoryhistory
  python report.py --no-db
"""

import argparse
import json
import os
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.path.join(HERE, "..", "results")
DEFAULT_DB_URL = "postgresql://memoryhistory:memoryhistory@localhost:15432/memoryhistory"
MEMORY_ERRORS = {"EXCEEDED_LOCAL_MEMORY_LIMIT", "EXCEEDED_GLOBAL_MEMORY_LIMIT", "CLUSTER_OUT_OF_MEMORY"}

KNOWN_LIMITS = [
    "The fingerprint is text-based. The same query on different tables/schemas gets a different fingerprint. "
    "Literals that change the data volume (e.g. a wider date range) share one fingerprint.",
    "The prediction is the maximum of recent successful runs. A run that failed with a memory error only gives a lower "
    "bound. Thus the first gated run after a failure uses `FAILURE_GROWTH_FACTOR`, and the prediction can still be too small.",
    "The predictor converts cluster-wide peak memory to workers: it divides by `query.max-memory-per-node`. "
    "This assumes an even spread of memory over workers. Skewed partitions or broadcast joins break this assumption.",
    "Queries with unknown fingerprints (cold start) run without a gate and can fail. They only help the next run.",
    "The predictor holds reservations in memory, and a restart forgets them. The scaler scales down after "
    "120 s. It does not check whether queries still run on the workers that it removes.",
    "The gate is client-side. Clients that do not use it get no prediction.",
]


def fmt_bytes(value):
    if value is None:
        return "-"
    for unit, size in (("GB", 2**30), ("MB", 2**20), ("kB", 2**10)):
        if value >= size:
            return f"{value / size:.2f} {unit}"
    return f"{value} B"


def fmt_ms(value):
    return "-" if value is None else f"{value / 1000:.1f} s"


def percentile(values, q):
    values = sorted(values)
    if not values:
        return None
    k = (len(values) - 1) * q
    lower = int(k)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (k - lower)


def load_runs(path):
    runs = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                runs.append(json.loads(line))
    return runs


def load_history(db_url, query_ids):
    """query_id -> row dict from query_history, or None when PostgreSQL is not reachable."""
    try:
        import psycopg
        from psycopg.rows import dict_row
    except ImportError:
        print("report: psycopg not installed, using runs.jsonl only", file=sys.stderr)
        return None
    try:
        with psycopg.connect(db_url, connect_timeout=5, row_factory=dict_row) as conn:
            rows = conn.execute(
                "SELECT query_id, fingerprint, query_state, error_code, peak_user_memory_bytes, "
                "peak_task_user_memory_bytes, wall_time_ms FROM query_history WHERE query_id = ANY(%s)",
                (list(query_ids),)).fetchall()
    except Exception as e:  # noqa: BLE001 - any DB problem means fall back
        print(f"report: query_history not available ({e.__class__.__name__}: {' '.join(str(e).split())[:120]}), using runs.jsonl only", file=sys.stderr)
        return None
    return {r["query_id"]: r for r in rows}


def enrich(runs, history):
    """Add normalized fields: peak, fingerprint, known, predicted."""
    for r in runs:
        actual = r.get("actual") or {}
        h = (history or {}).get(r.get("query_id"))
        prediction = r.get("prediction") or {}
        r["_peak"] = h["peak_user_memory_bytes"] if h else actual.get("peak_user_memory_bytes")
        r["_state"] = h["query_state"] if h else r.get("state")
        r["_error"] = (h["error_code"] if h else None) or r.get("error_code")
        r["_fingerprint"] = (h["fingerprint"] if h else None) or prediction.get("fingerprint")
        r["_group"] = r["_fingerprint"] or f"{r.get('template')}@{r.get('catalog')}.{r.get('schema')}"
        r["_known"] = bool(prediction.get("known"))
        r["_predicted"] = prediction.get("predictedPeakUserMemoryBytes")
        r["_in_history"] = h is not None
    return runs


def build_report(runs, history, source):
    lines = []
    out = lines.append
    out("# Memory-history PoC report")
    out("")
    out(f"Generated {datetime.now(timezone.utc).isoformat(timespec='seconds')} from {len(runs)} runs. Actual values from: {source}.")
    if history is not None:
        out(f"Runs found in `query_history`: {sum(r['_in_history'] for r in runs)} of {len(runs)}.")
    out("")

    by_pass = defaultdict(list)
    for r in runs:
        by_pass[r.get("pass", "?")].append(r)

    out("## Summary per pass")
    out("")
    out("| pass | runs | gated | FINISHED | memory errors | other errors | known prediction | waited for workers (> 1 s) | workers added during the run |")
    out("|---|---|---|---|---|---|---|---|---|")
    for name, rs in sorted(by_pass.items()):
        out(f"| {name} | {len(rs)} | {sum(bool(r.get('gated')) for r in rs)} | {sum(r['_state'] == 'FINISHED' for r in rs)} "
            f"| {sum(r['_error'] in MEMORY_ERRORS for r in rs)} "
            f"| {sum(r['_state'] != 'FINISHED' and r['_error'] not in MEMORY_ERRORS for r in rs)} "
            f"| {sum(r['_known'] for r in rs)} | {sum((r.get('wait_ms') or 0) > 1000 for r in rs)} "
            f"| {sum((r.get('workers_after') or 0) > (r.get('workers_before') or 0) for r in rs)} |")
    out("")

    gated = [r for r in runs if r.get("gated") and r.get("pass") == "predict"]
    out("## Hit rate (predict pass, gated)")
    out("")
    unreachable = sum(bool((r.get("prediction") or {}).get("error")) for r in gated)
    if unreachable:
        out(f"**Warning: the gate could not connect to the predictor for {unreachable} of {len(gated)} gated runs. "
            "Those queries ran without the gate.**")
        out("")
    if gated:
        hits = sum(r["_known"] for r in gated)
        out(f"{hits} of {len(gated)} queries had a known fingerprint: **{hits / len(gated):.0%}**.")
    else:
        out("No gated predict-pass runs.")
    out("")

    out("## Predicted vs actual peak user memory (hits that FINISHED)")
    out("")
    pairs = [(r, r["_predicted"] / r["_peak"]) for r in runs
             if r["_known"] and r["_predicted"] and r["_peak"] and r["_state"] == "FINISHED"]
    if pairs:
        ratios = [ratio for _, ratio in pairs]
        under = sum(ratio < 1 for ratio in ratios)
        worst_r, worst = max(pairs, key=lambda p: abs(p[1] - 1) if p[1] >= 1 else 1 / p[1] - 1)
        out("Ratio = predicted / actual (1.0 is perfect, < 1 is under-prediction).")
        out("")
        out(f"- n = {len(ratios)}, under-predicted: {under}")
        out(f"- median: {statistics.median(ratios):.2f}")
        out(f"- p90: {percentile(ratios, 0.9):.2f}")
        out(f"- min: {min(ratios):.2f}, max: {max(ratios):.2f}")
        out(f"- worst: {worst:.2f} ({worst_r.get('template')} {worst_r.get('schema')} v{worst_r.get('variant')}, "
            f"predicted {fmt_bytes(worst_r['_predicted'])}, actual {fmt_bytes(worst_r['_peak'])})")
        out("")
        out("The same ratio per group. Queries under 100 MB are noisy (a few kB to a few MB). They do not matter for scaling.")
        out("")
        out("| group | n | under-predicted | median | p90 | min | max |")
        out("|---|---|---|---|---|---|---|")
        big = 100 * 1024 * 1024

        def basis(r):
            return (r.get("prediction") or {}).get("basis")

        groups_by_name = [
            ("actual peak ≥ 100 MB, predicted from successful runs",
             [x for r, x in pairs if r["_peak"] >= big and basis(r) == "max_of_last_successful_runs"]),
            ("actual peak ≥ 100 MB, predicted from a memory failure (peak × growth factor)",
             [x for r, x in pairs if r["_peak"] >= big and basis(r) == "memory_failure_growth"]),
            ("actual peak < 100 MB",
             [x for r, x in pairs if r["_peak"] < big]),
        ]
        for name, values in groups_by_name:
            if values:
                out(f"| {name} | {len(values)} | {sum(v < 1 for v in values)} | {statistics.median(values):.2f} | "
                    f"{percentile(values, 0.9):.2f} | {min(values):.2f} | {max(values):.2f} |")
        out("")
        out("These ratios are **in-sample**. Every query in the predict passes ran before with the same literals, "
            "and the prediction is the maximum over that history.")
        out("")
        out("### Leave-one-variant-out")
        out("")
        out("For each FINISHED run with a peak ≥ 100 MB, this check predicts from the runs of the *other* literal variants "
            "of the same template only (maximum of their peaks). This is a retrospective cross-check, not a replay of the "
            "predictor. It uses successful runs from all passes (also later ones), and it ignores the failure-growth rule.")
        out("")
        by_template = defaultdict(lambda: defaultdict(list))
        for r in runs:
            if r["_state"] == "FINISHED" and r["_peak"]:
                by_template[(r.get("template"), r.get("schema"))][r.get("variant")].append(r["_peak"])
        loo = []
        for variants in by_template.values():
            for variant, peaks in variants.items():
                others = [x for v, ps in variants.items() if v != variant for x in ps]
                if others:
                    loo.extend(max(others) / peak for peak in peaks if peak >= big)
        if loo:
            out(f"- n = {len(loo)}, under-predicted: {sum(v < 1 for v in loo)}")
            out(f"- median: {statistics.median(loo):.2f}, p90: {percentile(loo, 0.9):.2f}, min: {min(loo):.2f}, max: {max(loo):.2f}")
        else:
            out("Not enough runs.")
    else:
        out("No hits with an actual peak yet.")
    out("")

    out("## Spread of actual peak within one template (FINISHED runs, all passes)")
    out("")
    out("Literal variants of one template should share one fingerprint. A max/min close to 1 means that literals do not change memory much.")
    out("")
    out("| template | schema | fingerprints | runs | min | max | max/min |")
    out("|---|---|---|---|---|---|---|")
    groups = defaultdict(list)
    fingerprints = defaultdict(set)
    for r in runs:
        if r["_state"] == "FINISHED" and r["_peak"]:
            key = (r.get("template") or "", r.get("schema") or "")
            groups[key].append(r["_peak"])
            if r["_fingerprint"]:
                fingerprints[key].add(r["_fingerprint"][:12])
    for (template, schema), peaks in sorted(groups.items()):
        fps = ", ".join(sorted(fingerprints[(template, schema)])) or "-"
        out(f"| {template} | {schema} | {fps} | {len(peaks)} | {fmt_bytes(min(peaks))} | {fmt_bytes(max(peaks))} | {max(peaks) / min(peaks):.2f} |")
    out("")

    out("## Heavy queries")
    out("")
    out("| template | schema | variant | pass | gated | state | error | peak | predicted | required | workers before → after | wait |")
    out("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in sorted((r for r in runs if r.get("size") == "heavy"),
                    key=lambda r: (r.get("template") or "", r.get("schema") or "", r.get("variant") or 0, r.get("ts") or "")):
        out(f"| {r.get('template')} | {r.get('schema')} | v{r.get('variant')} | {r.get('pass')} | {'yes' if r.get('gated') else 'no'} "
            f"| {r['_state']} | {r['_error'] or '-'} | {fmt_bytes(r['_peak'])} | {fmt_bytes(r['_predicted'])} "
            f"| {r.get('required_workers') or '-'} | {r.get('workers_before')} → {r.get('workers_after')} | {fmt_ms(r.get('wait_ms'))} |")
    heavy = defaultdict(lambda: {"ungated_mem_fail_low": False, "gated_ok": False})
    for r in runs:
        if r.get("size") != "heavy":
            continue
        key = (r.get("template"), r.get("schema"), r.get("variant"))
        if r["_error"] in MEMORY_ERRORS and not r.get("required_workers") and (r.get("workers_before") or 0) <= 2:
            heavy[key]["ungated_mem_fail_low"] = True
        if r["_state"] == "FINISHED" and r.get("gated") and r["_known"]:
            heavy[key]["gated_ok"] = True
    if heavy:
        both = sum(v["ungated_mem_fail_low"] and v["gated_ok"] for v in heavy.values())
        out("")
        out(f"Heavy (template, schema, variant) that failed with a memory error without a gate on ≤ 2 workers **and** later succeeded gated with a known prediction: "
            f"{both} of {len(heavy)}.")
    out("")

    out("## Cold start (learn pass)")
    out("")
    learn = by_pass.get("learn", [])
    if learn:
        unknown = [r for r in learn if not r["_known"]]
        out(f"- {len(unknown)} of {len(learn)} learn-pass queries had no prediction (unknown fingerprint or no gate) and ran unchanged.")
        out(f"- Of those, {sum(r['_error'] in MEMORY_ERRORS for r in unknown)} failed with a memory error. "
            "The listener records these failures, and they drive the `memory_failure_growth` prediction in the next pass.")
        seen = set()
        later_known = 0
        for r in sorted(learn, key=lambda r: r.get("ts") or ""):
            if r["_group"] in seen and r["_known"]:
                later_known += 1
            seen.add(r["_group"])
        out(f"- {later_known} learn-pass queries were already known from an earlier variant of the same template in the same pass.")
    else:
        out("No learn-pass runs.")
    out("")

    out("## Known limits")
    out("")
    for item in KNOWN_LIMITS:
        out(f"- {item}")
    out("")
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--runs", default=os.path.join(RESULTS, "runs.jsonl"))
    p.add_argument("--output", default=os.path.join(RESULTS, "REPORT.md"))
    p.add_argument("--db-url", default=os.environ.get("MEMORY_HISTORY_DB_URL", DEFAULT_DB_URL))
    p.add_argument("--no-db", action="store_true", help="do not read query_history")
    args = p.parse_args()

    runs = load_runs(args.runs)
    if not runs:
        print(f"report: no runs in {args.runs}", file=sys.stderr)
        return 1
    history = None if args.no_db else load_history(args.db_url, [r["query_id"] for r in runs if r.get("query_id")])
    source = "PostgreSQL query_history (falling back to runs.jsonl per query)" if history is not None else "runs.jsonl (Trino REST API)"
    report = build_report(enrich(runs, history), history, source)
    with open(args.output, "w") as f:
        f.write(report)
    print(f"report: wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
