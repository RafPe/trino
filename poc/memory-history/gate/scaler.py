#!/usr/bin/env python3
"""Compose-only scaler for the memory-history PoC (Contract 4). Stands in for KEDA.

Every --interval seconds it reads GET /v1/required-workers from the predictor
and runs `docker compose up -d --no-recreate --scale worker=<N>`.
It scales up at once. It scales down only after the wanted value has been lower
than the current target for --down-delay seconds (default 120).

  python scaler.py --predictor http://localhost:18090 --compose-dir ../compose
  python scaler.py ... --compose-arg=-f --compose-arg=docker-compose.yml \
                       --compose-arg=-f --compose-arg=docker-compose.full.yml
"""

import argparse
import os
import subprocess
import sys
import time
from datetime import datetime

import requests

HERE = os.path.dirname(os.path.abspath(__file__))


def log(message):
    print(f"{datetime.now().isoformat(timespec='seconds')} scaler: {message}", flush=True)


def compose(args, *extra):
    cmd = ["docker", "compose", *args.compose_arg, *extra]
    return subprocess.run(cmd, cwd=args.compose_dir, capture_output=True, text=True)


def running_workers(args):
    r = compose(args, "ps", "-q", "--status", "running", "worker")
    return len([line for line in r.stdout.splitlines() if line.strip()]) if r.returncode == 0 else None


def scale(args, workers):
    r = compose(args, "up", "-d", "--no-recreate", "--scale", f"worker={workers}")
    if r.returncode != 0:
        log(f"docker compose failed: {r.stderr.strip()}")
    return r.returncode == 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--predictor", default="http://localhost:18090")
    p.add_argument("--compose-dir", default=os.path.join(HERE, "..", "compose"))
    p.add_argument("--compose-arg", action="append", default=[], help="extra argument for `docker compose` (repeatable)")
    p.add_argument("--interval", type=float, default=5.0)
    p.add_argument("--down-delay", type=float, default=120.0)
    p.add_argument("--once", action="store_true", help="one poll, then exit (for tests)")
    args = p.parse_args()

    target = None          # last worker count we applied
    lower_since = None     # when the wanted value first dropped below target
    while True:
        try:
            r = requests.get(f"{args.predictor.rstrip('/')}/v1/required-workers", timeout=5)
            r.raise_for_status()
            wanted = int(r.json()["workers"])
            reservations = r.json().get("reservations")
        except (requests.RequestException, ValueError, KeyError) as e:
            log(f"predictor unavailable: {e}")
            wanted = None

        if wanted is not None:
            if target is None:
                target = running_workers(args) or wanted
                log(f"start: running={target}")
            now = time.monotonic()
            new_target = target
            if wanted > target:
                new_target = wanted
                lower_since = None
            elif wanted < target:
                if lower_since is None:
                    lower_since = now
                    log(f"wanted={wanted} < target={target}; scale down in {args.down_delay:.0f}s if it stays lower")
                elif now - lower_since >= args.down_delay:
                    new_target = wanted
                    lower_since = None
            else:
                lower_since = None

            running = running_workers(args)
            if new_target != target or (running is not None and running != new_target):
                log(f"scale worker={new_target} (wanted={wanted}, reservations={reservations}, running={running})")
                if scale(args, new_target):
                    target = new_target

        if args.once:
            return 0
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
