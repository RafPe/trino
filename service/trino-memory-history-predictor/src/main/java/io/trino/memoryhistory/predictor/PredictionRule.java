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

import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Predicts peak memory from the recent runs of one fingerprint.
 * <p>
 * Successful runs give the peak directly. A run that failed with a memory error
 * stopped before reaching its real peak, so its peak is only a lower bound and is
 * multiplied by the failure growth factor. A memory failure that is older than the
 * newest successful run is ignored: the later success shows the real need.
 */
public final class PredictionRule
{
    public static final String BASIS_SUCCESSFUL_RUNS = "max_of_last_successful_runs";
    public static final String BASIS_MEMORY_FAILURE_GROWTH = "memory_failure_growth";

    static final Set<String> MEMORY_ERROR_CODES = ImmutableSet.of(
            "EXCEEDED_LOCAL_MEMORY_LIMIT",
            "EXCEEDED_GLOBAL_MEMORY_LIMIT",
            "CLUSTER_OUT_OF_MEMORY");

    private PredictionRule() {}

    /**
     * The order of {@code runs} does not matter; the rule orders them by {@link HistoryRun#endTime()}.
     */
    public static Prediction predict(List<HistoryRun> runs, double failureGrowthFactor)
    {
        List<HistoryRun> successful = runs.stream()
                .filter(PredictionRule::isSuccessful)
                .toList();
        List<HistoryRun> memoryFailures = runs.stream()
                .filter(PredictionRule::isMemoryFailure)
                .toList();

        if (successful.isEmpty() && memoryFailures.isEmpty()) {
            return new Prediction(runs.size(), 0, 0, Optional.empty(), Optional.empty(), Optional.empty());
        }

        // memory failures newer than the newest success; all memory failures when there is no success
        Optional<Instant> lastSuccess = successful.stream()
                .map(HistoryRun::endTime)
                .max(Comparator.naturalOrder());
        List<HistoryRun> recentMemoryFailures = memoryFailures.stream()
                .filter(run -> lastSuccess.map(run.endTime()::isAfter).orElse(true))
                .toList();

        Estimate userMemory = estimate(successful, recentMemoryFailures, HistoryRun::peakUserMemoryBytes, failureGrowthFactor);
        Estimate taskUserMemory = estimate(successful, recentMemoryFailures, HistoryRun::peakTaskUserMemoryBytes, failureGrowthFactor);
        return new Prediction(
                runs.size(),
                successful.size(),
                memoryFailures.size(),
                Optional.of(userMemory.bytes()),
                Optional.of(taskUserMemory.bytes()),
                Optional.of(userMemory.basis()));
    }

    private static boolean isSuccessful(HistoryRun run)
    {
        return run.queryState().equals("FINISHED");
    }

    private static boolean isMemoryFailure(HistoryRun run)
    {
        return run.errorCode().map(MEMORY_ERROR_CODES::contains).orElse(false);
    }

    private static Estimate estimate(List<HistoryRun> successful, List<HistoryRun> recentMemoryFailures, ToLongFunction<HistoryRun> peak, double failureGrowthFactor)
    {
        long maxSuccessful = successful.stream().mapToLong(peak).max().orElse(0);
        if (recentMemoryFailures.isEmpty()) {
            return new Estimate(maxSuccessful, BASIS_SUCCESSFUL_RUNS);
        }
        // the query ran out of memory after its last success (or never succeeded): grow from the largest known peak
        long maxRecentFailed = recentMemoryFailures.stream().mapToLong(peak).max().orElse(0);
        long base = Math.max(maxSuccessful, maxRecentFailed);
        return new Estimate((long) Math.ceil(base * failureGrowthFactor), BASIS_MEMORY_FAILURE_GROWTH);
    }

    private record Estimate(long bytes, String basis) {}
}
