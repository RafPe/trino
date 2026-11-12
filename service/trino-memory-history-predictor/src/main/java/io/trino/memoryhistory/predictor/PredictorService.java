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

import io.trino.plugin.memoryhistory.QueryFingerprint;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Answers {@code POST /v1/predict}.
 */
public class PredictorService
{
    private final PredictorConfig config;
    private final HistoryRepository repository;

    public PredictorService(PredictorConfig config, HistoryRepository repository)
    {
        this.config = requireNonNull(config, "config is null");
        this.repository = requireNonNull(repository, "repository is null");
    }

    /**
     * Returns the JSON response body as an ordered map. Values may be null.
     */
    public Map<String, Object> predict(String sql, Optional<String> catalog, Optional<String> schema)
            throws SQLException
    {
        Optional<QueryFingerprint> fingerprint = QueryFingerprint.compute(sql, catalog, schema);
        Map<String, Object> response = new LinkedHashMap<>();
        if (fingerprint.isEmpty()) {
            response.put("supported", false);
            response.put("known", false);
            return response;
        }

        List<HistoryRun> runs = repository.lastRuns(fingerprint.get().hash(), config.historyRuns());
        Prediction prediction = PredictionRule.predict(runs, config.failureGrowthFactor());
        response.put("supported", true);
        response.put("fingerprint", fingerprint.get().hash());
        response.put("known", prediction.known());
        response.put("runs", prediction.runs());
        response.put("successfulRuns", prediction.successfulRuns());
        response.put("memoryFailures", prediction.memoryFailures());
        response.put("predictedPeakUserMemoryBytes", prediction.peakUserMemoryBytes().orElse(null));
        response.put("predictedPeakTaskUserMemoryBytes", prediction.peakTaskUserMemoryBytes().orElse(null));
        response.put("requiredWorkers", prediction.peakUserMemoryBytes().map(config::requiredWorkers).orElse(null));
        response.put("basis", prediction.basis().orElse(null));
        return response;
    }
}
