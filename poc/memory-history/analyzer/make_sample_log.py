#!/usr/bin/env python3
"""Writes a deterministic sample query log (CSV) for the analyzer.

The log is made, not measured. It uses the SQL of the PoC workload (workload/workload.yaml
and workload/queries/) and the measured peak ranges of results/REPORT.md. Same seed, same file.

  make_sample_log.py --output sample/sample-log.csv [--seed 29598]

What the log holds (UTC, 7 days from 2026-11-02):
- Day 0: every (template, variant) once with the literals of workload.yaml, as in the learn
  pass. The 9 heavy runs fail with EXCEEDED_LOCAL_MEMORY_LIMIT (2 workers), as in the lab.
- Days 1-6: every template 5 times a day with new literals, drawn from value lists.
  A heavy run fails with a memory error with probability 0.08.
- A dashboard: tpcds_q96 with the literals of variant 0, 3 times a day on days 1-6 (exact-text repeats).
- A date-range query on lineitem: 4 times a day on days 0-6. The literal INTERVAL is 1 day
  (probability 0.7) or 100 days. 100 days reads 100 times the bytes and needs 100 times the memory.
  A 100-day run fails with a memory error with probability 0.15.
- 10 ad hoc queries of different shapes, once each.
- 6 statements without a fingerprint: SHOW, DESCRIBE, SET SESSION, CREATE TABLE, 2 invalid SQL.
- Any other run fails with USER_CANCELED with probability 0.02.

Peaks of finished runs: log-uniform between the smallest and largest measured peak of the
template. The `template` column is not used by the analyzer; it allows checks by hand.
"""

import argparse
import csv
import math
import os
import random
import re
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
WORKLOAD = os.path.join(HERE, "..", "workload")
PLACEHOLDER = re.compile(r"\{\{\s*(\w+)\s*\}\}")
START = datetime(2026, 11, 2, tzinfo=timezone.utc)
DAYS = 7
KIB, MIB, GIB = 1024, 1024 ** 2, 1024 ** 3

# Smallest and largest peak of the FINISHED runs per template, from results/REPORT.md
PEAK_RANGE = {
    "tpcds_q03": (3.22 * MIB, 7.86 * MIB),
    "tpcds_q07": (48.78 * MIB, 53.54 * MIB),
    "tpcds_q19": (1.24 * GIB, 1.35 * GIB),
    "tpcds_q42": (20.64 * MIB, 31.48 * MIB),
    "tpcds_q43": (1.02 * GIB, 1.11 * GIB),
    "tpcds_q55": (4.08 * MIB, 5.18 * MIB),
    "tpcds_q96": (3.30 * MIB, 3.35 * MIB),
    "tpch_q01": (1.55 * KIB, 11.30 * MIB),
    "tpch_q06": (96, 4.38 * MIB),
    "tpch_q09": (2.15 * GIB, 2.33 * GIB),
    "tpch_q13": (471.12 * MIB, 589.17 * MIB),
    "tpch_q18": (1.52 * GIB, 1.60 * GIB),
    "tpch_q21": (3.29 * GIB, 3.43 * GIB),
}
# Peak at the memory failure on 2 workers (learn pass of results/REPORT.md)
FAILED_PEAK_RANGE = {
    "tpch_q09": (1.68 * GIB, 1.94 * GIB),
    "tpch_q18": (1.58 * GIB, 1.91 * GIB),
    "tpch_q21": (1.56 * GIB, 1.61 * GIB),
}
# Physical input bytes per run (made up, in proportion to the scale factor); each run varies by ±3%
INPUT_BYTES = {"sf1": 250 * MIB, "sf5": 1.4 * GIB, "sf10": 2.8 * GIB}
DURATION_S = {"small": (1, 6), "medium": (8, 40), "heavy": (60, 240)}
VALUES = {
    "manufact_id": list(range(1, 1001)),
    "moy": list(range(1, 13)),
    "year": list(range(1990, 2006)),
    "manager_id": list(range(1, 101)),
    "hour": list(range(0, 24)),
    "dep_count": list(range(0, 10)),
    "discount": [0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08, 0.09, 0.10],
    "delta_days": list(range(60, 121)),
    "gender": ["M", "F"],
    "gmt_offset": [-5, -6, -7, -8],
    "word1": ["special", "pending", "express", "unusual", "final", "regular", "ironic", "bold"],
    "word2": ["requests", "deposits", "packages", "accounts", "instructions", "theodolites"],
    "quantity": list(range(280, 331)),
    "color": ["green", "blue", "red", "yellow", "white", "black", "ivory", "khaki", "lime", "navy",
              "olive", "orange", "pink", "plum", "rose", "salmon", "tan", "violet", "wheat", "azure"],
    "nation": ["ALGERIA", "ARGENTINA", "BRAZIL", "CANADA", "EGYPT", "ETHIOPIA", "FRANCE", "GERMANY", "INDIA",
               "INDONESIA", "IRAN", "IRAQ", "JAPAN", "JORDAN", "KENYA", "MOROCCO", "MOZAMBIQUE", "PERU", "CHINA",
               "ROMANIA", "SAUDI ARABIA", "VIETNAM", "RUSSIA", "UNITED KINGDOM", "UNITED STATES"],
}
RANGE_QUERY = """SELECT l.suppkey, count(*) AS lines, sum(l.extendedprice) AS revenue
FROM lineitem l
WHERE l.shipdate >= DATE '{start}' AND l.shipdate < DATE '{start}' + INTERVAL '{days}' DAY
GROUP BY l.suppkey
ORDER BY revenue DESC"""
RANGE_SMALL = {"peak": 30 * MIB, "input": 64 * MIB}  # 1 day; 100 days is 100 times both
AD_HOC = [
    "SELECT count(*) FROM orders WHERE orderstatus = 'F'",
    "SELECT orderpriority, count(*) FROM orders GROUP BY orderpriority",
    "SELECT * FROM nation ORDER BY name",
    "SELECT max(totalprice) FROM orders WHERE orderdate > DATE '1998-01-01'",
    "SELECT c.mktsegment, avg(c.acctbal) FROM customer c GROUP BY c.mktsegment",
    "SELECT p.brand, count(*) FROM part p WHERE p.size < 10 GROUP BY p.brand",
    "SELECT s.name FROM supplier s JOIN nation n ON s.nationkey = n.nationkey WHERE n.name = 'PERU'",
    "SELECT shipmode, sum(quantity) FROM lineitem WHERE shipdate = DATE '1995-03-15' GROUP BY shipmode",
    "SELECT count(DISTINCT custkey) FROM orders",
    "WITH big AS (SELECT custkey FROM orders WHERE totalprice > 400000) SELECT count(*) FROM big",
]
NO_FINGERPRINT = [
    "SHOW TABLES",
    "DESCRIBE orders",
    "SET SESSION query_max_run_time = '1h'",
    "CREATE TABLE memory.default.t (a bigint)",
    "SELEC count(*) FROM orders",
    "SELECT count(* FROM orders",
]


def load_templates():
    """Reads the subset of YAML that workload.yaml uses (no YAML package needed)."""
    templates = []
    with open(os.path.join(WORKLOAD, "workload.yaml")) as f:
        for raw in f:
            line = raw.split(" #")[0].rstrip() if not raw.lstrip().startswith("#") else ""
            if not line.strip():
                continue
            stripped = line.strip()
            if stripped.startswith("- name:"):
                templates.append({"name": stripped.split(":", 1)[1].strip(), "variants": []})
            elif not templates:
                continue
            elif stripped.startswith("- {"):
                variant = {}
                for item in stripped[3:-1].split(","):
                    key, value = item.split(":", 1)
                    variant[key.strip()] = value.strip()
                templates[-1]["variants"].append(variant)
            elif stripped.startswith("schemas:"):
                templates[-1]["schemas"] = [s.strip() for s in stripped.split("[", 1)[1].rstrip("]").split(",")]
            elif ":" in stripped and not stripped.startswith("-"):
                key, value = stripped.split(":", 1)
                if key in ("file", "size", "catalog"):
                    templates[-1][key] = value.strip()
    for template in templates:
        with open(os.path.join(WORKLOAD, template["file"])) as f:
            template["sql"] = f.read()
    return templates


def render(template, variables):
    """As workload/run_workload.py renders, then without the trailing semicolon (as gate.py sends it)."""
    sql = PLACEHOLDER.sub(lambda m: str(variables[m.group(1)]), template)
    return sql.strip().rstrip(";").strip()


def log_uniform(rng, low, high):
    return int(math.exp(rng.uniform(math.log(low), math.log(high))))


class Log:
    def __init__(self, rng):
        self.rng = rng
        self.rows = []
        self.sequence = 0

    def add(self, day, sql, catalog, schema, template, size, state, error_code, peak, input_bytes, duration=None):
        rng = self.rng
        start = START + timedelta(days=day, seconds=rng.randint(7 * 3600, 19 * 3600), milliseconds=rng.randint(0, 999))
        seconds = duration if duration is not None else rng.uniform(*DURATION_S[size])
        end = start + timedelta(seconds=seconds)
        self.sequence += 1
        suffix = "".join(rng.choice("abcdefghijkmnpqrstuvwxyz23456789") for _ in range(5))
        self.rows.append({
            "query_id": f"{start:%Y%m%d_%H%M%S}_{self.sequence:05d}_{suffix}",
            "user": rng.choice(["analyst1", "analyst2", "etl", "dashboard"]) if template != "dashboard" else "dashboard",
            "source": "sample",
            "catalog": catalog,
            "schema": schema,
            "query_text": sql,
            "state": state,
            "error_code": error_code or "",
            "created": f"{start:%Y-%m-%d %H:%M:%S}.{start.microsecond // 1000:03d}+00",
            "ended": f"{end:%Y-%m-%d %H:%M:%S}.{end.microsecond // 1000:03d}+00",
            "peak_user_memory_bytes": peak,
            "input_bytes": input_bytes,
            "template": template,
        })


def run_template(log, rng, template, day, variables, cold=False):
    name, size, schema = template["name"], template["size"], template["schemas"][0]
    sql = render(template["sql"], variables)
    need = log_uniform(rng, *PEAK_RANGE[name])
    full_input = int(INPUT_BYTES[schema] * rng.uniform(0.97, 1.03))
    if size == "heavy" and (cold or rng.random() < 0.08):
        part = rng.uniform(0.3, 0.9)
        failed_peak = log_uniform(rng, *FAILED_PEAK_RANGE[name])
        log.add(day, sql, template["catalog"], schema, name, size, "FAILED", "EXCEEDED_LOCAL_MEMORY_LIMIT", failed_peak, int(full_input * part))
    elif rng.random() < 0.02:
        part = rng.uniform(0.1, 0.9)
        log.add(day, sql, template["catalog"], schema, name, size, "FAILED", "USER_CANCELED", int(need * part), int(full_input * part))
    else:
        log.add(day, sql, template["catalog"], schema, name, size, "FINISHED", None, need, full_input)


def generate(seed):
    rng = random.Random(seed)
    templates = load_templates()
    log = Log(rng)
    q96 = next(t for t in templates if t["name"] == "tpcds_q96")

    for day in range(DAYS):
        if day == 0:
            for template in templates:
                for variables in template["variants"]:
                    run_template(log, rng, template, day, variables, cold=True)
        else:
            for template in templates:
                for _ in range(5):
                    variables = {key: rng.choice(VALUES[key]) for key in template["variants"][0]}
                    run_template(log, rng, template, day, variables)
            for _ in range(3):
                need = log_uniform(rng, *PEAK_RANGE["tpcds_q96"])
                log.add(day, render(q96["sql"], q96["variants"][0]), "tpcds", "sf1", "dashboard", "small", "FINISHED", None,
                        need, int(INPUT_BYTES["sf1"] * rng.uniform(0.97, 1.03)))
        for _ in range(4):
            large = rng.random() >= 0.7
            days = 100 if large else 1
            start = (datetime(1995, 1, 1) + timedelta(days=rng.randint(0, 900))).date().isoformat()
            sql = RANGE_QUERY.format(start=start, days=days)
            peak = int(RANGE_SMALL["peak"] * days * rng.uniform(0.95, 1.05))
            input_bytes = int(RANGE_SMALL["input"] * days * rng.uniform(0.97, 1.03))
            template = "range_100_days" if large else "range_1_day"
            if large and rng.random() < 0.15:
                part = rng.uniform(0.4, 0.7)
                log.add(day, sql, "tpch", "sf5", template, "heavy", "FAILED", "EXCEEDED_LOCAL_MEMORY_LIMIT", int(peak * part), int(input_bytes * part))
            else:
                log.add(day, sql, "tpch", "sf5", template, "heavy" if large else "small", "FINISHED", None, peak, input_bytes)

    for sql in AD_HOC:
        log.add(rng.randrange(DAYS), sql, "tpch", "sf1", "ad_hoc", "small", "FINISHED", None,
                log_uniform(rng, 64 * KIB, 64 * MIB), int(INPUT_BYTES["sf1"] * rng.uniform(0.1, 1.0)))
    for sql in NO_FINGERPRINT:
        invalid = sql.startswith("SELEC")
        log.add(rng.randrange(DAYS), sql, "tpch", "sf1", "no_fingerprint", "small", "FAILED" if invalid else "FINISHED",
                "SYNTAX_ERROR" if invalid else None, 0, 0)

    log.rows.sort(key=lambda row: row["ended"])
    return log.rows


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--output", default=os.path.join(HERE, "sample", "sample-log.csv"))
    p.add_argument("--seed", type=int, default=29598)
    args = p.parse_args()
    rows = generate(args.seed)
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {len(rows)} records to {args.output}")


if __name__ == "__main__":
    main()
