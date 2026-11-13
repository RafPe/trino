"""Runs the lab bench (../bench) in the bench pod of the kind lab, with the kind Service names.

  python /bench/bench_kind.py bench <bench.py arguments>     e.g. bench smoke, bench run --workloads W1
  python /bench/bench_kind.py report <in.jsonl> <out.md>

bench.py and report.py are not changed. This file only replaces their address tables and the
"Not checked" text of the report before it calls their main().
"""
import sys

sys.path.insert(0, "/bench")

KIND_PATHS = {
    "direct": "http://trino-a:8080",
    "gateway-plain": "http://gateway-plain:8080",
    "gateway": "http://gateway:8080",
    "envoy-plain": "http://envoy:10001",
    "envoy-gated": "http://envoy:10000",
    "envoy-first-only": "http://envoy:10002",
}
KIND_COORDINATORS = {"a": "http://trino-a:8080", "b": "http://trino-b:8080"}
KIND_PREDICTORS = {"a": "http://predictor-a:8090", "b": "http://predictor-b:8090"}
KIND_GATE = "http://gate:8095"

NOT_CHECKED_KIND = """### Not checked

- One kind node on one host. Every pod, the client included, runs on the same node.
- Cluster B is only on the routing checks. All timed paths go to cluster A.
- `SELECT 1` is a known query after its first run, so W1 on the gated paths also makes a prediction and a reservation.
- The bench queries need 2 workers, so KEDA does not scale during the bench, and Trino never waits for workers.
- W3 measures the bench client, which parses every page with Python `json`.
- No TLS, no authentication, HTTP/1.1 only. Envoy runs with 4 worker threads, Trino Gateway with its chart defaults and 1 GiB.
"""


def main():
    command = sys.argv[1]
    if command == "bench":
        import bench
        bench.PATHS.clear()
        bench.PATHS.update(KIND_PATHS)
        bench.COORDINATORS.clear()
        bench.COORDINATORS.update(KIND_COORDINATORS)
        bench.PREDICTORS.clear()
        bench.PREDICTORS.update(KIND_PREDICTORS)
        bench.GATE = KIND_GATE
        args = sys.argv[2:]
        if not any(a == "--out" or a.startswith("--out=") for a in args):
            args += ["--out", "/results/bench-kind.jsonl"]
        if not any(a == "--env" or a.startswith("--env=") for a in args):
            args += ["--env", "kind"]
        sys.argv = ["bench.py"] + args
        bench.main()
    elif command == "report":
        import report
        report.NOT_CHECKED = NOT_CHECKED_KIND
        sys.argv = ["report.py"] + sys.argv[2:]
        report.main()
    else:
        sys.exit(f"unknown command {command}")


if __name__ == "__main__":
    main()
