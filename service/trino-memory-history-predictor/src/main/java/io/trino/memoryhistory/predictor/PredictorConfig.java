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

import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record PredictorConfig(
        String dbUrl,
        Optional<String> dbUser,
        Optional<String> dbPassword,
        long perWorkerMemoryBytes,
        int minWorkers,
        int maxWorkers,
        double safetyFactor,
        double failureGrowthFactor,
        int historyRuns,
        int port)
{
    public PredictorConfig
    {
        requireNonNull(dbUrl, "dbUrl is null");
        requireNonNull(dbUser, "dbUser is null");
        requireNonNull(dbPassword, "dbPassword is null");
        checkArgument(perWorkerMemoryBytes > 0, "PER_WORKER_MEMORY_BYTES must be positive");
        checkArgument(minWorkers >= 0, "MIN_WORKERS must not be negative");
        checkArgument(maxWorkers >= minWorkers, "MAX_WORKERS must be at least MIN_WORKERS");
        checkArgument(safetyFactor > 0, "SAFETY_FACTOR must be positive");
        checkArgument(failureGrowthFactor > 0, "FAILURE_GROWTH_FACTOR must be positive");
        checkArgument(historyRuns > 0, "HISTORY_RUNS must be positive");
    }

    public static PredictorConfig fromEnvironment(Map<String, String> environment)
    {
        return new PredictorConfig(
                required(environment, "DB_URL"),
                Optional.ofNullable(environment.get("DB_USER")),
                Optional.ofNullable(environment.get("DB_PASSWORD")),
                Long.parseLong(required(environment, "PER_WORKER_MEMORY_BYTES")),
                Integer.parseInt(environment.getOrDefault("MIN_WORKERS", "2")),
                Integer.parseInt(environment.getOrDefault("MAX_WORKERS", "6")),
                Double.parseDouble(environment.getOrDefault("SAFETY_FACTOR", "1.2")),
                Double.parseDouble(environment.getOrDefault("FAILURE_GROWTH_FACTOR", "2.0")),
                Integer.parseInt(environment.getOrDefault("HISTORY_RUNS", "10")),
                Integer.parseInt(environment.getOrDefault("PORT", "8090")));
    }

    /**
     * {@code clamp(ceil(bytes × SAFETY_FACTOR / PER_WORKER_MEMORY_BYTES), MIN_WORKERS, MAX_WORKERS)}
     */
    public int requiredWorkers(long bytes)
    {
        double workers = Math.ceil(bytes * safetyFactor / perWorkerMemoryBytes);
        return (int) Math.clamp((long) workers, minWorkers, maxWorkers);
    }

    private static String required(Map<String, String> environment, String name)
    {
        String value = environment.get(name);
        checkArgument(value != null && !value.isBlank(), "Environment variable %s is required", name);
        return value;
    }
}
