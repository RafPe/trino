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

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestHistoryCache
{
    private static final Duration TTL = Duration.ofSeconds(2);

    @Test
    void testHit()
            throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong ticker = new AtomicLong();
        HistoryCache cache = new HistoryCache(source, TTL, ticker::get);
        List<HistoryRun> first = cache.lastRuns("f1", 10);
        ticker.addAndGet(TTL.toNanos() - 1);
        assertThat(cache.lastRuns("f1", 10)).isEqualTo(first);
        assertThat(source.calls).containsExactly("f1/10");
        // another fingerprint, or another limit, is another entry
        cache.lastRuns("f2", 10);
        cache.lastRuns("f1", 5);
        assertThat(source.calls).containsExactly("f1/10", "f2/10", "f1/5");
    }

    @Test
    void testExpiry()
            throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong ticker = new AtomicLong();
        HistoryCache cache = new HistoryCache(source, TTL, ticker::get);
        assertThat(cache.lastRuns("f1", 10)).hasSize(1);
        // a new run is written; the cache shows it once the entry is TTL old
        source.runs++;
        assertThat(cache.lastRuns("f1", 10)).hasSize(1);
        ticker.addAndGet(TTL.toNanos());
        assertThat(cache.lastRuns("f1", 10)).hasSize(2);
        assertThat(source.calls).containsExactly("f1/10", "f1/10");
    }

    @Test
    void testOff()
            throws Exception
    {
        FakeSource source = new FakeSource();
        HistoryCache cache = new HistoryCache(source, Duration.ZERO, () -> 0);
        for (int i = 0; i < 3; i++) {
            assertThat(cache.lastRuns("f1", 10)).hasSize(source.runs);
            source.runs++;
        }
        assertThat(source.calls).hasSize(3);
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void testFailureIsNotCached()
            throws Exception
    {
        FakeSource source = new FakeSource();
        HistoryCache cache = new HistoryCache(source, TTL, () -> 0);
        source.fail = true;
        assertThatThrownBy(() -> cache.lastRuns("f1", 10)).isInstanceOf(SQLException.class);
        source.fail = false;
        assertThat(cache.lastRuns("f1", 10)).hasSize(1);
        assertThat(source.calls).hasSize(2);
    }

    @Test
    void testSizeIsBounded()
            throws Exception
    {
        FakeSource source = new FakeSource();
        HistoryCache cache = new HistoryCache(source, TTL, () -> 0);
        for (int i = 0; i < HistoryCache.MAX_ENTRIES + 10; i++) {
            cache.lastRuns("f" + i, 10);
        }
        assertThat(cache.size()).isLessThanOrEqualTo(HistoryCache.MAX_ENTRIES);
    }

    @Test
    void testServiceUsesCacheTtlFromConfig()
            throws Exception
    {
        FakeSource source = new FakeSource();
        String sql = "SELECT count(*) FROM orders WHERE orderkey > 5";
        PredictorService cached = new PredictorService(config(2000), source);
        cached.predict(sql, Optional.of("tpch"), Optional.of("tiny"));
        cached.predict(sql, Optional.of("tpch"), Optional.of("tiny"));
        assertThat(source.calls).hasSize(1);

        source.calls.clear();
        PredictorService uncached = new PredictorService(config(0), source);
        uncached.predict(sql, Optional.of("tpch"), Optional.of("tiny"));
        uncached.predict(sql, Optional.of("tpch"), Optional.of("tiny"));
        assertThat(source.calls).hasSize(2);
    }

    private static PredictorConfig config(long cacheTtlMs)
    {
        return new PredictorConfig("jdbc:postgresql://db/history", Optional.empty(), Optional.empty(), 1L << 30, 2, 6, 1.2, 2.0, 10, 0, "10m", 3600, 2000, cacheTtlMs);
    }

    private static class FakeSource
            implements HistoryCache.HistorySource
    {
        final List<String> calls = new ArrayList<>();
        int runs = 1;
        boolean fail;

        @Override
        public List<HistoryRun> lastRuns(String fingerprint, int limit)
                throws SQLException
        {
            calls.add(fingerprint + "/" + limit);
            if (fail) {
                throw new SQLException("database down");
            }
            List<HistoryRun> result = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                result.add(new HistoryRun("FINISHED", Optional.empty(), 100, 50, Instant.EPOCH.plusSeconds(i)));
            }
            return result;
        }
    }
}
