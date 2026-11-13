# kind lab notes (milestones L5 and L6)

This is the kind environment of the lab (LAB-PLAN.md, "Two environments"). Phase 1 (L5) has two Trino
clusters behind Trino Gateway, one predictor for each cluster, and KEDA for each cluster.
Phase 2 (L6) adds the gate, external routing in `gateway`, a second gateway `gateway-plain`, Envoy,
a bench pod, `demo-heavy.sh` and `bench-kind.sh`. The timed bench (L7) is not run yet.

All scripts use only the kind cluster `mhlab` and the kubeconfig file `lab/k8s/.kubeconfig`
(git-ignored). They never read or change the default kubeconfig. By hand:
`kubectl --kubeconfig poc/memory-history/lab/k8s/.kubeconfig -n lab ...`.

## Start and stop

```bash
cd poc/memory-history/lab/k8s
./up.sh              # create or update the full lab; safe to run again
./status.sh          # pods, ScaledObjects, HPAs, active workers, predictor values, backends, gate counters
./demo-heavy.sh      # milestone L6, about 6 to 8 minutes
./failopen-check.sh  # gate at 0 replicas: queries through gateway and envoy-gated still run
./bench-kind.sh      # milestone L7 (timed bench); QUICK=1 for a short functional check
./down.sh            # deletes cluster mhlab only; the local images stay
```

`up.sh` needs these local images: `trino-memory-history:poc`, `trino-memory-history-predictor:poc`,
`trino-memory-history-gate:lab` (`../gate/build.sh`), `trinodb/trino-gateway:22`, `envoyproxy/envoy:v1.35.3`,
`postgres:17-alpine`, `python:3.13-alpine`. `GATEWAY_EXTERNAL=0 ./up.sh` installs `gateway` without the
gate (phase 1 behaviour).

- `up.sh` stops if `/data` has less than 12 GB free. It checks before and after the image load, and at the end.
- A cold start took about 4 minutes. Cluster creation took 43 s. The image load took 57 s
  (Trino 32 s, predictor 11 s, PostgreSQL 7 s, Trino Gateway 7 s). KEDA took 36 s. The Trino
  pods were ready about 25 s after the Helm install.
  My first run stopped at the backend registration, because the gateway Service had no ready
  endpoint yet. Now `up.sh` waits for the gateway API. The second run completed. I did not time a
  full cold start again after that fix.
- A run with no changes took 23 s and restarted no pods.
- When a local image changes but keeps its tag, `up.sh` loads it again and restarts the pods that
  use it. I saw this happen: the predictor image was rebuilt during my work, and the second run
  restarted `predictor-a` and `predictor-b` only.

## What runs where

Namespace `lab`, one kind node (`kindest/node:v1.34.0`). Namespace `keda`: KEDA.

| Part | Name (Service:port) | Source | Notes |
|---|---|---|---|
| PostgreSQL | `postgres:5432` | `manifests/postgres.yaml` | Databases `history_a`, `history_b`, `gateway`, `gateway_plain` (made by `up.sh`). User `trino`/`trino`. emptyDir: no data is kept. |
| Cluster A | `trino-a:8080` | chart `trino` 1.45.0, release `trino-a` | 1 coordinator, 2 to 6 workers. Listener writes to `history_a`. |
| Cluster B | `trino-b:8080` | chart `trino` 1.45.0, release `trino-b` | The same, `history_b`. |
| Predictor A | `predictor-a:8090` | `manifests/predictor.yaml` | Reads `history_a`. `MIN_WORKERS=2`, `MAX_WORKERS=6`, `SAFETY_FACTOR=1.5`, `PER_WORKER_MEMORY_BYTES=1073741824`. |
| Predictor B | `predictor-b:8090` | the same file | Reads `history_b`. |
| Gate | `gate:8095` | `manifests/gate.yaml` | `GATE_GROUPS=a=http://predictor-a:8090,b=http://predictor-b:8090`, default group `a`, predictor timeout 200 ms. |
| Trino Gateway | `gateway:8080` | chart `trino-gateway` 1.22.0 (Trino Gateway 22), release `gateway` | External routing to `http://gate.lab.svc:8095/gateway/route`. Database `gateway`. Backends `trino-a` (group `a`) and `trino-b` (group `b`). |
| Trino Gateway, baseline | `gateway-plain:8080` | the same chart, release `gateway-plain` | No routing rules. Routes by `X-Trino-Routing-Group`, default `a`. Own database `gateway_plain`, same two backends. |
| Envoy | `envoy:10000` (gated), `:10001` (plain), `:10002` (first-only), `:9901` (admin) | `manifests/envoy.yaml`, config `envoy.yaml` | v1.35.3, all listeners to `trino-a`. ext_authz to `gate.lab.svc.cluster.local:8095`, prefix `/envoy/check/a`. |
| Client | no Service | `manifests/client.yaml` | Trino image with `sleep`. I use it for curl and HTTP tests inside the cluster. |
| Bench | no Service | `manifests/bench.yaml` | `python:3.13-alpine` with `sleep`. ConfigMap `bench-code` in `/bench`: `../bench/bench.py`, `../bench/report.py`, `bench_kind.py`, `heavy.py`. Results in `/results` (emptyDir). |
| KEDA | namespace `keda` | chart `keda` 2.21.0 | One ScaledObject for each worker Deployment. |

Files in `lab/k8s/`:

| File | Purpose |
|---|---|
| `env.sh` | Names, versions, the `kc`/`hm` wrappers that pin the kubeconfig, and small helpers. |
| `kind-config.yaml` | One node. Kubelet `syncFrequency: 10s`. |
| `up.sh`, `down.sh`, `status.sh` | See above. |
| `values-trino.yaml` | Shared Trino values. The memory numbers are the same as in the PoC. Also `http-server.process-forwarded=true`, tpch and tpcds catalogs, graceful worker shutdown, KEDA limits and HPA behavior. |
| `values-trino-a.yaml`, `values-trino-b.yaml` | `fullnameOverride`, the KEDA `metrics-api` trigger to the own predictor, and the listener file to the own database. |
| `values-gateway.yaml` | Trino Gateway base values: no routing rules, `defaultRoutingGroup: a`, `monitorType: INFO_API`, health check every 10 s. |
| `values-gateway-external.yaml` | On top of the base for release `gateway`: external routing to the gate (contract L3). Applied by default. |
| `values-gateway-plain.yaml` | On top of the base for release `gateway-plain`: own Service name and own database. |
| `envoy.yaml` | Envoy configuration. The same as `../compose/envoy/envoy.yaml` with the kind Service names (see below). |
| `manifests/gate.yaml`, `manifests/envoy.yaml`, `manifests/bench.yaml` | Gate, Envoy and bench pod. |
| `bench_kind.py` | Runs `../bench/bench.py` and `../bench/report.py` unchanged, with the kind addresses and a kind "Not checked" text. |
| `heavy.py` | Runs in the bench pod. One query with full timing and session properties from `/v1/query/<id>`; a sample of active workers and predictor values. |
| `demo-heavy.sh` | Milestone L6. Writes `../results/demo-heavy-kind-runs.jsonl` and `../results/demo-heavy-kind-timeline.log`. |
| `failopen-check.sh` | The gate at 0 replicas, then back at 1. |
| `bench-kind.sh` | Milestone L7: the bench in the bench pod, results copied to `../results/`. |
| `query.sh` | Runs one query with the REST protocol through a URL, with an optional `X-Trino-Routing-Group`. It prints the query id and the host of the first `nextUri`. |
| `keda-check.sh` | Adds a reservation on one predictor, records both clusters while they scale, then deletes it. |

Configuration details:

- **Listener on the coordinator only.** As in the PoC, I write `memory-history-listener.properties`
  through `coordinator.additionalConfigFiles` and set `event-listener.config-files` in
  `server.coordinatorExtraConfig`. The chart's `eventListenerProperties` would also go to the workers.
- **Resource names.** With release `trino-a`, the chart names everything `trino-a-trino-...`.
  `fullnameOverride: trino-a` gives `trino-a`, `trino-a-coordinator` and `trino-a-worker`.
- **KEDA.** The same as the PoC: `metrics-api`, `valueLocation: workers`, `targetValue: "1"`,
  `AverageValue`, so replicas equal the predictor value. Scale-up without a stabilization window
  (10 Pods per 15 s), scale-down after a 120 s window. KEDA warns that `pollingInterval` and
  `cooldownPeriod` have no effect with `minReplicaCount: 2`. The HPA reads the metric every 15 s.
- **Images.** `up.sh` streams `docker save --platform linux/arm64` into `ctr images import` on the
  node, because `kind load docker-image` fails for the multi-arch Trino image. All parts use
  `imagePullPolicy: Never`. Only the KEDA images come from a registry.
- **Gateway backends** are rows in the `gateway` database. PostgreSQL keeps no data, so `up.sh`
  registers them on each run (`/gateway/backend/modify/add`, or `/modify/update` if the name
  exists). After a PostgreSQL restart, run `up.sh` again.
- **Gateway resources.** I set 1 GiB (request and limit) instead of the chart default of 2 CPU
  and 4 GiB. The chart sets the heap to 80 % of the limit.

## Phase 2: design decisions

- **External routing.** `up.sh` now adds `values-gateway-external.yaml` to release `gateway` by default.
  It sets `routingRules.rulesEngineEnabled: true`, `rulesType: EXTERNAL`,
  `rulesExternalConfiguration.urlPath: http://gate.lab.svc:8095/gateway/route`, `excludeHeaders: []`,
  `propagateErrors: false`, `requestAnalyzerConfig.analyzeRequest: true` and
  `serverConfig.router.http-client.request-timeout: 1s`. Contract L3 does not name `rulesEngineEnabled`.
  Trino Gateway 22 uses routing rules only when that value is `true`, so I added it.
- **Two gateways, two databases.** `gateway-plain` is a second release of the same chart with the same base
  values. I gave it its own database `gateway_plain`. The compose lab shares one database. In kind I chose
  separate databases, because then the two gateways cannot touch each other's backend rows or query-id rows,
  and they never run the schema migration on the same database at the same time. The cost is one more
  registration loop in `up.sh`. After my runs, `gateway.query_history` had 167 rows and
  `gateway_plain.query_history` had 113 rows, each with its own queries only.
- **Envoy addresses.** The cluster `trino_a` is `trino-a.lab.svc.cluster.local:8080` and the cluster `gate` is
  `gate.lab.svc.cluster.local:8095` (full names, so the DNS search list does not matter). On `first-only` the
  upstream `Host` is `trino-a:8080`, so `nextUri` points at the coordinator Service. All other settings are
  the compose file unchanged (ext_authz only on `POST /v1/statement`, body included, `x-trino-` headers
  allowed, `x-trino-session` appended, `failure_mode_allow: true`, 250 ms).
- **Bench in the cluster.** `bench.py` has the compose host names in a table. I did not change it.
  `bench_kind.py` replaces the tables before it calls `main()`:

  | Path | kind URL |
  |---|---|
  | direct | `http://trino-a:8080` |
  | gateway-plain | `http://gateway-plain:8080` |
  | gateway | `http://gateway:8080` |
  | envoy-plain | `http://envoy:10001` |
  | envoy-gated | `http://envoy:10000` |
  | envoy-first-only | `http://envoy:10002` |

  The coordinators are `trino-a:8080`/`trino-b:8080`, the predictors and the gate keep their compose names.
  `report.py` has a compose-specific "Not checked" text; `bench_kind.py report` replaces it with a kind text.
- **Warm-up.** `up.sh` sends one `POST /v1/gate` to each predictor with a query that never runs, so no
  reservation is made and the first real query does not lose its properties to the 200 ms timeout.
- **Rollouts.** The Envoy and bench pods carry a checksum of their ConfigMap, so a changed configuration
  or changed bench code restarts them.

## Proof of L5 (2026-11-13, arm64)

### Two workers in each cluster

```
== trino-a  (SELECT node_id, http_uri, node_version, coordinator, state FROM system.runtime.nodes)
0af75682-40eb-47dc-b4a9-f2c50288dfda  http://10.244.0.19:8080  484  true   active
552cd51a-d400-46fe-8d7f-d2e652f0fff7  http://10.244.0.25:8080  484  false  active
f062d35d-e7e2-430c-af4a-d1b4a3317072  http://10.244.0.20:8080  484  false  active
== trino-b
74302791-2c4a-484f-ac0b-5a8192930680  http://10.244.0.21:8080  484  true   active
45981c8f-0ecc-453c-b5a9-daf888af883f  http://10.244.0.22:8080  484  false  active
17a1ca22-5cf8-402a-bb53-e0748ce5f9d3  http://10.244.0.24:8080  484  false  active
```

### Routing by header through the gateway Service

`query.sh` sends the requests with curl from the client pod to `http://gateway:8080`.

```
$ ./query.sh http://gateway:8080 a "SELECT 'routed-a', count(*) FROM nation"
queryId=20261113_095116_00007_qbfvt firstNextUriHost=http://gateway:8080
[["routed-a",25]]
state=FINISHED
$ ./query.sh http://gateway:8080 b "SELECT 'routed-b', count(*) FROM region"
queryId=20261113_095119_00006_jbh2t firstNextUriHost=http://gateway:8080
[["routed-b",5]]
state=FINISHED
$ ./query.sh http://gateway:8080 - "SELECT 'routed-default', count(*) FROM customer"   # no header
queryId=20261113_095122_00008_qbfvt firstNextUriHost=http://gateway:8080
[["routed-default",1500]]
state=FINISHED
```

`nextUri` points at the gateway, so `http-server.process-forwarded=true` works and all requests of
each query went through the gateway.

`system.runtime.queries` on each coordinator:

```
== trino-a
20261113_095116_00007_qbfvt  FINISHED  SELECT 'routed-a', count(*) FROM nation
20261113_095122_00008_qbfvt  FINISHED  SELECT 'routed-default', count(*) FROM customer
== trino-b
20261113_095119_00006_jbh2t  FINISHED  SELECT 'routed-b', count(*) FROM region
```

The `query_history` table of Trino Gateway (database `gateway`) agrees:

```
20261113_095122_00008_qbfvt | http://trino-a.lab.svc:8080 | a
20261113_095119_00006_jbh2t | http://trino-b.lab.svc:8080 | b
20261113_095116_00007_qbfvt | http://trino-a.lab.svc:8080 | a
```

### History rows in the right database

```
== history_a
20261113_095116_00007_qbfvt | FINISHED | select ? , count ( * ) from nation
20261113_095122_00008_qbfvt | FINISHED | select ? , count ( * ) from customer
== history_b
20261113_095119_00006_jbh2t | FINISHED | select ? , count ( * ) from region
```

None of these query ids is in the other database.

### KEDA for each cluster

`./keda-check.sh a 3400000000` (the log is in `lab/k8s/evidence/keda-check-a.log`, git-ignored).
The predictor asks for ceil(3.4e9 × 1.5 / 1 GiB) = 5 workers. One sample takes 5 to 10 s, because
each one runs several `kubectl exec` calls. So the times below are accurate to about 7 s.

```
09:52:11 POST reservation (3400000000 bytes) on predictor-a -> 201; predictor-a now asks for 5 workers
09:52:15 t=4s   a: predictor=5 hpa=2 pods=2 active=2 | b: predictor=2 hpa=2 pods=2 active=2
09:52:22 t=11s  a: predictor=5 hpa=5 pods=5 active=2 | b: predictor=2 hpa=2 pods=2 active=2
09:52:35 t=24s  a: predictor=5 hpa=5 pods=5 active=5 | b: predictor=2 hpa=2 pods=2 active=2
09:53:18 t=67s  a: predictor=5 hpa=5 pods=5 active=5 | b: predictor=2 hpa=2 pods=2 active=2
09:53:18 DELETE reservation -> 204
09:53:22 t=4s   a: predictor=2 hpa=5 pods=5 active=5 | b: ...=2
09:55:16 t=118s a: predictor=2 hpa=5 pods=5 active=5 | b: ...=2
09:55:25 t=127s a: predictor=2 hpa=2 pods=2 active=2 | b: predictor=2 hpa=2 pods=2 active=2
```

- Scale-up of `trino-a`: 2 → 5. The HPA changed within 11 s. All 5 workers were active in Trino
  after 24 s.
- `trino-b` stayed at 2 the whole time (predictor, HPA, pods and active workers). Its HPA has no
  rescale event after the first one at install time.
- Scale-down: the HPA went to 2 between 118 s and 127 s after the delete (event
  `09:55:13 New size: 2; reason: All metrics below target`, 115 s after the delete). This agrees
  with the 120 s window. The 3 removed pods left the active set at once and were gone at 09:56:13,
  about 60 s later (2 × 30 s grace period).
- Each removed worker got a `FailedPreStopHook` event. This is the known chart behavior from the
  PoC (the hook is killed when Trino exits). The shutdown itself works.

### End state

`mhlab` runs: 2 workers in each cluster, both predictors at 2 with 0 reservations, both backends active.

## Proof of L6 (2026-11-13, arm64)

I ran `demo-heavy.sh` twice. Both runs gave the same outcomes. The numbers below are from the second run
(10:32 to 10:38 UTC). Its files are `results/demo-heavy-kind-runs.jsonl` and
`results/demo-heavy-kind-timeline.log`. The first run (10:24 to 10:30) is in `lab/k8s/evidence/`
(git-ignored). Between the two runs, `up.sh` loaded a newer predictor image and a newer gate image that
were built on the host during my work, so the second run used them.

The query is TPC-H q21 on `tpch.sf5`. The client sends `X-Trino-Session: join_distribution_type=PARTITIONED,task_concurrency=4`.
Each run gives the count column a new alias (`numwait_r<epoch>`). This makes a new fingerprint, so
both predictors start without history for it. Step 1 of the second run showed `runs: 1` on
predictor-a, so the alias does change the fingerprint.

### Steps 1 to 4

```
step1-direct      20261113_103202_01042_qbfvt  FAILED   EXCEEDED_LOCAL_MEMORY_LIMIT  peak 1.83 GiB  9.5 s
predictor-a now: {"known":true,"memoryFailures":1,"predictedPeakGiB":3.65,"requiredWorkers":6,"basis":"memory_failure_growth"}
predictor-b now: {"known":false,"requiredWorkers":null}
step2-gateway-a   20261113_103214_01045_qbfvt  FINISHED  required_workers_count=6  waited 22.61 s  elapsed 49.1 s  peak 3.31 GiB
step3-envoy-gated 20261113_103510_01099_qbfvt  FINISHED  required_workers_count=5  waited 26.20 s  elapsed 56.2 s  peak 3.30 GiB
step4-gateway-b   20261113_103611_00249_jbh2t  FAILED   EXCEEDED_LOCAL_MEMORY_LIMIT  peak 1.73 GiB  8.8 s (no gate properties)
predictor-b now: {"known":true,"memoryFailures":1,"predictedPeakGiB":3.46,"requiredWorkers":6,"basis":"memory_failure_growth"}
gate stats after: {"known":4,"unknown":1,"requests":5,"predictor_errors":0,"predictor_timeouts":0,...}
```

The gate counters include the two queries of `failopen-check.sh`, which ran just before.

- Step 3 asked for 5 workers, not 6, because the prediction now came from the successful run of step 2
  (3.31 GiB × 1.5 / 1 GiB = 4.97 → 5).
- Step 4 ran on cluster B unchanged and failed, as expected for a shape that cluster B does not know.
  After it, predictor-b knows the shape. The HPA of `trino-b` has no event after its install in phase 1.
- First run: the same four outcomes. Step 2 and step 3 both asked for 6 workers and waited 23.41 s and 24.21 s.

### Session properties in the history databases

```
== history_a
20261113_103202_01042_qbfvt | FAILED   | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.83 | {"join_distribution_type":"PARTITIONED","task_concurrency":"4"}
20261113_103214_01045_qbfvt | FINISHED | -                           | 3.31 | {"join_distribution_type":"PARTITIONED","required_workers_count":"6","required_workers_max_wait_time":"10m","task_concurrency":"4"}
20261113_103510_01099_qbfvt | FINISHED | -                           | 3.30 | {"join_distribution_type":"PARTITIONED","required_workers_count":"5","required_workers_max_wait_time":"10m","task_concurrency":"4"}
== history_b
20261113_103611_00249_jbh2t | FAILED   | EXCEEDED_LOCAL_MEMORY_LIMIT | 1.73 | {"join_distribution_type":"PARTITIONED","task_concurrency":"4"}
```

The gateway row (`…01045`) and the Envoy row (`…01099`) have both gate properties and both client
properties. None of the four ids is in the other database.

### Worker timeline (a sample every 5 to 8 s; `resv` = live reservations)

```
10:32:09 a: predictor=2 resv=0 hpa=2 ready_pods=2 active=2 | b: predictor=2 resv=0 hpa=2 ready_pods=2 active=2
10:32:15 a: predictor=6 resv=1 hpa=6 ready_pods=2 active=2 | b: 2 0 2 2 2   step 2 sent at 10:32:14
10:32:34 a: predictor=6 resv=1 hpa=6 ready_pods=6 active=2 | b: 2 0 2 2 2
10:32:42 a: predictor=6 resv=1 hpa=6 ready_pods=6 active=6 | b: 2 0 2 2 2
10:33:04 a: predictor=2 resv=0 hpa=6 ready_pods=6 active=6 | b: 2 0 2 2 2   step 2 ended 10:33:03, released
10:35:04 a: predictor=2 resv=0 hpa=2 ready_pods=2 active=6 | b: 2 0 2 2 2   scale-down (120 s window)
10:35:10 a: predictor=2 resv=0 hpa=2 ready_pods=2 active=2 | b: 2 0 2 2 2   step 3 sent at 10:35:10
10:35:17 a: predictor=5 resv=1 hpa=5 ready_pods=2 active=2 | b: 2 0 2 2 2
10:35:36 a: predictor=5 resv=1 hpa=5 ready_pods=5 active=2 | b: 2 0 2 2 2
10:35:46 a: predictor=5 resv=1 hpa=5 ready_pods=5 active=5 | b: 2 0 2 2 2
10:36:13 a: predictor=2 resv=0 hpa=5 ready_pods=5 active=5 | b: 2 0 2 2 2   step 3 ended 10:36:07, released
10:38:08 idle: a and b at 2 active workers, predictor 2, 0 reservations
```

Cluster B stayed at predictor 2, HPA 2, 2 ready pods and 2 active workers in all 58 samples of the run.
The sampler had one gap of 48 s (10:33:16 to 10:34:04), in the idle time between step 2 and step 3.

### Timings (metric change → pods → active workers → query start)

The HPA time is from its event, the pod times from the pod status, the query times from `/v1/query/<id>`.
"All workers active" is the interval between two timeline samples, so it is accurate to about 7 s.

| | run 1 step 2 | run 1 step 3 | run 2 step 2 | run 2 step 3 |
|---|---|---|---|---|
| query created (gate call, reservation) | 10:24:58.2 | 10:27:57.5 | 10:32:14.2 | 10:35:10.9 |
| HPA new size | 10:24:59 (6) | 10:28:00 (6) | 10:32:15 (6) | 10:35:15 (5) |
| new pods Ready | 10:25:12 | 10:28:14 | 10:32:31–32 | 10:35:28–30 |
| all workers active in Trino | 10:25:19–27 | 10:28:21–28 | 10:32:34–42 | 10:35:36–46 |
| query execution start | 10:25:21.6 | 10:28:21.7 | 10:32:36.8 | 10:35:37.1 |
| wait for workers | 23.4 s | 24.2 s | 22.6 s | 26.2 s |

- The HPA reacted 1 to 4 s after the reservation. The HPA reads the metric every 15 s, so up to about 15 s
  is possible. I did not see that case.
- New pod to Ready took 13 to 17 s. Ready to active in Trino took a few more seconds. Trino started
  the query 22.6 to 26.2 s after it arrived.
- Reservations went back to 0 within one timeline sample (≤ 7 s) after each query ended.
- In run 1, step 3 started 13 s after the HPA had scaled cluster A down to 2. The 4 removed workers were
  still in their graceful shutdown. KEDA then made 4 new pods; the old ones were not reused.

### Fail open (`failopen-check.sh`, 10:31:47)

```
gate scaled to 0; gate pods: 0, endpoints: (none)
gate-0-gateway      20261113_103149_01036_qbfvt FINISHED first request 0.046 s  {"query_max_run_time":"10m"}
gate-0-envoy-gated  20261113_103149_01037_qbfvt FINISHED first request 0.019 s  {"query_max_run_time":"10m"}
gate scaled back to 1
gate-1-gateway      20261113_103154_01038_qbfvt FINISHED  {"required_workers_count":"2","query_max_run_time":"10m","required_workers_max_wait_time":"10m"}
gate-1-envoy-gated  20261113_103154_01039_qbfvt FINISHED  {"required_workers_count":"2","query_max_run_time":"10m","required_workers_max_wait_time":"10m"}
reservations: a 0, b 0
```

Without the gate both paths ran the query without the gate properties and kept the client property.

### Short functional check of the bench

`QUICK=1 ./bench-kind.sh` (16 W1, 5 W2, 1 W3 of 20,000 rows per path, 5 and 3 W4 runs) ran on all six paths,
and all 17 checks passed. The run took 45 s. I did not keep its numbers; the output is in
`lab/k8s/evidence/` (git-ignored). Two things from it are worth checking in the timed run:

- With the gate at 0 replicas, about half of the Envoy gate calls waited for the full 250 ms timeout and one
  `gateway` call waited for the 1 s timeout; the other calls failed at once. In compose a stopped gate always
  failed at once. I did not find out why. Possible causes: a ClusterIP Service without endpoints, or
  keep-alive connections to the removed pod.
- When `GATE_DELAY_MS` changes, the old gate pod keeps answering on open keep-alive connections until it is
  gone. `bench-kind.sh` now waits until only one gate pod is left.

## How to run the timed bench (L7)

```bash
cd poc/memory-history/lab/k8s
./up.sh           # if anything changed
./status.sh       # 2 active workers per cluster, 0 reservations
./bench-kind.sh   # writes ../results/bench-kind.jsonl and ../results/BENCH-kind.md
```

It runs `smoke`, `learn`, `run` (W1, W2, W3 with the bench defaults: 300, 100, 5 runs), `checks`,
W4 with the gate at 0 replicas (100 runs) and W4 with `GATE_DELAY_MS=2000` (30 runs), then the report.
On exit it always sets the gate back to 1 replica with no delay. Run it when no other load is on the host.

## Resources

- `/data`: 29 GB free before I started, 22 GB after the image load, 28 GB at the end. The other
  values come from other work on the host (the compose lab ran at the same time).
- Memory of the kind node (`docker stats`): 16.2 GiB with 5 + 2 workers, 12.2 GiB with 2 + 2 workers
  after the test. Host "available" memory went from about 88 GB to about 57 GB. The compose lab
  ran at the same time, so this difference is not only mine.
- Phase 2: `/data` had 28 GB free before and 30 GB free at the end. The new images in the node (gate,
  Envoy, Python) are about 0.3 GB. The kind node used 28.5 GiB just after the first heavy run and
  42.9 GiB while cluster A had 5 workers plus 3 workers in shutdown. Host "available" memory was 42 GB at
  that time, with the compose lab and other work on the same host.
- Pod requests: 6 Trino pods × 5 GiB = 30 GiB at rest, up to 50 GiB with 6 workers in one
  cluster, up to 70 GiB with 6 in both. The node allocatable is the host memory (124 GiB), so the
  scheduler does not block this. The heaps are not fully used at rest.

## Not checked

- Queries through `kubectl port-forward` from the host. I used the client pod only.
- The Trino CLI through the gateway. I used curl with the REST protocol.
- A reservation on `predictor-b`. I tested cluster A only, and cluster B as the control.
- Scale to 6 workers, and both clusters scaling at the same time (about 70 GiB of requests).
- Scale-down while queries run.
- A restart of PostgreSQL. The backends and the history are lost, and `up.sh` registers the backends again. I did not test it.
- Phase 2: the timed bench (L7). Both clusters scaling at the same time. A heavy query on cluster B
  after it became known there (step 4 shows that predictor-b learns it; I did not run it again on B).
- Phase 2: a scale-down that starts while a gated query waits. In run 1 the scale-down and the next
  scale-up overlapped, and the query still finished.
- Phase 2: the gate with more than one replica, and Envoy or Trino Gateway restarts during a query.
- Phase 2: the bench pod copies results out with `kubectl exec cat`. I checked it only with the short run.
- The timings are from one run, on a host where the compose lab and its bench ran at the same time.

## Problems and remarks

- **A reservation can stay for an hour.** After my second short bench check, predictor-a held 1 reservation
  with nothing running. It came from W4 with `GATE_DELAY_MS=2000`: the gateway gave up after 1 s, the gate
  still made the reservation, and the queries of that shape had already ended, so the automatic release did
  not match it. It stays until its 3600 s time limit. It asked for only 2 workers, so it did not scale
  anything, but `demo-heavy.sh` waits for 0 reservations before it starts. I released it by running one
  query of that shape directly on cluster A. The newer gate image has the counters `reservations_deleted`
  and `reservation_delete_errors`; I did not check if it fixes this case.
- **`up.sh` restarts a pod that it just created** when the image of that pod was loaded in the same run (I
  saw it for Envoy and the bench pod on their first install). It costs a few seconds and does no harm.

- The task text names the other kind clusters `mhpoc`, `chop-final-lc` and `chop-hub`. When I
  started, `kind get clusters` showed `chop-dev-fix`, `chop-hub` and `mhpoc`. At the end it showed
  only `mhlab` and `mhpoc`. I did not delete any cluster. My scripts call `kind delete` only in
  `down.sh`, only for `mhlab`, and I did not run `down.sh`.
- Contract L3 does not list `routingRules.rulesEngineEnabled: true`. Without it the external rules
  are not used. I added it in `values-gateway-external.yaml`.
- The Trino image has curl, but no grep, sed or python. `query.sh` therefore runs each request with
  curl in the pod and reads the JSON on the host with jq.
