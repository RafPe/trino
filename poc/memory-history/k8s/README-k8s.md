# Memory-history PoC on kind + Helm + KEDA (milestone M7 scaffolding)

A local kind cluster in which KEDA scales the Trino worker Deployment to the number served by
`GET /v1/required-workers` (PLAN.md Contract 3), and Trino holds a query that sets
`required_workers_count` until that many workers have joined.

All scripts use only the kind cluster **`mhpoc`** and the kubeconfig file `k8s/.kubeconfig`
(git-ignored). They never read or change the default kubeconfig. Do the same when you run
commands by hand: `kubectl --kubeconfig poc/memory-history/k8s/.kubeconfig -n trino ...`.

## Files

| File | Purpose |
|---|---|
| `kind-config.yaml` | Single node. Kubelet `syncFrequency: 10s`, so ConfigMap edits (the stub value) reach pods within about 10 s. |
| `env.sh` | Shared settings (names, chart versions, image variables) and the `kc` / `hm` wrappers that pin `--kubeconfig`. |
| `up.sh` | Creates the cluster (node image `kindest/node:v1.34.0`), loads images, installs KEDA, PostgreSQL, the predictor or the stub, and Trino. Safe to run again. |
| `down.sh` | Deletes cluster `mhpoc` only. |
| `demo.sh` | `status`, `set N`, `scale-up`, `scale-down`, `wait-query`, `stub-all` (the stub checks below), `gate` (placeholder for the real-image demo). |
| `values-trino.yaml` | Values for the Trino chart: Contract 5 shape, KEDA `metrics-api` trigger, HPA behavior, graceful shutdown. |
| `values-listener.yaml` | Optional overlay that enables the `memory-history` event listener on the coordinator only. |
| `manifests/postgres.yaml` | PostgreSQL 17 (`postgres:17-alpine`), database `memory_history`, user/password `trino`/`trino`, emptyDir. |
| `manifests/predictor-service.yaml` | Service `predictor:8090`. It selects `app.kubernetes.io/name=predictor`, so it matches either the stub or the real predictor. |
| `manifests/predictor-stub.yaml` | Stub (`python:3.13-alpine`, 18 MB). Serves `{"workers": N, "reservations": 0}`. N comes from ConfigMap `predictor-stub`, key `workers`. |
| `manifests/predictor.yaml` | Real predictor Deployment. `${PREDICTOR_IMAGE}`, env per Contract 3. Not run yet. |

Versions: Trino chart **1.45.0** (appVersion 484), KEDA chart **2.21.0** (KEDA 2.21.0),
Kubernetes 1.34.0 (kind v0.33.0), Helm 4.3.1. `up.sh` installs both charts with
`helm upgrade --install --repo <url> --version <v>`. It does not run `helm repo add`.

## Run

```bash
cd poc/memory-history/k8s

# Stock image + stub (what was verified):
TRINO_IMAGE_REPO=trinodb/trino TRINO_IMAGE_TAG=484 ./up.sh
./demo.sh status
./demo.sh stub-all          # (b), (d), (c), (d) below; about 10 minutes

# Real images (once built by poc/memory-history/docker/build-images.sh):
TRINO_IMAGE_REPO=trino-memory-history TRINO_IMAGE_TAG=poc \
PREDICTOR_IMAGE=<predictor image:tag> LISTENER=1 ./up.sh

./down.sh                   # deletes cluster mhpoc only
```

You can also run `up.sh` against a cluster that already exists. It loads only images whose
local ID changed, deletes the stub when `PREDICTOR_IMAGE` is set (and the real predictor when
it is not), and runs `helm upgrade` on Trino. The Trino pods restart when the image or the
config changes. A second run with nothing changed took 20 s and restarted no pods. Each run
also re-applies `predictor-stub.yaml`, which resets the stub value to 2.

Disk: Docker's data root is `/data/docker`, not `/`. Inside the kind node, the Trino image
takes about 1 GB compressed and about 2.4 GB unpacked.

## Configuration notes

- **Cluster shape (Contract 5):** coordinator and workers have `-Xms4G -Xmx4G`, pod requests of
  500m CPU and 5Gi memory, and a 6Gi memory limit (no CPU limit). Settings:
  `query.max-memory-per-node=1GB`, `query.max-memory=6GB`,
  `node-scheduler.include-coordinator=false`. Catalogs `tpch` and `tpcds`, both with
  `splits-per-node=4`. Workers: 2 to 6.
- **KEDA trigger:** `metrics-api`, `url: http://predictor.{{ .Release.Namespace }}.svc:8090/v1/required-workers`,
  `valueLocation: workers`, `targetValue: "1"`, `metricType: AverageValue`. For an External
  metric with an `AverageValue` target, the HPA computes `desired = ceil(metric / target)`.
  With target 1 this gives `replicas == workers`, which the runs below confirm (2 → 4 → 2,
  and the HPA reports `1/1 (avg)`). No other setting is needed.
- **HPA behavior** (`server.keda.advanced.horizontalPodAutoscalerConfig.behavior`): scale up
  with no stabilization window, at most 10 Pods per 15 s. Scale down with a 120 s
  stabilization window, then 100 % per 15 s.
- **Polling:** KEDA warns that `pollingInterval` (5) and `cooldownPeriod` only matter for
  scale to zero, idle replicas or `useCachedMetrics`. With `minReplicaCount: 2`, the HPA
  queries the metric directly through the KEDA metrics server every 15 s (the default
  `--horizontal-pod-autoscaler-sync-period`). This interval sets the scale-up latency.
- **Graceful shutdown:** `worker.gracefulShutdown` is on, with `gracePeriodSeconds: 30` and
  `terminationGracePeriodSeconds: 300` (the chart requires at least 2 × the grace period; the
  extra time lets long tasks drain). The coordinator gets `shutdown.grace-period=30s` too.
  A removed worker turns inactive in Trino within a few seconds and exits about 60 s later
  (2 × 30 s).
- **Event listener (`values-listener.yaml`):** the chart's `eventListenerProperties` would
  write `event-listener.properties` on the workers too. Contract 2 says the listener runs on
  the coordinator only, so the overlay writes `memory-history-listener.properties` through
  `coordinator.additionalConfigFiles` and sets `event-listener.config-files` in
  `server.coordinatorExtraConfig`. The URL is
  `jdbc:postgresql://postgres.<ns>.svc:5432/memory_history` with user/password `trino`/`trino`.
  Do not use the overlay with the stock image (it has no such plugin).
- **Image loading:** `kind load docker-image` fails for multi-arch images when Docker uses the
  containerd image store (`ctr: content digest ... not found`). `up.sh` streams
  `docker save --platform linux/<arch>` into the node's `ctr images import` instead. It
  labels the node image with the local image ID so that later runs skip unchanged images.
  `imagePullPolicy: Never` everywhere, so nothing is pulled from a registry inside the cluster
  except the KEDA images (about 100 MB).

## Verified evidence (stock `trinodb/trino:484` + stub, 2026-11-12, arm64)

Timings come from `demo.sh`, which polls once per second. The time starts at
`kubectl patch configmap predictor-stub`. The first line includes the kubelet's ConfigMap
propagation delay (up to about 10 s), which the real predictor does not have.

**(a) Trino with 2 workers** (after `up.sh`, about 2.5 min from a cold start, including 1 min to import the image):
```
$ trino --execute "SELECT count(*) FROM system.runtime.nodes WHERE coordinator=false AND state='active'"
2
a6ef5f2a-...  http://10.244.0.10:8080  484  true   active
16ad147c-...  http://10.244.0.11:8080  484  false  active
65dc5fb0-...  http://10.244.0.12:8080  484  false  active
horizontalpodautoscaler.autoscaling/keda-hpa-trino-worker   Deployment/trino-worker   1/1 (avg)   2   6   2
```

**(b) Stub 2 → 4** (`./demo.sh scale-up 4`):
```
12:58:02 setting stub to 4
12:58:06 predictor /v1/required-workers = 4 after 4 s
12:58:08 worker Deployment spec.replicas (HPA) = 4 after 6 s
12:58:10 worker pods created = 4 after 8 s
12:58:10 Running worker pods = 4 after 8 s
12:58:29 active workers in Trino = 4 after 27 s
HPA event: New size: 4; reason: external metric s0-metric-api-workers(...) above target
```
Metric change to HPA decision: about 2 s here (the HPA syncs every 15 s, so it can take up to
15 s). New pods to active Trino workers: about 19 s (JVM start plus node announcement).

**(c) Query waits for workers** (`./demo.sh wait-query 4`, with 2 workers and the stub at 2):
```
13:02:03 active workers: 2; predictor: {"workers": 2, "reservations": 0}
13:02:03 submitting with required_workers_count=4, required_workers_max_wait_time=10m: SELECT count(*) FROM tpch.tiny.nation
13:02:25 after 22 s the query is: 20261112_130205_00019_9fvz4 WAITING_FOR_RESOURCES (CLI still running: yes)
13:02:27 setting stub to 4
13:03:00 active workers in Trino = 4 after 33 s
13:03:00 query finished 57 s after submit (33 s after the stub change); result: 25
20261112_130205_00019_9fvz4  FINISHED  55.036
```

**(d) Stub 4 → 2** (`./demo.sh scale-down 2`, two runs):
```
run 1: predictor=2 after 10 s; HPA replicas=2 after 124 s; non-terminating pods=2 after 129 s;
       active in Trino=2 after 133 s; terminated pods gone after 184 s
run 2: predictor=2 after 6 s;  HPA replicas=2 after 122 s; non-terminating pods=2 after 126 s;
       active in Trino=2 after 134 s; terminated pods gone after 183 s
```
The coordinator log has 0 `ERROR` lines. The worker's `PUT /v1/info/state "SHUTTING_DOWN"`
returned `200 OK`.

**Known warning:** each removed worker gets an event `FailedPreStopHook` (`exited with 137`).
The chart's preStop hook sends the shutdown request and then runs `tail --pid=1 -f /dev/null`.
When Trino finishes its graceful shutdown and exits, the runtime kills the hook, and the kubelet
reports that kill as a failure. The shutdown itself succeeds: HTTP 200, the worker leaves the
active set, and the pod exits about 60 s later instead of at the 300 s SIGKILL. This is chart
behavior; you cannot change it without replacing `worker.lifecycle`, which the chart forbids
together with `gracefulShutdown`.

## Not verified yet

- The real images: `trino-memory-history:poc` with `LISTENER=1`, and the predictor
  Deployment (`manifests/predictor.yaml`). Their manifests render, but they have not run.
- The gate-driven flow (`demo.sh gate` is a placeholder).
- Scaling to 6 workers and memory-heavy queries on kind. All pods share the host's memory:
  7 Trino pods × 6Gi limit = 42Gi, which fits on this host.
