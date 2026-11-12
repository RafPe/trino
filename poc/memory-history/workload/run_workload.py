#!/usr/bin/env python3
"""Workload driver for the memory-history PoC (Contract 6).

Runs every (template, schema, variant) from workload.yaml through gate.run_query,
tags each query with client tags poc_pass, poc_template, poc_variant, and
appends one JSON line per query to results/runs.jsonl. The line includes the
prediction made before the run.

  python run_workload.py --pass learn
  python run_workload.py --pass predict
  python run_workload.py --pass learn --no-gate --only tpcds_q03,tpch_q06
"""

import argparse
import json
import os
import re
import sys
import time

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "gate"))
import gate  # noqa: E402

PLACEHOLDER = re.compile(r"\{\{\s*(\w+)\s*\}\}")


def render(template, variables):
    def substitute(match):
        name = match.group(1)
        if name not in variables:
            raise KeyError(f"no value for placeholder {{{{{name}}}}}")
        return str(variables[name])
    return PLACEHOLDER.sub(substitute, template)


def load_workload(path):
    with open(path) as f:
        workload = yaml.safe_load(f)
    base = os.path.dirname(os.path.abspath(path))
    for t in workload["templates"]:
        with open(os.path.join(base, t["file"])) as f:
            t["sql"] = f.read()
    return workload


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--pass", dest="pass_name", required=True, choices=["learn", "predict"])
    p.add_argument("--workload", default=os.path.join(HERE, "workload.yaml"))
    p.add_argument("--server", default=gate.DEFAULT_SERVER)
    p.add_argument("--predictor", default=gate.DEFAULT_PREDICTOR)
    p.add_argument("--user", default="poc")
    p.add_argument("--no-gate", action="store_true", help="submit every query unchanged")
    p.add_argument("--only", default=None, help="comma-separated template names")
    p.add_argument("--size", default=None, help="comma-separated sizes (small,medium,heavy)")
    p.add_argument("--output", default=os.path.join(HERE, "..", "results", "runs.jsonl"))
    args = p.parse_args()

    workload = load_workload(args.workload)
    session = {k: str(v) for k, v in (workload.get("session_properties") or {}).items()}
    only = set(args.only.split(",")) if args.only else None
    sizes = set(args.size.split(",")) if args.size else None
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)

    started = time.monotonic()
    count = 0
    failed = 0
    with open(args.output, "a") as out:
        for t in workload["templates"]:
            if only and t["name"] not in only:
                continue
            if sizes and t["size"] not in sizes:
                continue
            for schema in t["schemas"]:
                for index, variables in enumerate(t["variants"]):
                    sql = render(t["sql"], variables)
                    tags = [f"poc_pass={args.pass_name}", f"poc_template={t['name']}", f"poc_variant={index}"]
                    result = gate.run_query(
                        sql,
                        server=args.server.rstrip("/"),
                        predictor=args.predictor.rstrip("/"),
                        catalog=t["catalog"],
                        schema=schema,
                        user=args.user,
                        gate=not args.no_gate,
                        tags=tags,
                        session_properties=session)
                    result.update({
                        "pass": args.pass_name,
                        "template": t["name"],
                        "size": t["size"],
                        "variant": index,
                        "variables": variables,
                    })
                    out.write(json.dumps(result, sort_keys=True) + "\n")
                    out.flush()
                    count += 1
                    failed += result["state"] != "FINISHED"
                    actual = result.get("actual") or {}
                    peak = actual.get("peak_user_memory_bytes") or 0
                    prediction = result.get("prediction") or {}
                    print(f"{t['name']:<12} {schema:<5} v{index} {result['state']:<9} {result['error_code'] or '-':<30} "
                          f"peak={peak / 2**20:8.1f}MB known={prediction.get('known')} req={result['required_workers']} "
                          f"workers={result['workers_before']}->{result['workers_after']} wait={result['wait_ms']}ms "
                          f"wall={result['client_wall_ms'] / 1000:.1f}s {result['query_id']}", flush=True)
    print(f"{count} queries, {failed} not FINISHED, {time.monotonic() - started:.0f}s total; appended to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
