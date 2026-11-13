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
package io.trino.memoryhistory.predictor;

import io.trino.memoryhistory.predictor.HistoryRepository.CompletedQuery;

import java.io.Closeable;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Releases {@code /v1/gate} reservations when their queries complete.
 * <p>
 * Each run reads the rows of {@code query_history} with an {@code end_time} at or after
 * {@code cursor - overlap}, where {@code cursor} is the largest {@code end_time} seen so far.
 * The overlap catches rows that are written after rows with a later {@code end_time}.
 * The ids of the rows in the overlap are kept, so that each row releases at most one reservation.
 * A row that is written later than the overlap after its {@code end_time} is not seen.
 * The time limit of the reservation then releases it.
 */
public class AutoRelease
        implements Closeable
{
    public static final Duration DEFAULT_OVERLAP = Duration.ofMinutes(5);

    private static final Logger log = Logger.getLogger(AutoRelease.class.getName());

    private final CompletedQuerySource source;
    private final Reservations reservations;
    private final Duration interval;
    private final Duration overlap;
    private final ScheduledExecutorService executor;

    // Guarded by this
    private Instant cursor;
    private final Map<String, Instant> processed = new HashMap<>();

    public AutoRelease(CompletedQuerySource source, Reservations reservations, Duration interval, Duration overlap, Instant start)
    {
        this.source = requireNonNull(source, "source is null");
        this.reservations = requireNonNull(reservations, "reservations is null");
        this.interval = requireNonNull(interval, "interval is null");
        this.overlap = requireNonNull(overlap, "overlap is null");
        checkArgument(interval.isPositive(), "interval must be positive");
        checkArgument(!overlap.isNegative(), "overlap must not be negative");
        // Reservations live in memory, so rows that ended before the start cannot match one
        this.cursor = requireNonNull(start, "start is null");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "memory-history-auto-release");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start()
    {
        executor.scheduleWithFixedDelay(this::runSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close()
    {
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runSafely()
    {
        try {
            runOnce();
        }
        catch (Exception e) {
            // Keep the schedule: the next run reads the same rows again
            log.log(Level.WARNING, "Automatic release failed, retrying at the next run", e);
        }
    }

    /**
     * Reads the new completed rows and releases one reservation for each. Returns the number of released reservations.
     */
    synchronized int runOnce()
            throws SQLException
    {
        List<CompletedQuery> rows = source.completedSince(cursor.minus(overlap));
        int released = 0;
        for (CompletedQuery row : rows) {
            if (processed.putIfAbsent(row.queryId(), row.endTime()) != null) {
                continue;
            }
            if (row.endTime().isAfter(cursor)) {
                cursor = row.endTime();
            }
            Optional<String> id = reservations.releaseCompleted(row.fingerprint(), row.endTime());
            if (id.isPresent()) {
                released++;
                log.fine("Released reservation %s after query %s".formatted(id.get(), row.queryId()));
            }
        }
        // The next read starts at cursor - overlap, so older ids cannot come back
        Instant keepFrom = cursor.minus(overlap);
        processed.values().removeIf(endTime -> endTime.isBefore(keepFrom));
        return released;
    }

    @FunctionalInterface
    public interface CompletedQuerySource
    {
        List<CompletedQuery> completedSince(Instant from)
                throws SQLException;
    }
}
