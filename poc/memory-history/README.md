# Memory-history PoC

Predict a repeated query's peak memory from its earlier runs, and use the prediction to add
Trino workers **before** the query runs. No Trino core changes.

- Design and contracts: [PLAN.md](PLAN.md)
- Results of the first full run: [results/REPORT.md](results/REPORT.md)
- Cluster calibration: [workload/CALIBRATION.md](workload/CALIBRATION.md)
- Kubernetes details: [k8s/README-k8s.md](k8s/README-k8s.md)

## How it works

```
client ──▶ gate.py ──(1) POST /v1/predict ─────────▶ predictor ──reads──▶ PostgreSQL (query_history)
              │       (2) POST /v1/reservations ───▶    │  ▲                     ▲
              │                                         │  └─ GET /v1/required-workers ◀── KEDA (k8s) or scaler.py (compose)
              │ (3) submit with session property        │                        │ writes
              ▼     required_workers_count=N            │                        │
        Trino coordinator ── waits for N workers ── runs query ── QueryCompletedEvent ──▶ memory-history event listener
```

| Part | Where | What it does |
|---|---|---|
| Fingerprint | `plugin/trino-memory-history` (`QueryFingerprint`) | SQL → hash. Literals become `?`, case/whitespace/comments ignored, tables included. |
| Event listener | `plugin/trino-memory-history` | On every completed query: writes fingerprint, peak memory, state and error code to PostgreSQL. |
| Predictor | `service/trino-memory-history-predictor` | `POST /v1/predict` → predicted peak and required workers. Holds reservations. `GET /v1/required-workers` for the scaler. |
| Gate | `gate/gate.py` | Client-side. Asks the predictor, reserves, submits the query with `required_workers_count` so Trino itself waits. |
| Scaler | KEDA `metrics-api` trigger (k8s) or `gate/scaler.py` (compose) | Sets the number of workers to what the predictor reports. |

Prediction rule: the largest peak among the last 10 successful runs of the fingerprint. If a run
failed with a memory error and its peak is higher than any success, the prediction is that peak × 2.
Required workers = `ceil(predicted × 1.5 / query.max-memory-per-node)`, between 2 and 6.
An unknown fingerprint gets no prediction and runs unchanged.

## Prerequisites

Docker, Python 3, JDK 25 and `mvnd` (or `./mvnw`). For the Kubernetes demo also `kind`, `kubectl`, `helm`.
About 8 GB of free disk for images. The stack uses up to 7 containers with 4 GB heap each.

All commands below start from `poc/memory-history/`.

## 1. Build the images

```bash
docker/build-images.sh
# -> trino-memory-history:poc             (trinodb/trino:484 + the plugin)
# -> trino-memory-history-predictor:poc
```

Unit tests (optional, need Docker for Testcontainers):

```bash
(cd ../.. && mvnd -pl plugin/trino-memory-history,service/trino-memory-history-predictor test -Dair.check.skip-all=true)
```

## 2. Python environment

```bash
python3 -m venv .venv && .venv/bin/pip install -r gate/requirements.txt
```

If `python3 -m venv` fails because `ensurepip` is missing, create it with
`python3 -m venv --without-pip .venv` and install pip with `get-pip.py`.

## 3. Run on docker compose

```bash
cd compose
DC="docker compose -f docker-compose.yml -f docker-compose.full.yml --profile full"
$DC up -d --scale worker=2          # coordinator :18080, predictor :18090, postgres :15432
cd ..
```

Cluster: 4 GB heap per node, `query.max-memory-per-node=1GB`, `query.max-memory=6GB`,
coordinator runs no queries. Ports and limits are in `compose/.env`.

### 3a. One query by hand

```bash
Q="SELECT count(*) FROM orders WHERE orderkey > 100"
.venv/bin/python gate/gate.py --catalog tpch --schema tiny -e "$Q"      # first run: unknown, runs unchanged
.venv/bin/python gate/gate.py --catalog tpch --schema tiny -e "${Q/100/7}"   # other literal: known
curl -s -X POST localhost:18090/v1/predict -H 'Content-Type: application/json' \
     -d "{\"sql\": \"$Q\", \"catalog\": \"tpch\", \"schema\": \"tiny\"}"
docker exec mhpoc-postgres-1 psql -U memoryhistory -d memoryhistory -c \
  "select left(fingerprint,12) fp, query_state, error_code, peak_user_memory_bytes, tables from query_history order by end_time desc limit 5"
```

### 3b. The full loop: learn, then predict

```bash
# Pass 1: no gate, 2 workers. Heavy queries fail with EXCEEDED_LOCAL_MEMORY_LIMIT. Everything is recorded.
.venv/bin/python workload/run_workload.py --pass learn --no-gate

# Start the scaler (stands in for KEDA) in a second terminal:
.venv/bin/python gate/scaler.py --compose-arg=-f --compose-arg=docker-compose.yml \
    --compose-arg=-f --compose-arg=docker-compose.full.yml --compose-arg=--profile --compose-arg=full

# Pass 2 and 3: through the gate. Heavy queries get more workers first and finish.
.venv/bin/python workload/run_workload.py --pass predict
.venv/bin/python workload/run_workload.py --pass predict

.venv/bin/python workload/report.py       # writes results/REPORT.md
```

One pass takes about 9 minutes (13 query templates × 3 literal variants; `workload/workload.yaml`).
Use `--only tpch_q21` or `--size heavy` to run a part.

Stop: `cd compose && $DC down` (add `-v` to also delete the history).

## 4. Run on Kubernetes (kind + Helm + KEDA)

```bash
cd k8s
TRINO_IMAGE_REPO=trino-memory-history TRINO_IMAGE_TAG=poc \
PREDICTOR_IMAGE=trino-memory-history-predictor:poc LISTENER=1 ./up.sh
./demo.sh status
./demo-gate.sh          # heavy query: fails without the gate, then scales up and finishes with it
./down.sh               # deletes only the kind cluster "mhpoc"
```

`up.sh` creates a kind cluster named `mhpoc` with its own kubeconfig (`k8s/.kubeconfig`); it does
not touch the default kubeconfig or other clusters. KEDA reads `GET /v1/required-workers` from the
predictor every 5 s and sets the worker Deployment's replicas to that number (2 to 6).

## What the first full run showed

Compose stack, real plugin and predictor, 117 workload runs. Details: [results/REPORT.md](results/REPORT.md).

| Question | Result |
|---|---|
| Do literal variants share one fingerprint? | Yes: 13 templates → 13 fingerprints, 3 variants each. |
| Hit rate in the gated passes | 78 of 78 (every query shape repeats in this workload). |
| Prediction accuracy, peak ≥ 100 MB, predicted from successful runs | predicted / actual: median 1.05, p90 1.09, range 0.97–1.17 (31 runs). **In-sample:** the same literals had already run. |
| The same, predicting each run only from the *other* literal variants of its shape | median 1.04, p90 1.08, range 0.96–1.17 (45 runs). |
| Prediction accuracy, predicted from a memory failure | median 1.99 (range 1.04–2.14, 5 runs): over-provisions once, then corrects itself. |
| Heavy queries failing ungated on 2 workers | 9 of 9 variants (TPC-H q09, q18, q21 at sf5). |
| The same queries through the gate | 9 of 9 variants finished (18 of 18 gated heavy runs). Only 3 of those 18 runs triggered a scale-up themselves (3→4, 3→6, 4→6 workers) and waited about 20 s in Trino; the other 15 found enough workers still running from the query before, because scale-down waits 120 s. |
| Memory spread across literal variants of one shape (≥ 100 MB) | max/min 1.02–1.17. |

### On Kubernetes (kind, real images)

`./demo-gate.sh` with TPC-H q21 at sf5. Run log: `results/k8s-runs.jsonl`, worker timeline: `results/k8s-workers.log`.

| Step | Result |
|---|---|
| 3 variants without the gate, 2 workers | all 3 failed with `EXCEEDED_LOCAL_MEMORY_LIMIT` (peak reached 1.6–1.8 GB) |
| Variant 0 through the gate | predicted 3.7 GB from the failures → 6 workers requested; KEDA had 6 pods running in about 14 s; Trino saw 6 active workers about 35 s after the request; the query waited 30 s and finished (peak 3.4 GB, 51 s in total) |
| Variants 1 and 2 through the gate | predicted 3.6 GB from the successful run, actual 3.5 GB; no wait, finished in 12–13 s |
| After the last query | reservation released, required workers back to 2 |

## Known limits

- **Only repeated queries.** A new query shape runs unchanged and can fail; its failure teaches the next run.
- **Text fingerprint.** Literals that change the data volume (a 1-day vs a 1-year range) share one fingerprint. The workload's literals did not change memory much; real workloads may.
- **No per-node peak.** Trino reports a cluster-wide peak and the largest single task, not a per-node peak. Workers are computed as total / per-node limit with a 1.5 safety factor. Skewed data or broadcast joins need more (TPC-H q18 needs 3 workers at 1.7 GB).
- **Scale-down can kill queries on compose.** `scaler.py` removes worker containers abruptly; one small query failed with `PAGE_TRANSPORT_TIMEOUT` in the first run. The Kubernetes setup uses Trino's graceful worker shutdown.
- **Small queries are noisy.** Peaks under about 10 MB vary from bytes to megabytes between runs. They do not affect scaling.
- **Long queries can outlive their reservation.** The reservation lasts 1 hour and is not refreshed.
- **The gate is client-side.** Clients that do not use it get no prediction. Reservations live in the predictor's memory and are lost on restart.
- **Monitoring queries are recorded too** (for example the gate's own `system.runtime.nodes` query).
- **A failed run's peak is only a lower bound.** q21 had reached 1.7 GB when it failed on 2 workers and used 3.3 GB when it finished; the × 2 growth factor happened to cover that.
- **Cluster size is not part of the history.** Runs on 2, 4 and 6 workers are mixed. Whether the same query's peak depends on the worker count was not analysed.

## Layout

```
plugin/trino-memory-history/               fingerprint, event listener, PostgreSQL store (Java)
service/trino-memory-history-predictor/    predictor HTTP service (Java)
poc/memory-history/
  docker/      image builds
  compose/     docker compose cluster
  gate/        gate.py, scaler.py (mock_predictor.py is test-only)
  workload/    queries, workload.yaml, run_workload.py, report.py, CALIBRATION.md
  k8s/         kind + Helm + KEDA
  results/     REPORT.md and the run log of the first full run
```
