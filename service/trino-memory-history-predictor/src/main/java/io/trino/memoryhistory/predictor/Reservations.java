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
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Memory reserved by queries that are about to run. Held in memory only.
 * <p>
 * A reservation made by {@code /v1/gate} also holds the fingerprint of the query. The automatic
 * release uses it to find the reservation when a query of that fingerprint completes.
 * Reservations made by {@code POST /v1/reservations} have no fingerprint and are never released
 * automatically.
 */
public class Reservations
{
    private final PredictorConfig config;
    private final InstantSource clock;
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    // Orders reservations that were created at the same instant
    private final AtomicLong sequence = new AtomicLong();

    public Reservations(PredictorConfig config, InstantSource clock)
    {
        this.config = requireNonNull(config, "config is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public void add(String id, long predictedBytes, Duration ttl)
    {
        add(id, Optional.empty(), predictedBytes, ttl);
    }

    public void addGate(String id, String fingerprint, long predictedBytes, Duration ttl)
    {
        add(id, Optional.of(requireNonNull(fingerprint, "fingerprint is null")), predictedBytes, ttl);
    }

    private void add(String id, Optional<String> gateFingerprint, long predictedBytes, Duration ttl)
    {
        requireNonNull(id, "id is null");
        checkArgument(predictedBytes >= 0, "predictedBytes must not be negative");
        checkArgument(ttl.isPositive(), "ttl must be positive");
        Instant now = clock.instant();
        reservations.put(id, new Reservation(predictedBytes, now, now.plus(ttl), gateFingerprint, sequence.incrementAndGet()));
    }

    public void remove(String id)
    {
        reservations.remove(id);
    }

    /**
     * Releases the oldest live reservation that was made by {@code /v1/gate} for {@code fingerprint}
     * before {@code endTime}. Returns its id, or empty when no reservation matches.
     */
    public synchronized Optional<String> releaseCompleted(String fingerprint, Instant endTime)
    {
        requireNonNull(fingerprint, "fingerprint is null");
        requireNonNull(endTime, "endTime is null");
        while (true) {
            Instant now = clock.instant();
            Optional<Map.Entry<String, Reservation>> oldest = reservations.entrySet().stream()
                    .filter(entry -> entry.getValue().expiresAt().isAfter(now))
                    .filter(entry -> entry.getValue().gateFingerprint().equals(Optional.of(fingerprint)))
                    .filter(entry -> entry.getValue().createdAt().isBefore(endTime))
                    .min(Comparator.comparing((Map.Entry<String, Reservation> entry) -> entry.getValue().createdAt())
                            .thenComparingLong(entry -> entry.getValue().sequence()));
            if (oldest.isEmpty()) {
                return Optional.empty();
            }
            // A concurrent DELETE or a new reservation with the same id may change the entry; then look again
            if (reservations.remove(oldest.get().getKey(), oldest.get().getValue())) {
                return Optional.of(oldest.get().getKey());
            }
        }
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

    private record Reservation(long predictedBytes, Instant createdAt, Instant expiresAt, Optional<String> gateFingerprint, long sequence) {}
}
