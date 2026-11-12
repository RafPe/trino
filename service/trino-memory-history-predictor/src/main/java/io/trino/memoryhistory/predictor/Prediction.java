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

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record Prediction(
        int runs,
        int successfulRuns,
        int memoryFailures,
        Optional<Long> peakUserMemoryBytes,
        Optional<Long> peakTaskUserMemoryBytes,
        Optional<String> basis)
{
    public Prediction
    {
        requireNonNull(peakUserMemoryBytes, "peakUserMemoryBytes is null");
        requireNonNull(peakTaskUserMemoryBytes, "peakTaskUserMemoryBytes is null");
        requireNonNull(basis, "basis is null");
    }

    public boolean known()
    {
        return peakUserMemoryBytes.isPresent();
    }
}
