# kind lab notes (milestone L5)

This is the kind environment of the lab (LAB-PLAN.md, "Two environments"). Phase 1 has two Trino
clusters behind Trino Gateway, one predictor for each cluster, and KEDA for each cluster.
The gate, Envoy and the bench come in phase 2.

All scripts use only the kind cluster `mhlab` and the kubeconfig file `lab/k8s/.kubeconfig`
(git-ignored). They never read or change the default kubeconfig. By hand:
`kubectl --kubeconfig poc/memory-history/lab/k8s/.kubeconfig -n lab ...`.

## Start and stop

```bash
cd poc/memory-history/lab/k8s
./up.sh        # create or update; safe to run again
./status.sh    # pods, ScaledObjects, HPAs, active workers, predictor values, gateway backends
./down.sh      # deletes cluster mhlab only; the local images stay
```

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
| PostgreSQL | `postgres:5432` | `manifests/postgres.yaml` | Databases `history_a`, `history_b`, `gateway`. User `trino`/`trino`. emptyDir: no data is kept. |
| Cluster A | `trino-a:8080` | chart `trino` 1.45.0, release `trino-a` | 1 coordinator, 2 to 6 workers. Listener writes to `history_a`. |
| Cluster B | `trino-b:8080` | chart `trino` 1.45.0, release `trino-b` | The same, `history_b`. |
| Predictor A | `predictor-a:8090` | `manifests/predictor.yaml` | Reads `history_a`. `MIN_WORKERS=2`, `MAX_WORKERS=6`, `SAFETY_FACTOR=1.5`, `PER_WORKER_MEMORY_BYTES=1073741824`. |
| Predictor B | `predictor-b:8090` | the same file | Reads `history_b`. |
| Trino Gateway | `gateway:8080` | chart `trino-gateway` 1.22.0 (Trino Gateway 22), release `gateway` | Database `gateway`. Backends `trino-a` (group `a`) and `trino-b` (group `b`). |
| Client | no Service | `manifests/client.yaml` | Trino image with `sleep`. I use it for curl and HTTP tests inside the cluster. |
| KEDA | namespace `keda` | chart `keda` 2.21.0 | One ScaledObject for each worker Deployment. |

Files in `lab/k8s/`:

| File | Purpose |
|---|---|
| `env.sh` | Names, versions, the `kc`/`hm` wrappers that pin the kubeconfig, and small helpers. |
| `kind-config.yaml` | One node. Kubelet `syncFrequency: 10s`. |
| `up.sh`, `down.sh`, `status.sh` | See above. |
| `values-trino.yaml` | Shared Trino values. The memory numbers are the same as in the PoC. Also `http-server.process-forwarded=true`, tpch and tpcds catalogs, graceful worker shutdown, KEDA limits and HPA behavior. |
| `values-trino-a.yaml`, `values-trino-b.yaml` | `fullnameOverride`, the KEDA `metrics-api` trigger to the own predictor, and the listener file to the own database. |
| `values-gateway.yaml` | Trino Gateway, phase 1: no routing rules, `defaultRoutingGroup: a`, `monitorType: INFO_API`, health check every 10 s. |
| `values-gateway-external.yaml` | Phase 2 only. Not applied. See below. |
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

## Phase 2: external routing

Phase 2 runs `GATEWAY_EXTERNAL=1 ./up.sh`. It adds `values-gateway-external.yaml` to the gateway
release. Helm merges the file into `config` and the gateway pod restarts (the chart puts a checksum
of the config on the pod). No other file changes. The file sets:

- `routingRules.rulesEngineEnabled: true`, `rulesType: EXTERNAL`,
  `rulesExternalConfiguration.urlPath: http://gate.lab.svc:8095/gateway/route`,
  `excludeHeaders: []`, `propagateErrors: false`;
- `requestAnalyzerConfig.analyzeRequest: true`;
- `serverConfig.router.http-client.request-timeout: 1s`.

I checked with `helm template` that the rendered `config.yaml` has all these keys, and that
`excludeHeaders: []` stays in the file. I did not apply it, because the gate does not run in kind yet.
Contract L3 does not name `rulesEngineEnabled`. Trino Gateway 22 uses routing rules only when
that value is `true`, so I added it.

`gateway-plain` (LAB-PLAN, parts table) does not exist in kind yet. A second release of the same
chart with `values-gateway.yaml` only would do. It would need its own `serviceName`. I did not
decide if it shares the `gateway` database.

## Proof (2026-11-13, arm64)

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

## Resources

- `/data`: 29 GB free before I started, 22 GB after the image load, 28 GB at the end. The other
  values come from other work on the host (the compose lab ran at the same time).
- Memory of the kind node (`docker stats`): 16.2 GiB with 5 + 2 workers, 12.2 GiB with 2 + 2 workers
  after the test. Host "available" memory went from about 88 GB to about 57 GB. The compose lab
  ran at the same time, so this difference is not only mine.
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
- The gateway with `values-gateway-external.yaml`.
- The timings are from one run, on a host where the compose lab and its bench ran at the same time.

## Problems and remarks

- The task text names the other kind clusters `mhpoc`, `chop-final-lc` and `chop-hub`. When I
  started, `kind get clusters` showed `chop-dev-fix`, `chop-hub` and `mhpoc`. At the end it showed
  only `mhlab` and `mhpoc`. I did not delete any cluster. My scripts call `kind delete` only in
  `down.sh`, only for `mhlab`, and I did not run `down.sh`.
- Contract L3 does not list `routingRules.rulesEngineEnabled: true`. Without it the external rules
  are not used. I added it in `values-gateway-external.yaml`.
- The Trino image has curl, but no grep, sed or python. `query.sh` therefore runs each request with
  curl in the pod and reads the JSON on the host with jq.
