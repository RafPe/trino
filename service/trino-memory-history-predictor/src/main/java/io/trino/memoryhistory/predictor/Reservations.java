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

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Memory reserved by queries that are about to run. Held in memory only.
 */
public class Reservations
{
    private final PredictorConfig config;
    private final InstantSource clock;
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

    public Reservations(PredictorConfig config, InstantSource clock)
    {
        this.config = requireNonNull(config, "config is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public void add(String id, long predictedBytes, Duration ttl)
    {
        requireNonNull(id, "id is null");
        checkArgument(predictedBytes >= 0, "predictedBytes must not be negative");
        checkArgument(ttl.isPositive(), "ttl must be positive");
        reservations.put(id, new Reservation(predictedBytes, clock.instant().plus(ttl)));
    }

    public void remove(String id)
    {
        reservations.remove(id);
    }

    public RequiredWorkers requiredWorkers()
    {
        Instant now = clock.instant();
        reservations.values().removeIf(reservation -> !reservation.expiresAt().isAfter(now));
        long totalBytes = 0;
        int count = 0;
        for (Reservation reservation : reservations.values()) {
            totalBytes += reservation.predictedBytes();
            count++;
        }
        if (count == 0) {
            return new RequiredWorkers(config.minWorkers(), 0);
        }
        return new RequiredWorkers(config.requiredWorkers(totalBytes), count);
    }

    public record RequiredWorkers(int workers, int reservations) {}

    private record Reservation(long predictedBytes, Instant expiresAt) {}
}
