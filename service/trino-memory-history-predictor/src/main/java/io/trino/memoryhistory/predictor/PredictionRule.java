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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Predicts peak memory from the recent runs of one fingerprint.
 * <p>
 * Successful runs give the peak directly. A run that failed with a memory error
 * stopped before reaching its real peak, so its peak is only a lower bound and is
 * multiplied by the failure growth factor.
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

    public static Prediction predict(List<HistoryRun> runs, double failureGrowthFactor)
    {
        List<HistoryRun> successful = runs.stream()
                .filter(run -> run.queryState().equals("FINISHED"))
                .toList();
        List<HistoryRun> memoryFailures = runs.stream()
                .filter(run -> run.errorCode().map(MEMORY_ERROR_CODES::contains).orElse(false))
                .toList();

        if (successful.isEmpty() && memoryFailures.isEmpty()) {
            return new Prediction(runs.size(), 0, 0, Optional.empty(), Optional.empty(), Optional.empty());
        }

        Estimate userMemory = estimate(successful, memoryFailures, HistoryRun::peakUserMemoryBytes, failureGrowthFactor);
        Estimate taskUserMemory = estimate(successful, memoryFailures, HistoryRun::peakTaskUserMemoryBytes, failureGrowthFactor);
        return new Prediction(
                runs.size(),
                successful.size(),
                memoryFailures.size(),
                Optional.of(userMemory.bytes()),
                Optional.of(taskUserMemory.bytes()),
                Optional.of(userMemory.basis()));
    }

    private static Estimate estimate(List<HistoryRun> successful, List<HistoryRun> memoryFailures, ToLongFunction<HistoryRun> peak, double failureGrowthFactor)
    {
        long maxSuccessful = successful.stream().mapToLong(peak).max().orElse(0);
        long maxFailed = memoryFailures.stream().mapToLong(peak).max().orElse(0);
        if (successful.isEmpty() || maxFailed > maxSuccessful) {
            return new Estimate((long) Math.ceil(maxFailed * failureGrowthFactor), BASIS_MEMORY_FAILURE_GROWTH);
        }
        return new Estimate(maxSuccessful, BASIS_SUCCESSFUL_RUNS);
    }

    private record Estimate(long bytes, String basis) {}
}
