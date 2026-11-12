/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.memoryhistory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonCodecFactory;
import io.airlift.log.Logger;
import io.trino.memoryhistory.fingerprint.QueryFingerprint;
import io.trino.spi.eventlistener.EventListener;
import io.trino.spi.eventlistener.QueryCompletedEvent;
import io.trino.spi.eventlistener.QueryContext;
import io.trino.spi.eventlistener.QueryFailureInfo;
import io.trino.spi.eventlistener.QueryMetadata;
import io.trino.spi.eventlistener.QueryStatistics;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Records the fingerprint and peak memory of every completed query in PostgreSQL.
 * The calling thread only copies the needed event fields and enqueues them; parsing,
 * fingerprinting and the insert run on a single background thread. The listener never throws.
 */
public class MemoryHistoryEventListener
        implements EventListener
{
    private static final Logger log = Logger.get(MemoryHistoryEventListener.class);
    private static final JsonCodec<Map<String, String>> SESSION_PROPERTIES_CODEC = new JsonCodecFactory().mapJsonCodec(String.class, String.class);
    private static final int QUEUE_SIZE = 1_000;
    @VisibleForTesting
    static final int MAX_TEXT_LENGTH = 100_000;
    private static final long DROP_LOG_INTERVAL_NANOS = MINUTES.toNanos(1);

    private final QueryHistoryStore store;
    private final ThreadPoolExecutor executor;
    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicLong failedInserts = new AtomicLong();
    private final AtomicLong lastDropLogNanos = new AtomicLong(System.nanoTime() - DROP_LOG_INTERVAL_NANOS);
    private volatile boolean tableCreated;

    @Inject
    public MemoryHistoryEventListener(QueryHistoryStore store)
    {
        this.store = requireNonNull(store, "store is null");
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0,
                MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE),
                new ThreadFactoryBuilder().setNameFormat("memory-history-%s").setDaemon(true).build(),
                (_, _) -> recordDrop());
        executor.execute(this::ensureTableCreated);
    }

    @Override
    public void queryCompleted(QueryCompletedEvent event)
    {
        try {
            CompletedQuery query = CompletedQuery.from(event);
            executor.execute(() -> insert(query));
        }
        catch (RuntimeException e) {
            log.error(e, "Failed to record query %s", event.getMetadata().getQueryId());
        }
    }

    @Override
    public void shutdown()
    {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, SECONDS)) {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @VisibleForTesting
    long droppedRecords()
    {
        return droppedRecords.get();
    }

    @VisibleForTesting
    long failedInserts()
    {
        return failedInserts.get();
    }

    /**
     * Waits until every task queued before this call has finished. The executor has a single
     * thread and a FIFO queue, so a sentinel task finishes only after all earlier tasks.
     */
    @VisibleForTesting
    boolean awaitIdle(long timeoutMillis)
            throws InterruptedException
    {
        try {
            executor.submit(() -> {}).get(timeoutMillis, MILLISECONDS);
            return true;
        }
        catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            return false;
        }
    }

    private void recordDrop()
    {
        long dropped = droppedRecords.incrementAndGet();
        long now = System.nanoTime();
        long last = lastDropLogNanos.get();
        if (now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now)) {
            log.warn("Memory history queue is full, dropped %s records so far", dropped);
        }
    }

    private void insert(CompletedQuery query)
    {
        try {
            Optional<QueryHistoryRecord> record = query.toRecord();
            if (record.isEmpty()) {
                return;
            }
            ensureTableCreated();
            try {
                store.insert(record.get());
            }
            catch (SQLException e) {
                if (!QueryHistoryStore.isUndefinedTable(e)) {
                    throw e;
                }
                // the database was replaced or the table dropped: create the table again and retry once
                log.warn("Table query_history is missing, creating it again");
                tableCreated = false;
                ensureTableCreated();
                store.insert(record.get());
            }
        }
        catch (Exception e) {
            failedInserts.incrementAndGet();
            log.error(e, "Failed to store memory history of query %s", query.queryId());
        }
    }

    private void ensureTableCreated()
    {
        if (tableCreated) {
            return;
        }
        try {
            store.createTable();
            tableCreated = true;
        }
        catch (Exception e) {
            log.error(e, "Failed to create query_history table, will retry on the next query");
        }
    }

    @VisibleForTesting
    static String truncate(String value)
    {
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        int end = MAX_TEXT_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * The event fields the listener stores. Built on the calling thread with plain getters
     * only, so the queue does not keep the whole event (plan, operator statistics) alive.
     */
    private record CompletedQuery(
            String queryId,
            String sql,
            Optional<String> catalog,
            Optional<String> schema,
            String queryState,
            Optional<String> errorCode,
            Optional<String> errorType,
            long peakUserMemoryBytes,
            long peakTaskUserMemoryBytes,
            long peakTaskTotalMemoryBytes,
            long physicalInputRows,
            long physicalInputBytes,
            long wallTimeMillis,
            long cpuTimeMillis,
            long queuedTimeMillis,
            String userName,
            Optional<String> source,
            String clientTags,
            SortedMap<String, String> sessionProperties,
            Instant createTime,
            Instant endTime)
    {
        static CompletedQuery from(QueryCompletedEvent event)
        {
            QueryMetadata metadata = event.getMetadata();
            QueryContext context = event.getContext();
            QueryStatistics statistics = event.getStatistics();
            Optional<QueryFailureInfo> failure = event.getFailureInfo();
            return new CompletedQuery(
                    metadata.getQueryId(),
                    metadata.getPreparedQuery().orElse(metadata.getQuery()),
                    context.getCatalog(),
                    context.getSchema(),
                    metadata.getQueryState(),
                    failure.map(info -> info.getErrorCode().getName()),
                    failure.map(info -> info.getErrorCode().getType().name()),
                    statistics.getPeakUserMemoryBytes(),
                    statistics.getPeakTaskUserMemory(),
                    statistics.getPeakTaskTotalMemory(),
                    statistics.getPhysicalInputRows(),
                    statistics.getPhysicalInputBytes(),
                    statistics.getWallTime().toMillis(),
                    statistics.getCpuTime().toMillis(),
                    statistics.getQueuedTime().toMillis(),
                    context.getUser(),
                    context.getSource(),
                    String.join(",", context.getClientTags().stream().sorted().collect(toImmutableList())),
                    ImmutableSortedMap.copyOf(context.getSessionProperties()),
                    event.getCreateTime(),
                    event.getEndTime());
        }

        /**
         * Fingerprints the SQL. Empty when the statement has no fingerprint.
         * The hash covers the full normalized SQL; only the stored text is truncated.
         */
        Optional<QueryHistoryRecord> toRecord()
        {
            return QueryFingerprint.compute(sql, catalog, schema)
                    .map(fingerprint -> new QueryHistoryRecord(
                            queryId,
                            fingerprint.hash(),
                            truncate(fingerprint.normalizedSql()),
                            String.join(",", fingerprint.tables()),
                            queryState,
                            errorCode,
                            errorType,
                            peakUserMemoryBytes,
                            peakTaskUserMemoryBytes,
                            peakTaskTotalMemoryBytes,
                            physicalInputRows,
                            physicalInputBytes,
                            wallTimeMillis,
                            cpuTimeMillis,
                            queuedTimeMillis,
                            userName,
                            source,
                            clientTags,
                            truncate(SESSION_PROPERTIES_CODEC.toJson(sessionProperties)),
                            createTime,
                            endTime));
        }
    }
}
