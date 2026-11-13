"""Writes BENCH-<env>.md from bench-<env>.jsonl. Standard library only.

Usage: python3 report.py /path/to/bench-compose.jsonl /path/to/BENCH-compose.md
"""
import collections
import json
import random
import statistics
import sys

PATHS = ["direct", "gateway-plain", "gateway", "envoy-plain", "envoy-gated", "envoy-first-only"]
BOOTSTRAP = 2000
NOT_CHECKED = """### Not checked

- One host. I ran the whole bench twice (the second time on a fresh lab). This report is the second run; the first is in `bench-compose-run1.jsonl`. The latency medians of the two runs differ by at most about 5 ms. I did not run it more often.
- Cluster B is only on the routing checks (L2, L3). All timed paths go to cluster A.
- `SELECT 1` is a known query after its first run (the listener records every query), so W1 on the gated paths also makes a prediction and a reservation. I did not time an unknown query on the gated paths.
- The predictor and PostgreSQL ran on the same host as everything else. A remote predictor adds its network time.
- W3 measures the bench client, which parses every page with Python `json`. A faster client can show larger differences between the paths.
- No TLS, no authentication, HTTP/1.1 only.
- Envoy runs with 4 worker threads, Trino Gateway with its defaults. I did not tune either.
"""


def quantile(values, p):
    values = sorted(values)
    if not values:
        return float("nan")
    position = p * (len(values) - 1)
    low = int(position)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (position - low)


def median_diff_ci(values, base, rng):
    """95% bootstrap interval of median(values) - median(base)."""
    diffs = []
    for _ in range(BOOTSTRAP):
        a = [values[rng.randrange(len(values))] for _ in values]
        b = [base[rng.randrange(len(base))] for _ in base]
        diffs.append(statistics.median(a) - statistics.median(b))
    return quantile(diffs, 0.025), quantile(diffs, 0.975)


def fmt(value):
    return f"{value:.1f}"


def latency_table(records, title, rng, paths=PATHS):
    lines = [f"### {title}", ""]
    by_path = collections.defaultdict(list)
    errors = collections.Counter()
    for r in records:
        if r.get("state") == "FINISHED":
            by_path[r["path"]].append(r)
        else:
            errors[r["path"]] += 1
    base = by_path.get("direct", [])
    lines.append("| Path | n | errors | total p50 | p95 | p99 | Δp50 vs direct [95% CI] | first p50 | p95 | p99 | Δp50 vs direct [95% CI] | noise? |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for path in paths:
        rows = by_path.get(path, [])
        if not rows:
            continue
        cells = [path, str(len(rows)), str(errors[path])]
        verdicts = []
        for key in ("t_total_ms", "t_first_ms"):
            values = [r[key] for r in rows]
            cells += [fmt(statistics.median(values)), fmt(quantile(values, 0.95)), fmt(quantile(values, 0.99))]
            if path == "direct" or not base:
                cells.append("—")
            else:
                base_values = [r[key] for r in base]
                low, high = median_diff_ci(values, base_values, rng)
                diff = statistics.median(values) - statistics.median(base_values)
                cells.append(f"{diff:+.1f} [{low:+.1f}, {high:+.1f}]")
                verdicts.append(low <= 0 <= high)
        cells.append("—" if path == "direct" else ("within noise" if all(verdicts) else ("total within noise" if verdicts and verdicts[0] else "no")))
        lines.append("| " + " | ".join(cells) + " |")
    lines.append("")
    return lines


def main():
    source, target = sys.argv[1], sys.argv[2]
    records = [json.loads(line) for line in open(source) if line.strip()]
    rng = random.Random(42)
    env = records[0].get("env", "?") if records else "?"
    out = [f"# Bench: {env}", "",
           "Times in ms. total = POST until the last page is read. first = the POST /v1/statement request only.",
           "Δp50 = median of the path − median of direct, with a 95% bootstrap interval. "
           "\"within noise\" = the interval contains 0 for total and first.", ""]

    timed = [r for r in records if "path" in r and "t_total_ms" in r]
    w1_seq = [r for r in timed if r.get("workload") == "W1" and r.get("concurrency") == 1]
    w1_con = [r for r in timed if r.get("workload") == "W1" and r.get("concurrency", 1) > 1]
    w2 = [r for r in timed if r.get("workload") == "W2"]
    w3 = [r for r in timed if r.get("workload") == "W3"]
    if w1_seq:
        out += latency_table(w1_seq, "W1: SELECT 1, in sequence", rng)
    if w1_con:
        out += latency_table(w1_con, "W1: SELECT 1, 8 at the same time", rng)
        walls = [r for r in records if r.get("kind") == "wall" and r.get("workload") == "W1"]
        out += ["| Path | queries | wall s | queries/s |", "|---|---|---|---|"]
        for path in PATHS:
            for r in walls:
                if r["path"] == path:
                    out.append(f"| {path} | {r['queries']} | {r['wall_s']:.1f} | {r['queries'] / r['wall_s']:.1f} |")
        out.append("")
    if w2:
        out += latency_table(w2, "W2: known query (count(*) on tpch.tiny.orders), in sequence", rng)
        stats = [r for r in records if r.get("kind") == "gate_stats" and r.get("when") == "after W2"]
        if stats:
            before, after = stats[-1]["before"], stats[-1]["stats"]
            delta = {k: after.get(k, 0) - before.get(k, 0) for k in after if isinstance(after.get(k), int)}
            out += [f"Gate counters during W2: {json.dumps(delta, sort_keys=True)}.", ""]
    if w3:
        out += ["### W3: large result (tpch.sf1.lineitem), in sequence", "",
                "| Path | n | errors | rows | total p50 s | total min–max s | rows/s p50 | MB/s p50 | Δ total p50 vs direct |", "|---|---|---|---|---|---|---|---|---|"]
        base = [r["t_total_ms"] for r in w3 if r["path"] == "direct" and r.get("state") == "FINISHED"]
        for path in PATHS:
            ok = [r for r in w3 if r["path"] == path and r.get("state") == "FINISHED"]
            bad = [r for r in w3 if r["path"] == path and r.get("state") != "FINISHED"]
            if not ok:
                continue
            total = statistics.median(r["t_total_ms"] for r in ok) / 1000
            rows_s = statistics.median(r["rows"] / (r["t_total_ms"] / 1000) for r in ok)
            mb_s = statistics.median(r["bytes"] / 1e6 / (r["t_total_ms"] / 1000) for r in ok)
            diff = "—" if path == "direct" or not base else f"{(statistics.median(r['t_total_ms'] for r in ok) - statistics.median(base)) / statistics.median(base) * 100:+.1f}%"
            low = min(r["t_total_ms"] for r in ok) / 1000
            high = max(r["t_total_ms"] for r in ok) / 1000
            out.append(f"| {path} | {len(ok)} | {len(bad)} | {ok[0]['rows']} | {total:.2f} | {low:.2f}–{high:.2f} | {rows_s:,.0f} | {mb_s:.1f} | {diff} |")
        out += ["", "With 5 runs I give no interval for W3. A path is within noise when its min–max range overlaps the range of direct.", ""]
    for tag in sorted({r.get("tag") for r in timed if r.get("workload", "").startswith("W4")}):
        w4 = [r for r in timed if r.get("workload") == "W4" and r.get("tag") == tag]
        known = [r for r in timed if r.get("workload") == "W4-known" and r.get("tag") == tag]
        out += latency_table(w4, f"W4 ({tag}): SELECT 1, in sequence", rng, ["direct", "gateway", "envoy-gated", "envoy-first-only"])
        out += latency_table(known, f"W4 ({tag}): known query, in sequence", rng, ["direct", "gateway", "envoy-gated", "envoy-first-only"])
        stats = [r for r in records if r.get("kind") == "gate_stats" and r.get("when") == f"after W4 {tag}"]
        if stats and "error" not in stats[-1]["stats"] and "error" not in stats[-1]["before"]:
            before, after = stats[-1]["before"], stats[-1]["stats"]
            out += [f"Gate counters during W4 ({tag}): {json.dumps({k: after[k] - before.get(k, 0) for k in after}, sort_keys=True)}.", ""]
    checks = [r for r in records if r.get("kind") == "check"]
    if checks:
        out += ["### Checks", "", "| Check | Result |", "|---|---|"]
        for r in checks:
            out.append(f"| {r['check']} | {'pass' if r['ok'] else 'FAIL'} |")
        out.append("")
    out.append(NOT_CHECKED)
    open(target, "w").write("\n".join(out))


if __name__ == "__main__":
    main()
