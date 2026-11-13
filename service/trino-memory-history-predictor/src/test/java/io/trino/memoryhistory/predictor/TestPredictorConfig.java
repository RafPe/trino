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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestPredictorConfig
{
    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void testDefaults()
    {
        PredictorConfig config = PredictorConfig.fromEnvironment(Map.of("DB_URL", "jdbc:postgresql://db/history", "PER_WORKER_MEMORY_BYTES", "1073741824"));
        assertThat(config).isEqualTo(new PredictorConfig("jdbc:postgresql://db/history", Optional.empty(), Optional.empty(), GB, 2, 6, 1.2, 2.0, 10, 8090));
    }

    @Test
    void testExplicit()
    {
        PredictorConfig config = PredictorConfig.fromEnvironment(Map.of(
                "DB_URL", "jdbc:postgresql://db/history",
                "DB_USER", "user",
                "DB_PASSWORD", "password",
                "PER_WORKER_MEMORY_BYTES", "1000",
                "MIN_WORKERS", "1",
                "MAX_WORKERS", "3",
                "SAFETY_FACTOR", "1.5",
                "FAILURE_GROWTH_FACTOR", "3",
                "HISTORY_RUNS", "5",
                "PORT", "9000"));
        assertThat(config).isEqualTo(new PredictorConfig("jdbc:postgresql://db/history", Optional.of("user"), Optional.of("password"), 1000, 1, 3, 1.5, 3.0, 5, 9000));
    }

    @Test
    void testGateConfig()
    {
        PredictorConfig defaults = PredictorConfig.fromEnvironment(Map.of("DB_URL", "jdbc:postgresql://db/history", "PER_WORKER_MEMORY_BYTES", "1"));
        assertThat(defaults.gateMaxWait()).isEqualTo("10m");
        assertThat(defaults.gateReservationTtlSeconds()).isEqualTo(3600);
        assertThat(defaults.autoReleaseIntervalMs()).isEqualTo(2000);
        assertThat(defaults.historyCacheTtlMs()).isEqualTo(2000);

        PredictorConfig explicit = PredictorConfig.fromEnvironment(Map.of(
                "DB_URL", "jdbc:postgresql://db/history",
                "PER_WORKER_MEMORY_BYTES", "1",
                "GATE_MAX_WAIT", "90s",
                "GATE_RESERVATION_TTL_SECONDS", "600",
                "AUTO_RELEASE_INTERVAL_MS", "500",
                "HISTORY_CACHE_TTL_MS", "0"));
        assertThat(explicit.gateMaxWait()).isEqualTo("90s");
        assertThat(explicit.gateReservationTtlSeconds()).isEqualTo(600);
        assertThat(explicit.autoReleaseIntervalMs()).isEqualTo(500);
        assertThat(explicit.historyCacheTtlMs()).isEqualTo(0);

        assertThatThrownBy(() -> PredictorConfig.fromEnvironment(Map.of("DB_URL", "x", "PER_WORKER_MEMORY_BYTES", "1", "GATE_MAX_WAIT", "10 minutes")))
                .hasMessageContaining("GATE_MAX_WAIT");
        assertThatThrownBy(() -> PredictorConfig.fromEnvironment(Map.of("DB_URL", "x", "PER_WORKER_MEMORY_BYTES", "1", "GATE_RESERVATION_TTL_SECONDS", "0")))
                .hasMessageContaining("GATE_RESERVATION_TTL_SECONDS");
        assertThatThrownBy(() -> PredictorConfig.fromEnvironment(Map.of("DB_URL", "x", "PER_WORKER_MEMORY_BYTES", "1", "AUTO_RELEASE_INTERVAL_MS", "0")))
                .hasMessageContaining("AUTO_RELEASE_INTERVAL_MS");
        assertThatThrownBy(() -> PredictorConfig.fromEnvironment(Map.of("DB_URL", "x", "PER_WORKER_MEMORY_BYTES", "1", "HISTORY_CACHE_TTL_MS", "-1")))
                .hasMessageContaining("HISTORY_CACHE_TTL_MS");
    }

    @Test
    void testMissingRequired()
    {
        assertThatThrownBy(() -> PredictorConfig.fromEnvironment(Map.of("PER_WORKER_MEMORY_BYTES", "1")))
                .hasMessageContaining("DB_URL");
        assertThatThrownBy(() -> PredictorConfig.fromEnvironment(Map.of("DB_URL", "jdbc:postgresql://db/history")))
                .hasMessageContaining("PER_WORKER_MEMORY_BYTES");
    }

    @Test
    void testRequiredWorkers()
    {
        PredictorConfig config = config(2, 6, 1.2);
        // ceil(bytes * 1.2 / 1 GB), clamped to [2, 6]
        assertThat(config.requiredWorkers(0)).isEqualTo(2);
        assertThat(config.requiredWorkers(GB)).isEqualTo(2);
        assertThat(config.requiredWorkers(2 * GB)).isEqualTo(3);
        assertThat(config.requiredWorkers(3_400_000_000L)).isEqualTo(4);
        assertThat(config.requiredWorkers(5 * GB)).isEqualTo(6);
        assertThat(config.requiredWorkers(100 * GB)).isEqualTo(6);
        assertThat(config(0, 6, 1.0).requiredWorkers(GB + 1)).isEqualTo(2);
        assertThat(config(0, 6, 1.0).requiredWorkers(GB)).isEqualTo(1);
    }

    static PredictorConfig config(int minWorkers, int maxWorkers, double safetyFactor)
    {
        return new PredictorConfig("jdbc:postgresql://db/history", Optional.empty(), Optional.empty(), GB, minWorkers, maxWorkers, safetyFactor, 2.0, 10, 0);
    }
}
