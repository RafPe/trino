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

import com.google.common.collect.ImmutableList;
import io.trino.memoryhistory.predictor.HistoryRepository.CompletedQuery;
import io.trino.memoryhistory.predictor.Reservations.RequiredWorkers;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static io.trino.memoryhistory.predictor.TestPredictorConfig.config;
import static org.assertj.core.api.Assertions.assertThat;

final class TestReservations
{
    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void testRequiredWorkers()
    {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Reservations reservations = new Reservations(config(2, 6, 1.2), now::get);

        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(2, 0));

        // 2 GB * 1.2 / 1 GB = 2.4 -> 3
        reservations.add("a", 2 * GB, Duration.ofSeconds(60));
        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(3, 1));

        // (2 + 1.5) GB * 1.2 = 4.2 -> 5
        reservations.add("b", 3 * GB / 2, Duration.ofSeconds(120));
        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(5, 2));

        // replacing a reservation with the same id
        reservations.add("b", GB, Duration.ofSeconds(120));
        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(4, 2));

        // clamped to the maximum
        reservations.add("c", 10 * GB, Duration.ofSeconds(120));
        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(6, 3));
        reservations.remove("c");

        // a expires
        now.set(now.get().plusSeconds(60));
        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(2, 1));

        // removing an unknown id is fine
        reservations.remove("unknown");
        reservations.remove("b");
        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(2, 0));
    }

    @Test
    void testReleaseOldestMatching()
    {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Reservations reservations = new Reservations(config(2, 6, 1.2), now::get);
        reservations.addGate("old", "f1", GB, Duration.ofHours(1));
        now.set(now.get().plusSeconds(1));
        reservations.addGate("other-fingerprint", "f2", GB, Duration.ofHours(1));
        now.set(now.get().plusSeconds(1));
        reservations.addGate("new", "f1", GB, Duration.ofHours(1));
        now.set(now.get().plusSeconds(1));

        assertThat(reservations.releaseCompleted("f1", now.get())).contains("old");
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(2);
        assertThat(reservations.releaseCompleted("f1", now.get())).contains("new");
        assertThat(reservations.releaseCompleted("f1", now.get())).isEmpty();
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(1);
    }

    @Test
    void testReservationAfterEndTimeIsKept()
    {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Reservations reservations = new Reservations(config(2, 6, 1.2), now::get);
        Instant endTime = now.get();
        now.set(now.get().plusSeconds(1));
        reservations.addGate("later", "f1", GB, Duration.ofHours(1));

        assertThat(reservations.releaseCompleted("f1", endTime)).isEmpty();
        // created at the same instant as the end is not before it
        assertThat(reservations.releaseCompleted("f1", now.get())).isEmpty();
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(1);
        assertThat(reservations.releaseCompleted("f1", now.get().plusMillis(1))).contains("later");
    }

    @Test
    void testApiReservationIsNotReleased()
    {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Reservations reservations = new Reservations(config(2, 6, 1.2), now::get);
        reservations.add("f1", GB, Duration.ofHours(1));
        reservations.add("api", GB, Duration.ofHours(1));
        now.set(now.get().plusSeconds(1));

        // neither the id nor anything else links an API reservation to a fingerprint
        assertThat(reservations.releaseCompleted("f1", now.get())).isEmpty();
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(2);
    }

    @Test
    void testTwoRowsReleaseTwoReservations()
    {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Reservations reservations = new Reservations(config(2, 6, 1.2), now::get);
        // same creation instant: the order of creation decides
        reservations.addGate("first", "f1", GB, Duration.ofHours(1));
        reservations.addGate("second", "f1", GB, Duration.ofHours(1));
        reservations.addGate("third", "f1", GB, Duration.ofHours(1));
        now.set(now.get().plusSeconds(1));

        AutoRelease autoRelease = new AutoRelease(
                _ -> ImmutableList.of(
                        new CompletedQuery("q1", "f1", now.get()),
                        new CompletedQuery("q2", "f1", now.get())),
                reservations,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                now.get().minusSeconds(10));
        try (autoRelease) {
            assertThat(releaseOnce(autoRelease)).isEqualTo(2);
            // the same rows again release nothing
            assertThat(releaseOnce(autoRelease)).isEqualTo(0);
        }
        assertThat(reservations.releaseCompleted("f1", now.get())).contains("third");
    }

    @Test
    void testExpiredReservationIsNotReleased()
    {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Reservations reservations = new Reservations(config(2, 6, 1.2), now::get);
        reservations.addGate("short", "f1", GB, Duration.ofSeconds(10));
        reservations.addGate("long", "f1", GB, Duration.ofSeconds(100));
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(2);

        // the time limit still applies to gate reservations
        now.set(now.get().plusSeconds(10));
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(1);
        assertThat(reservations.releaseCompleted("f1", now.get())).contains("long");
        assertThat(reservations.requiredWorkers()).isEqualTo(new RequiredWorkers(2, 0));
    }

    private static int releaseOnce(AutoRelease autoRelease)
    {
        try {
            return autoRelease.runOnce();
        }
        catch (SQLException e) {
            throw new AssertionError(e);
        }
    }
}
