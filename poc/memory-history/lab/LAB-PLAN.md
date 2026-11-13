# Lab plan: a transparent gate, and what it costs

Branch: `RafPe/memory-history-poc`. Folder: `poc/memory-history/lab/`.

## Goal

The PoC gate is a script that the client runs. This lab moves the gate to the server side and measures
the effect on performance. It answers three questions:

1. Can Trino Gateway add the gate with an external routing service?
2. Can a proxy in front of the coordinator add the gate?
3. How much time does each layer add for a query, and what occurs when a part is slow or stopped?

## Out of scope

- Changes under `core/`.
- Authentication, TLS and high availability.
- A proxy that I write from zero. The lab uses Envoy.
- minitrino.

## Parts

```
                         ┌──────────────── Trino Gateway (external routing) ──┐
client / bench ──────────┤                                                    ├──▶ cluster A (coordinator, workers, plugin)
                         ├──────────────── Envoy (ext_authz on the first request) ──▶ cluster A
                         └──────────────── direct ────────────────────────────────▶ cluster A or B

Trino Gateway ──▶ gate ──▶ predictor A or B ──▶ history database A or B
Envoy         ──▶ gate ──▶ predictor A
```

| Part | Image or source | Role |
|---|---|---|
| Cluster A, cluster B | `trino-memory-history:poc` | Two Trino 484 clusters with the history listener. Each has its own history database and its own predictor. |
| Predictor A, predictor B | `trino-memory-history-predictor:poc` | One for each cluster. New in this lab: `POST /v1/gate` and automatic release of reservations. |
| Gate | `lab/gate/` (Go) → image `trino-memory-history-gate:lab` | Decision service. It answers Trino Gateway and Envoy. It calls the predictor of the selected cluster. |
| Trino Gateway | `trinodb/trino-gateway:22` | Two instances: `gateway` with external routing, `gateway-plain` without routing rules (baseline). |
| Envoy | `envoyproxy/envoy` (pin one version) | Three listeners: with the gate, without the gate (baseline), and first-request-only. |
| Bench | `lab/bench/` (Python) | Sends the same queries on each path and reports the time. |

## Contract L1: predictor additions

**`POST /v1/gate`** — request `{"sql": "...", "catalog": "tpch", "schema": "tiny"}`.

- It does the same prediction as `/v1/predict`.
- If the query is known, it creates a reservation in the same call.
- Response: all fields of `/v1/predict`, plus:
  - `"reservationId"`: the id, or `null`.
  - `"sessionProperties"`: `{"required_workers_count": "<N>", "required_workers_max_wait_time": "<GATE_MAX_WAIT>"}` for a known query, `{}` for all other queries.
- New configuration: `GATE_MAX_WAIT` (default `10m`), `GATE_RESERVATION_TTL_SECONDS` (default `3600`).

**Automatic release.** A background task reads `query_history` every `AUTO_RELEASE_INTERVAL_MS` (default `2000`).
For each new completed row, it releases the oldest live reservation that:

- was made by `/v1/gate`,
- has the same fingerprint, and
- was made before the `end_time` of the row.

The time limit of the reservation stays as a second safety.
Known limit: with concurrent queries of one fingerprint, the task can release the reservation of a different query of that fingerprint. The total count stays correct.

Existing endpoints do not change.

## Contract L2: gate service

Configuration (environment): `GATE_GROUPS` (for example `a=http://predictor-a:8090,b=http://predictor-b:8090`),
`GATE_DEFAULT_GROUP` (`a`), `GATE_PREDICTOR_TIMEOUT_MS` (`200`), `GATE_DELAY_MS` (`0`, test only: wait before each answer), `PORT` (`8095`).

**`POST /gateway/route`** — the Trino Gateway external routing call.

- Input: the JSON body of Trino Gateway. The gate reads `trinoQueryProperties.body` (the SQL text), `defaultCatalog`, `defaultSchema`. The client headers arrive as HTTP headers.
- Routing group: the value of the `X-Trino-Routing-Group` header if it names a configured group. If not, `GATE_DEFAULT_GROUP`.
- The gate calls `/v1/gate` on the predictor of that group.
- Output: `{"routingGroup": "<group>", "errors": [], "externalHeaders": {...}}`.
  - Known query: `externalHeaders` has `X-Trino-Session`. Its value is the session properties that the client sent, plus the two properties from the predictor. The client properties must not be lost.
  - All other cases: `externalHeaders` is `{}`.

**`/envoy/check/<group>/...`** — the Envoy `ext_authz` HTTP call. Envoy adds the original path after the prefix.

- For `POST …/v1/statement`: the gate reads the SQL from the request body and the catalog and schema from `X-Trino-Catalog` and `X-Trino-Schema`. It calls `/v1/gate` on the predictor of `<group>`. It answers `200` with the header `x-trino-session: required_workers_count=<N>,required_workers_max_wait_time=<value>` for a known query. It answers `200` with no extra header for all other cases.
- For all other requests: `200`, no extra header.

**Fail open, always.** If the predictor does not answer in `GATE_PREDICTOR_TIMEOUT_MS`, or returns an error, the gate answers as for an unknown query. The gate never returns an error that stops a query.

**`GET /healthz`** → `200`. **`GET /stats`** → JSON counters: requests, known, unknown, predictor errors, predictor timeouts.

## Contract L3: Trino Gateway

- `routingRules.rulesEngineEnabled: true`, `routingRules.rulesType: EXTERNAL`, `rulesExternalConfiguration.urlPath: http://gate:8095/gateway/route`, `excludeHeaders: []` (the key must be present), `propagateErrors: false`.
- `requestAnalyzerConfig.analyzeRequest: true`.
- `serverConfig: router.http-client.request-timeout: 1s`. The default is 5 minutes.
- Backends: cluster A in routing group `a`, cluster B in routing group `b`. Register them with `POST /gateway/backend/modify/add`.
- Both Trino clusters set `http-server.process-forwarded=true`. Trino Gateway does not rewrite `nextUri`.
- `gateway-plain` is the same without routing rules.

## Contract L4: Envoy

One Envoy, three listeners, all to cluster A:

| Listener | Behaviour |
|---|---|
| `gated` | `ext_authz` (HTTP service = gate, path prefix `/envoy/check/a`) only for `POST /v1/statement`, with the request body. All other routes go through with `ext_authz` disabled. The `x-trino-session` header from the gate is appended to the request. `failure_mode_allow: true`. Timeout 250 ms. |
| `plain` | No `ext_authz`. Baseline for the cost of the hop. |
| `first-only` | As `gated`, but the upstream `Host` header is the coordinator address. Then `nextUri` points at the coordinator, and only the first request of a query goes through Envoy. |

## Contract L5: bench

Paths: `direct`, `gateway-plain`, `gateway`, `envoy-plain`, `envoy-gated`, `envoy-first-only`.

| Workload | What it shows |
|---|---|
| W1: `SELECT 1`, 300 runs in sequence, and 8 at the same time | The time that a path adds to a very short query |
| W2: a short query with a known fingerprint (`count(*)` on `tpch.tiny`) | The same, with a prediction and a reservation |
| W3: a large result (for example 1–2 million rows of `tpch.sf1.lineitem`) | Result throughput on each path |
| W4: W1 with the gate stopped, and with `GATE_DELAY_MS=2000` | Fail-open behaviour and the maximum added time |

Report for each path and workload: median, p95 and p99 of the total client time and of the first request (`POST /v1/statement`), and the difference to `direct`. For W3: rows for each second and MB for each second.
The bench runs in a container on the lab network, so each path uses the same network.
Output: `lab/results/bench-<env>.jsonl` and `lab/results/BENCH-<env>.md`.

Checks that the bench must also make:

- A session property that the client sets (for example `query_max_run_time`) still reaches Trino on the `gateway` path and on the `envoy-gated` path.
- A known query gets `required_workers_count` on the `gateway` path and on the `envoy-gated` path (read it from `/v1/query/<id>` or from `query_history.session_properties`).
- The reservation count of the predictor goes back to 0 after the queries end.

## Contract L6: wait modes

A query must not always wait. The gate has three modes.

| Mode | What the gate does for a known query |
|---|---|
| `signal` | It makes the reservation, so the autoscaler adds workers. It adds no session properties. The query starts immediately with the workers that are there. |
| `wait` | It makes the reservation and adds `required_workers_count` and `required_workers_max_wait_time`. Trino holds the query until the workers joined. This is the behaviour before this contract. |
| `bounded:<seconds>` | It makes the reservation. Then the gate holds its answer until the cluster has the required workers, or until `<seconds>` passed. Then it answers with no session properties. The query starts with the workers that are there at that time. |

An unknown query is not changed in any mode.

**How the mode is selected** (the first match is used):

1. The request header `X-Memory-Gate-Mode`.
2. A client tag `gate_mode=<mode>` in `X-Trino-Client-Tags`.
3. The default of the routing group: `GATE_MODE_<GROUP>` (for example `GATE_MODE_A=signal`).
4. `GATE_DEFAULT_MODE` (default `wait`).

A value that the gate does not understand is the same as no value. `<seconds>` is limited by `GATE_MAX_BOUNDED_SECONDS` (default `60`).

**Worker count for `bounded`.** The gate reads the number of active workers of the group from the Trino
coordinator. Configuration: `GATE_NODES_<GROUP>` (the coordinator URL). It reads the count each second
during the hold. If it cannot read the count, it answers immediately, with no session properties.

**Time limits of the callers.** A caller must wait longer than the hold.

- Envoy: a fourth listener `gated-bounded` with an `ext_authz` time limit above `GATE_MAX_BOUNDED_SECONDS`. The listener `gated` keeps its short time limit, so `bounded` on that listener ends at the short time limit and the query runs unchanged.
- Trino Gateway: the lab does not use `bounded` on this path. Its routing time limit stays at 1 s.

`/stats` counts the requests for each mode, and the holds that ended because the workers were there or because the time passed.

## Two environments

- **Compose** (`lab/compose/`, project name `mhlab`): all parts above. Fixed worker count (2 for each cluster).
- **kind** (`lab/k8s/`, cluster name `mhlab`, its own kubeconfig file): the same parts. Trino from the official Helm chart, one release for each cluster. KEDA scales the workers of each cluster from its predictor. Trino Gateway from the official Helm chart or from a manifest.

## Milestones

| # | What it proves | Evidence |
|---|---|---|
| L1 | Predictor: `/v1/gate` and automatic release work | Unit tests, and a `curl` sequence |
| L2 | Compose: two clusters work behind `gateway-plain`, and behind `envoy-plain` | A query on each path, with the query id and the cluster that ran it |
| L3 | Compose: the gate adds the session properties on the `gateway` path and on the `envoy-gated` path, and client properties stay | Rows from `query_history.session_properties` |
| L4 | Compose: bench report | `BENCH-compose.md` |
| L5 | kind: two clusters behind Trino Gateway, KEDA for each cluster | Queries on each path, worker counts |
| L6 | kind: gate on both paths, and a heavy known query scales the right cluster | Worker timeline for cluster A and cluster B |
| L7 | kind: bench report | `BENCH-kind.md` |
| L8 | Wait modes work on compose and on kind | Session properties for each mode, hold times, and a heavy query in each mode on kind |

## Working rules

- Work only in the folders of your part. Do not run `git add`, `git commit` or `git push`.
- Do not change a contract. If a contract is wrong, write the problem in your notes and stop that item.
- Do not run `mvn install`.
- Do not change or delete the kind clusters `mhpoc`, `chop-final-lc` and `chop-hub`.
- Use the compose project name `mhlab` and the kind cluster name `mhlab` only. Remove the containers that you start for tests. Keep the images.
- Docker data is on `/data`. Stop and write a note if the free space goes below 12 GB.
- Write notes as short, plain sentences in the first person. Say what you measured and what you did not check. Do not write about tools or assistants.
