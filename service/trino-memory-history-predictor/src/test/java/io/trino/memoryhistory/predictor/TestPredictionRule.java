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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static io.trino.memoryhistory.predictor.PredictionRule.BASIS_MEMORY_FAILURE_GROWTH;
import static io.trino.memoryhistory.predictor.PredictionRule.BASIS_SUCCESSFUL_RUNS;
import static org.assertj.core.api.Assertions.assertThat;

final class TestPredictionRule
{
    private static final double GROWTH = 2.0;
    private static final Instant NEWEST_END_TIME = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void testNoRuns()
    {
        Prediction prediction = PredictionRule.predict(List.of(), GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(prediction.known()).isFalse();
    }

    @Test
    void testOnlyOtherFailures()
    {
        Prediction prediction = PredictionRule.predict(newestFirst(failed("USER_CANCELED", 900, 90), failed("SYNTAX_ERROR", 0, 0)), GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(2, 0, 0, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(prediction.known()).isFalse();
    }

    @Test
    void testMaxOfSuccessfulRuns()
    {
        Prediction prediction = PredictionRule.predict(
                newestFirst(finished(300, 30), finished(500, 20), finished(400, 50), failed("USER_CANCELED", 9000, 900)),
                GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(4, 3, 0, Optional.of(500L), Optional.of(50L), Optional.of(BASIS_SUCCESSFUL_RUNS)));
        assertThat(prediction.known()).isTrue();
    }

    @Test
    void testOnlyMemoryFailures()
    {
        Prediction prediction = PredictionRule.predict(
                newestFirst(failed("EXCEEDED_LOCAL_MEMORY_LIMIT", 1000, 100), failed("CLUSTER_OUT_OF_MEMORY", 1500, 80)),
                GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(2, 0, 2, Optional.of(3000L), Optional.of(200L), Optional.of(BASIS_MEMORY_FAILURE_GROWTH)));
    }

    @Test
    void testOlderMemoryFailuresAboveSuccessfulRunsAreIgnored()
    {
        // TPC-H q18: three memory failures on a small cluster, then successes on a larger one
        Prediction prediction = PredictionRule.predict(
                newestFirst(
                        finished(1_600_000_000L, 400_000_000L),
                        finished(1_550_000_000L, 390_000_000L),
                        finished(1_600_000_000L, 410_000_000L),
                        failed("EXCEEDED_GLOBAL_MEMORY_LIMIT", 1_900_000_000L, 900_000_000L),
                        failed("EXCEEDED_GLOBAL_MEMORY_LIMIT", 1_900_000_000L, 900_000_000L),
                        failed("EXCEEDED_GLOBAL_MEMORY_LIMIT", 1_900_000_000L, 900_000_000L)),
                GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(6, 3, 3, Optional.of(1_600_000_000L), Optional.of(410_000_000L), Optional.of(BASIS_SUCCESSFUL_RUNS)));
    }

    @Test
    void testNewerMemoryFailureBelowSuccessfulRuns()
    {
        Prediction prediction = PredictionRule.predict(
                newestFirst(failed("EXCEEDED_LOCAL_MEMORY_LIMIT", 700, 70), finished(800, 80), finished(600, 60)),
                GROWTH);
        // max(maxS 800, maxF_recent 700) x 2; task memory: max(80, 70) x 2
        assertThat(prediction).isEqualTo(new Prediction(3, 2, 1, Optional.of(1600L), Optional.of(160L), Optional.of(BASIS_MEMORY_FAILURE_GROWTH)));
    }

    @Test
    void testNewerMemoryFailureAboveSuccessfulRuns()
    {
        Prediction prediction = PredictionRule.predict(
                newestFirst(failed("EXCEEDED_GLOBAL_MEMORY_LIMIT", 1000, 100), finished(800, 300)),
                1.5);
        // user memory: max(800, 1000) x 1.5; task memory: max(300, 100) x 1.5
        assertThat(prediction).isEqualTo(new Prediction(2, 1, 1, Optional.of(1500L), Optional.of(450L), Optional.of(BASIS_MEMORY_FAILURE_GROWTH)));
    }

    @Test
    void testOnlyFailuresNewerThanLastSuccessCount()
    {
        Prediction prediction = PredictionRule.predict(
                newestFirst(
                        failed("CLUSTER_OUT_OF_MEMORY", 500, 50),
                        finished(800, 80),
                        failed("EXCEEDED_LOCAL_MEMORY_LIMIT", 5000, 500),
                        finished(700, 70)),
                GROWTH);
        // the 5000 failure is older than the last success: max(800, 500) x 2
        assertThat(prediction).isEqualTo(new Prediction(4, 2, 2, Optional.of(1600L), Optional.of(160L), Optional.of(BASIS_MEMORY_FAILURE_GROWTH)));
    }

    @Test
    void testNewestOtherFailuresAreIgnored()
    {
        Prediction prediction = PredictionRule.predict(
                newestFirst(
                        failed("USER_CANCELED", 9000, 900),
                        failed("SYNTAX_ERROR", 0, 0),
                        finished(800, 80),
                        failed("EXCEEDED_LOCAL_MEMORY_LIMIT", 1000, 100)),
                GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(4, 1, 1, Optional.of(800L), Optional.of(80L), Optional.of(BASIS_SUCCESSFUL_RUNS)));
    }

    @Test
    void testOrderComesFromEndTime()
    {
        // same runs as the q18 case, but the list is oldest first
        List<HistoryRun> runs = new ArrayList<>(newestFirst(
                finished(1600, 400),
                finished(1500, 390),
                failed("EXCEEDED_GLOBAL_MEMORY_LIMIT", 1900, 900),
                failed("EXCEEDED_GLOBAL_MEMORY_LIMIT", 1900, 900)));
        Collections.reverse(runs);
        Prediction prediction = PredictionRule.predict(runs, GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(4, 2, 2, Optional.of(1600L), Optional.of(400L), Optional.of(BASIS_SUCCESSFUL_RUNS)));
    }

    /**
     * Gives the runs end times one minute apart, the first run being the newest.
     */
    private static List<HistoryRun> newestFirst(RunSpec... runs)
    {
        ImmutableList.Builder<HistoryRun> result = ImmutableList.builder();
        for (int i = 0; i < runs.length; i++) {
            RunSpec run = runs[i];
            result.add(new HistoryRun(run.queryState(), run.errorCode(), run.peakUserMemoryBytes(), run.peakTaskUserMemoryBytes(), NEWEST_END_TIME.minus(Duration.ofMinutes(i))));
        }
        return result.build();
    }

    private static RunSpec finished(long peakUserMemoryBytes, long peakTaskUserMemoryBytes)
    {
        return new RunSpec("FINISHED", Optional.empty(), peakUserMemoryBytes, peakTaskUserMemoryBytes);
    }

    private static RunSpec failed(String errorCode, long peakUserMemoryBytes, long peakTaskUserMemoryBytes)
    {
        return new RunSpec("FAILED", Optional.of(errorCode), peakUserMemoryBytes, peakTaskUserMemoryBytes);
    }

    private record RunSpec(String queryState, Optional<String> errorCode, long peakUserMemoryBytes, long peakTaskUserMemoryBytes) {}
}
