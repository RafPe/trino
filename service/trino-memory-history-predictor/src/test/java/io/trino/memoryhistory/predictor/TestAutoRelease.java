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
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.trino.memoryhistory.predictor.TestPredictorConfig.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestAutoRelease
{
    private static final long GB = 1024L * 1024 * 1024;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void testCursorWithOverlap()
            throws Exception
    {
        List<CompletedQuery> table = new ArrayList<>();
        List<Instant> reads = new ArrayList<>();
        Reservations reservations = new Reservations(config(2, 6, 1.2), () -> START);
        for (int i = 0; i < 4; i++) {
            reservations.addGate("r" + i, "f1", GB, Duration.ofDays(1));
        }

        try (AutoRelease autoRelease = new AutoRelease(from -> read(table, reads, from), reservations, Duration.ofSeconds(1), Duration.ofSeconds(60), START)) {
            assertThat(autoRelease.runOnce()).isEqualTo(0);
            assertThat(reads).containsExactly(START.minusSeconds(60));

            table.add(new CompletedQuery("q1", "f1", START.plusSeconds(100)));
            assertThat(autoRelease.runOnce()).isEqualTo(1);
            // the cursor moves to the newest end time, minus the overlap
            assertThat(autoRelease.runOnce()).isEqualTo(0);
            assertThat(reads.getLast()).isEqualTo(START.plusSeconds(40));

            // a row that is written late, but inside the overlap, is still seen once
            table.add(new CompletedQuery("q2", "f1", START.plusSeconds(50)));
            assertThat(autoRelease.runOnce()).isEqualTo(1);
            assertThat(autoRelease.runOnce()).isEqualTo(0);

            // a row that is written later than the overlap is not seen
            table.add(new CompletedQuery("q3", "f1", START.plusSeconds(10)));
            assertThat(autoRelease.runOnce()).isEqualTo(0);

            // a row of an other fingerprint releases nothing
            table.add(new CompletedQuery("q4", "f2", START.plusSeconds(200)));
            assertThat(autoRelease.runOnce()).isEqualTo(0);
            // the old ids are no longer needed after the cursor moves on
            table.add(new CompletedQuery("q5", "f1", START.plusSeconds(201)));
            assertThat(autoRelease.runOnce()).isEqualTo(1);
            assertThat(reads.getLast()).isEqualTo(START.plusSeconds(140));
        }
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(1);
    }

    @Test
    void testDatabaseErrorIsRetried()
            throws Exception
    {
        List<CompletedQuery> table = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        Reservations reservations = new Reservations(config(2, 6, 1.2), () -> START);
        reservations.addGate("r1", "f1", GB, Duration.ofDays(1));
        table.add(new CompletedQuery("q1", "f1", START.plusSeconds(1)));

        try (AutoRelease autoRelease = new AutoRelease(
                from -> {
                    if (fail.get()) {
                        throw new SQLException("connection refused");
                    }
                    return read(table, new ArrayList<>(), from);
                },
                reservations,
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                START)) {
            assertThatThrownBy(autoRelease::runOnce).isInstanceOf(SQLException.class);
            assertThat(reservations.requiredWorkers().reservations()).isEqualTo(1);
            fail.set(false);
            assertThat(autoRelease.runOnce()).isEqualTo(1);
        }
    }

    @Test
    void testScheduledRunSurvivesErrors()
            throws Exception
    {
        List<CompletedQuery> table = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        Reservations reservations = new Reservations(config(2, 6, 1.2), Instant::now);
        Instant start = Instant.now();
        reservations.addGate("r1", "f1", GB, Duration.ofDays(1));

        try (AutoRelease autoRelease = new AutoRelease(
                from -> {
                    if (fail.get()) {
                        throw new RuntimeException("failure");
                    }
                    return read(table, new ArrayList<>(), from);
                },
                reservations,
                Duration.ofMillis(20),
                Duration.ofSeconds(60),
                start)) {
            autoRelease.start();
            Thread.sleep(100);
            synchronized (table) {
                table.add(new CompletedQuery("q1", "f1", Instant.now().plusSeconds(1)));
            }
            fail.set(false);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (reservations.requiredWorkers().reservations() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(reservations.requiredWorkers().reservations()).isEqualTo(0);
        }
    }

    private static List<CompletedQuery> read(List<CompletedQuery> table, List<Instant> reads, Instant from)
    {
        synchronized (table) {
            reads.add(from);
            return table.stream()
                    .filter(row -> !row.endTime().isBefore(from))
                    .sorted(Comparator.comparing(CompletedQuery::endTime).thenComparing(CompletedQuery::queryId))
                    .toList();
        }
    }
}
