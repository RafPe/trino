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
package io.trino.plugin.memoryhistory;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One row of the {@code query_history} table.
 */
public record QueryHistoryRecord(
        String queryId,
        String fingerprint,
        String normalizedSql,
        String tables,
        String queryState,
        Optional<String> errorCode,
        Optional<String> errorType,
        long peakUserMemoryBytes,
        long peakTaskUserMemoryBytes,
        long peakTaskTotalMemoryBytes,
        long physicalInputRows,
        long physicalInputBytes,
        long wallTimeMillis,
        long cpuTimeMillis,
        long queuedTimeMillis,
        String userName,
        Optional<String> source,
        String clientTags,
        String sessionProperties,
        Instant createTime,
        Instant endTime)
{
    public QueryHistoryRecord
    {
        requireNonNull(queryId, "queryId is null");
        requireNonNull(fingerprint, "fingerprint is null");
        requireNonNull(normalizedSql, "normalizedSql is null");
        requireNonNull(tables, "tables is null");
        requireNonNull(queryState, "queryState is null");
        requireNonNull(errorCode, "errorCode is null");
        requireNonNull(errorType, "errorType is null");
        requireNonNull(userName, "userName is null");
        requireNonNull(source, "source is null");
        requireNonNull(clientTags, "clientTags is null");
        requireNonNull(sessionProperties, "sessionProperties is null");
        requireNonNull(createTime, "createTime is null");
        requireNonNull(endTime, "endTime is null");
    }
}
