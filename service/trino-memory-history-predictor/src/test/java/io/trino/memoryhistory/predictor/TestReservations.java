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

import io.trino.memoryhistory.predictor.Reservations.RequiredWorkers;
import org.junit.jupiter.api.Test;

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
}
