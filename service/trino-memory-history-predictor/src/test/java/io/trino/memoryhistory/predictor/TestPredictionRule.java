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

import java.util.List;
import java.util.Optional;

import static io.trino.memoryhistory.predictor.PredictionRule.BASIS_MEMORY_FAILURE_GROWTH;
import static io.trino.memoryhistory.predictor.PredictionRule.BASIS_SUCCESSFUL_RUNS;
import static org.assertj.core.api.Assertions.assertThat;

final class TestPredictionRule
{
    private static final double GROWTH = 2.0;

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
        Prediction prediction = PredictionRule.predict(List.of(failed("USER_CANCELED", 900, 90), failed("SYNTAX_ERROR", 0, 0)), GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(2, 0, 0, Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(prediction.known()).isFalse();
    }

    @Test
    void testMaxOfSuccessfulRuns()
    {
        Prediction prediction = PredictionRule.predict(
                List.of(finished(300, 30), finished(500, 20), finished(400, 50), failed("USER_CANCELED", 9000, 900)),
                GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(4, 3, 0, Optional.of(500L), Optional.of(50L), Optional.of(BASIS_SUCCESSFUL_RUNS)));
        assertThat(prediction.known()).isTrue();
    }

    @Test
    void testOnlyMemoryFailures()
    {
        Prediction prediction = PredictionRule.predict(
                List.of(failed("EXCEEDED_LOCAL_MEMORY_LIMIT", 1000, 100), failed("CLUSTER_OUT_OF_MEMORY", 1500, 80)),
                GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(2, 0, 2, Optional.of(3000L), Optional.of(200L), Optional.of(BASIS_MEMORY_FAILURE_GROWTH)));
    }

    @Test
    void testMemoryFailureAboveSuccessfulRuns()
    {
        Prediction prediction = PredictionRule.predict(
                List.of(failed("EXCEEDED_GLOBAL_MEMORY_LIMIT", 1000, 100), finished(800, 300)),
                1.5);
        // user memory: maxF 1000 > maxS 800 -> 1500; task memory: maxF 100 < maxS 300 -> 300
        assertThat(prediction).isEqualTo(new Prediction(2, 1, 1, Optional.of(1500L), Optional.of(300L), Optional.of(BASIS_MEMORY_FAILURE_GROWTH)));
    }

    @Test
    void testMemoryFailureBelowSuccessfulRuns()
    {
        Prediction prediction = PredictionRule.predict(
                List.of(failed("EXCEEDED_LOCAL_MEMORY_LIMIT", 700, 70), finished(800, 80)),
                GROWTH);
        assertThat(prediction).isEqualTo(new Prediction(2, 1, 1, Optional.of(800L), Optional.of(80L), Optional.of(BASIS_SUCCESSFUL_RUNS)));
    }

    private static HistoryRun finished(long peakUserMemoryBytes, long peakTaskUserMemoryBytes)
    {
        return new HistoryRun("FINISHED", Optional.empty(), peakUserMemoryBytes, peakTaskUserMemoryBytes);
    }

    private static HistoryRun failed(String errorCode, long peakUserMemoryBytes, long peakTaskUserMemoryBytes)
    {
        return new HistoryRun("FAILED", Optional.of(errorCode), peakUserMemoryBytes, peakTaskUserMemoryBytes);
    }
}
