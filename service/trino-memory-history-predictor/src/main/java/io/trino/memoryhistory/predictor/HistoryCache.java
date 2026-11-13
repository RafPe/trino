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

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Keeps the history rows of a fingerprint for a short time, so a burst of queries of one shape reads
 * the database once.
 * <p>
 * Staleness: a prediction can miss the rows that were written in the last {@code ttl} before it. A new
 * fingerprint stays unknown, and a new run (also a memory failure) changes the prediction, at most
 * {@code ttl} later than without the cache. Only the prediction uses this cache. The automatic release
 * and the reservations do not. A zero {@code ttl} turns the cache off. Failed reads are not cached.
 */
public class HistoryCache
{
    // Above this size, expired entries are removed; if all entries are live, the cache starts empty again
    static final int MAX_ENTRIES = 10_000;

    private final HistorySource source;
    private final long ttlNanos;
    private final LongSupplier ticker;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    public HistoryCache(HistorySource source, Duration ttl, LongSupplier ticker)
    {
        this.source = requireNonNull(source, "source is null");
        requireNonNull(ttl, "ttl is null");
        checkArgument(!ttl.isNegative(), "ttl must not be negative");
        this.ttlNanos = ttl.toNanos();
        this.ticker = requireNonNull(ticker, "ticker is null");
    }

    public List<HistoryRun> lastRuns(String fingerprint, int limit)
            throws SQLException
    {
        if (ttlNanos == 0) {
            return source.lastRuns(fingerprint, limit);
        }
        Key key = new Key(fingerprint, limit);
        Entry cached = entries.get(key);
        if (cached != null && ticker.getAsLong() - cached.loadedAt() < ttlNanos) {
            return cached.runs();
        }
        // The age counts from before the read, so an entry never covers more than ttl of writes
        long loadedAt = ticker.getAsLong();
        List<HistoryRun> runs = source.lastRuns(fingerprint, limit);
        if (entries.size() >= MAX_ENTRIES) {
            evict(loadedAt);
        }
        entries.put(key, new Entry(List.copyOf(runs), loadedAt));
        return runs;
    }

    int size()
    {
        return entries.size();
    }

    private void evict(long now)
    {
        entries.values().removeIf(entry -> now - entry.loadedAt() >= ttlNanos);
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
    }

    @FunctionalInterface
    public interface HistorySource
    {
        List<HistoryRun> lastRuns(String fingerprint, int limit)
                throws SQLException;
    }

    private record Key(String fingerprint, int limit) {}

    private record Entry(List<HistoryRun> runs, long loadedAt) {}
}
