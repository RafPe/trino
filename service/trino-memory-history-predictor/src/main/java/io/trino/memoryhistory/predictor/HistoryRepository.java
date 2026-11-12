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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static java.util.Objects.requireNonNull;

/**
 * Reads {@code query_history}. The table is created by the event listener.
 */
public class HistoryRepository
{
    private static final String SELECT_RUNS =
            """
            SELECT query_state, error_code, peak_user_memory_bytes, peak_task_user_memory_bytes
            FROM query_history
            WHERE fingerprint = ?
            ORDER BY end_time DESC
            LIMIT ?
            """;

    // PostgreSQL JDBC timeouts in seconds; parameters in the JDBC URL take precedence.
    // The gate waits for /v1/predict before it submits a query, so a database that cannot be
    // reached must fail fast; a slow query may take longer.
    private static final String CONNECT_TIMEOUT_SECONDS = "3";
    private static final String SOCKET_TIMEOUT_SECONDS = "10";
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final String url;
    private final Properties properties = new Properties();

    public HistoryRepository(PredictorConfig config)
    {
        this.url = requireNonNull(config.dbUrl(), "dbUrl is null");
        properties.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
        properties.setProperty("loginTimeout", CONNECT_TIMEOUT_SECONDS);
        properties.setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
        config.dbUser().ifPresent(user -> properties.setProperty("user", user));
        config.dbPassword().ifPresent(password -> properties.setProperty("password", password));
    }

    /**
     * Returns the newest runs of the fingerprint, newest first.
     */
    public List<HistoryRun> lastRuns(String fingerprint, int limit)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(url, properties);
                PreparedStatement statement = connection.prepareStatement(SELECT_RUNS)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, fingerprint);
            statement.setInt(2, limit);
            ImmutableList.Builder<HistoryRun> runs = ImmutableList.builder();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    runs.add(new HistoryRun(
                            resultSet.getString("query_state"),
                            Optional.ofNullable(resultSet.getString("error_code")),
                            resultSet.getLong("peak_user_memory_bytes"),
                            resultSet.getLong("peak_task_user_memory_bytes")));
                }
            }
            return runs.build();
        }
    }
}
