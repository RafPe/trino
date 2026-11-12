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
import io.trino.spi.eventlistener.EventListener;
import io.trino.spi.eventlistener.QueryCompletedEvent;
import io.trino.spi.eventlistener.QueryContext;
import io.trino.spi.eventlistener.QueryFailureInfo;
import io.trino.spi.eventlistener.QueryMetadata;
import io.trino.spi.eventlistener.QueryStatistics;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Records the fingerprint and peak memory of every completed query in PostgreSQL.
 * Inserts run on a single background thread. The listener never throws.
 */
public class MemoryHistoryEventListener
        implements EventListener
{
    private static final Logger log = Logger.get(MemoryHistoryEventListener.class);
    private static final JsonCodec<Map<String, String>> SESSION_PROPERTIES_CODEC = new JsonCodecFactory().mapJsonCodec(String.class, String.class);
    private static final int QUEUE_SIZE = 10_000;

    private final QueryHistoryStore store;
    private final ThreadPoolExecutor executor;
    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicLong failedInserts = new AtomicLong();
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
                (_, _) -> {
                    long dropped = droppedRecords.incrementAndGet();
                    log.warn("Memory history queue is full, dropped %s records so far", dropped);
                });
        executor.execute(this::ensureTableCreated);
    }

    @Override
    public void queryCompleted(QueryCompletedEvent event)
    {
        try {
            Optional<QueryHistoryRecord> record = toRecord(event);
            if (record.isEmpty()) {
                return;
            }
            executor.execute(() -> insert(record.get()));
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

    @VisibleForTesting
    boolean awaitIdle(long timeoutMillis)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    private void insert(QueryHistoryRecord record)
    {
        try {
            ensureTableCreated();
            store.insert(record);
        }
        catch (Exception e) {
            failedInserts.incrementAndGet();
            log.error(e, "Failed to store memory history of query %s", record.queryId());
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
    static Optional<QueryHistoryRecord> toRecord(QueryCompletedEvent event)
    {
        QueryMetadata metadata = event.getMetadata();
        QueryContext context = event.getContext();
        QueryStatistics statistics = event.getStatistics();

        String sql = metadata.getPreparedQuery().orElse(metadata.getQuery());
        Optional<QueryFingerprint> fingerprint = QueryFingerprint.compute(sql, context.getCatalog(), context.getSchema());
        if (fingerprint.isEmpty()) {
            return Optional.empty();
        }

        Optional<QueryFailureInfo> failure = event.getFailureInfo();
        return Optional.of(new QueryHistoryRecord(
                metadata.getQueryId(),
                fingerprint.get().hash(),
                fingerprint.get().normalizedSql(),
                String.join(",", fingerprint.get().tables()),
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
                SESSION_PROPERTIES_CODEC.toJson(ImmutableSortedMap.copyOf(context.getSessionProperties())),
                event.getCreateTime(),
                event.getEndTime()));
    }
}
