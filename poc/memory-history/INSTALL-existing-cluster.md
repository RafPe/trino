# Install the history listener on an existing Trino on Kubernetes

This guide installs **only the event listener**. The listener records one row for each completed query.
It does not change how queries run. There is no gate, no predictor and no scaling in this guide.

Use it to collect data from real queries. The data shows how frequently queries repeat and how stable
their peak memory is.

## What I tested, and what I did not

| Item | Status |
|---|---|
| The listener in the official `trinodb/trino:484` image | Tested (docker compose and kind) |
| The Helm values in this guide, official `trino` chart 1.45.0 | Tested on kind |
| The analysis queries in this guide | Tested on the PoC database |
| A real EKS cluster | Not tested |
| Trino versions other than 484 | Not tested |
| The database password from a Kubernetes Secret (`${ENV:...}`) | Not tested |
| A cluster with access control or with a second event listener | Not tested |

## Before you start

- **Trino version.** The plugin is built for Trino 484. For a different version, build the plugin from the source of that version. I did not test this.
- **A PostgreSQL database.** The coordinator must be able to connect to it. Do not use the PoC manifest `k8s/manifests/postgres.yaml`. It keeps the data in an `emptyDir` volume, and the data is lost when the pod restarts.
- **A container registry** that the cluster can pull from.
- **A build host** with JDK 25, `mvnd` or `./mvnw`, Docker and Python 3.
- **A maintenance window.** The coordinator restarts when the listener is installed. Queries that run at that time fail.

## What the listener stores

One row for each completed `SELECT`, `INSERT` or `CREATE TABLE AS` query:

- The fingerprint, and the normalized SQL text. Literals are replaced with `?`. Table and column names stay.
- The list of tables.
- The query state and the error code.
- Peak memory, input rows and bytes, wall time, CPU time and queued time.
- The user name, the source, the client tags and the session properties.

Make sure that it is permitted to store this data in your organization. On the PoC database, 99 rows used
144 kB. This is about 1.5 kB for each row. Long SQL texts need more space: the limit is 100,000 characters.

## Steps

### 1. Build the image

Run these commands from `poc/memory-history/`.

```bash
docker/build-images.sh
docker tag trino-memory-history:poc <registry>/trino-memory-history:484-1
docker push <registry>/trino-memory-history:484-1
```

- The script builds for the architecture of the build host. If the cluster nodes have a different architecture, build the image for that architecture. I did not test a cross-architecture build.
- If your cluster uses its own Trino image, change the `FROM` line in `docker/Dockerfile.trino` to that image. Then run the script again.

### 2. Prepare the database

Create a database and a user. The listener creates the table `query_history` when it starts.

```sql
CREATE USER memory_history WITH PASSWORD '<password>';
CREATE DATABASE memory_history OWNER memory_history;
```

### 3. Add the Helm values

Add these values to the values of your Trino release. They are the same as `k8s/values-listener.yaml`,
with your database address.

```yaml
image:
  repository: <registry>/trino-memory-history
  tag: 484-1

coordinator:
  additionalConfigFiles:
    memory-history-listener.properties: |
      event-listener.name=memory-history
      memory-history.db.url=jdbc:postgresql://<host>:5432/memory_history
      memory-history.db.user=memory_history
      memory-history.db.password=<password>

server:
  coordinatorExtraConfig: |
    event-listener.config-files=/etc/trino/memory-history-listener.properties
```

Notes:

- **Coordinator only.** Do not use the chart value `eventListenerProperties`. The chart writes that value to the workers also.
- **Password.** This example puts the password in a ConfigMap. This is acceptable only for a test. Trino can read `${ENV:NAME}` in this file, so you can set the password from a Kubernetes Secret through an environment variable. I did not test this.
- **A second event listener.** `event-listener.config-files` is a comma-separated list. If you already have a listener, add the new file to your list.
- **The image applies to all pods.** The workers get the image too, and they restart. They do not load the listener.

### 4. Apply the change

```bash
helm upgrade <release> trino --repo https://trinodb.github.io/charts --version <your chart version> \
  --namespace <namespace> -f <your values files>
```

Wait until the coordinator and the workers are ready.

### 5. Make sure that the listener works

1. Look for the listener in the coordinator log:

   ```bash
   kubectl -n <namespace> logs -l app.kubernetes.io/component=coordinator --tail=-1 | grep -i "event listener"
   ```

   The log must contain `Registering event listener memory-history`. It must not contain errors about the listener.

2. Run a query through Trino. Then look at the table:

   ```sql
   SELECT left(fingerprint, 12) AS fp, query_state, error_code,
          pg_size_pretty(peak_user_memory_bytes) AS peak, tables, end_time
   FROM query_history
   ORDER BY end_time DESC
   LIMIT 10;
   ```

   A new row must appear some seconds after the query ends.

## What to look at after one or two weeks

Run these queries on the PostgreSQL database.

**How many queries had an earlier run of the same shape?** This is the hit rate that a predictor would get.

```sql
WITH f AS (SELECT fingerprint, count(*) AS runs FROM query_history GROUP BY fingerprint)
SELECT count(*) AS shapes,
       sum(runs) AS queries,
       round(100.0 * sum(runs - 1) / sum(runs), 1) AS pct_with_earlier_run
FROM f;
```

**How stable is the peak memory of one shape?** A `max_over_min` near 1 means that history predicts the peak well.

```sql
SELECT left(fingerprint, 12) AS fp,
       count(*) AS runs,
       pg_size_pretty(min(peak_user_memory_bytes)) AS min_peak,
       pg_size_pretty(max(peak_user_memory_bytes)) AS max_peak,
       round(max(peak_user_memory_bytes)::numeric / nullif(min(peak_user_memory_bytes), 0), 2) AS max_over_min
FROM query_history
WHERE query_state = 'FINISHED'
GROUP BY fingerprint
HAVING count(*) >= 3 AND max(peak_user_memory_bytes) >= 100 * 1024 * 1024
ORDER BY max_over_min DESC
LIMIT 50;
```

**How many memory failures came from a shape that ran before?** These are the failures that prediction could prevent.

```sql
SELECT count(*) AS memory_failures,
       count(*) FILTER (WHERE EXISTS (
           SELECT 1 FROM query_history e
           WHERE e.fingerprint = h.fingerprint AND e.end_time < h.create_time)) AS had_earlier_run
FROM query_history h
WHERE error_code IN ('EXCEEDED_LOCAL_MEMORY_LIMIT', 'EXCEEDED_GLOBAL_MEMORY_LIMIT', 'CLUSTER_OUT_OF_MEMORY');
```

**How large is the table?**

```sql
SELECT pg_size_pretty(pg_total_relation_size('query_history')) AS table_size, count(*) AS row_count
FROM query_history;
```

## Operation

- **If the database is down:** queries are not affected. The listener writes on a background thread with timeouts of 10 seconds. It keeps a maximum of 1,000 records in memory. It drops more records and writes a warning to the coordinator log one time each minute.
- **If the table is deleted:** the listener creates it again on the next write.
- **Old rows:** the listener does not delete rows. Delete old rows yourself, for example:

  ```sql
  DELETE FROM query_history WHERE end_time < now() - interval '90 days';
  ```

- **More than one cluster:** the table has no cluster column. Use one database for each cluster.

## Remove the listener

1. Remove the values from step 3.
2. Set the image to your original image.
3. Run `helm upgrade` again. The coordinator restarts.
4. Delete the database if you do not need the data.

## Test it locally first

`README.md`, section 4, starts a local kind cluster with the same image and the same Helm values. Use it
to try this guide before you change a real cluster.

## After the data collection

The next step is the predictor, the scaling trigger and the gate. `README.md` describes them. Do that step
only if the numbers from the queries above are good.
