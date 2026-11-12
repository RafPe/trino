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

import com.google.inject.Inject;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Properties;

import static java.time.ZoneOffset.UTC;
import static java.util.Objects.requireNonNull;

/**
 * Writes {@link QueryHistoryRecord}s to the PostgreSQL {@code query_history} table.
 */
public class QueryHistoryStore
{
    static final String CREATE_TABLE =
            """
            CREATE TABLE IF NOT EXISTS query_history (
                query_id                     text PRIMARY KEY,
                fingerprint                  text NOT NULL,
                normalized_sql               text NOT NULL,
                tables                       text NOT NULL,
                query_state                  text NOT NULL,
                error_code                   text,
                error_type                   text,
                peak_user_memory_bytes       bigint NOT NULL,
                peak_task_user_memory_bytes  bigint NOT NULL,
                peak_task_total_memory_bytes bigint NOT NULL,
                physical_input_rows          bigint NOT NULL,
                physical_input_bytes         bigint NOT NULL,
                wall_time_ms                 bigint NOT NULL,
                cpu_time_ms                  bigint NOT NULL,
                queued_time_ms               bigint NOT NULL,
                user_name                    text,
                source                       text,
                client_tags                  text,
                session_properties           text,
                create_time                  timestamptz NOT NULL,
                end_time                     timestamptz NOT NULL
            )
            """;

    static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS query_history_fingerprint_idx ON query_history (fingerprint, end_time DESC)";

    private static final String INSERT =
            """
            INSERT INTO query_history (
                query_id, fingerprint, normalized_sql, tables, query_state, error_code, error_type,
                peak_user_memory_bytes, peak_task_user_memory_bytes, peak_task_total_memory_bytes,
                physical_input_rows, physical_input_bytes, wall_time_ms, cpu_time_ms, queued_time_ms,
                user_name, source, client_tags, session_properties, create_time, end_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (query_id) DO NOTHING
            """;

    private final String url;
    private final Properties properties;

    @Inject
    public QueryHistoryStore(MemoryHistoryConfig config)
    {
        this.url = requireNonNull(config.getUrl(), "url is null");
        this.properties = new Properties();
        config.getUser().ifPresent(user -> properties.setProperty("user", user));
        config.getPassword().ifPresent(password -> properties.setProperty("password", password));
    }

    public void createTable()
            throws SQLException
    {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
        }
    }

    public void insert(QueryHistoryRecord record)
            throws SQLException
    {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(INSERT)) {
            int index = 1;
            statement.setString(index++, record.queryId());
            statement.setString(index++, record.fingerprint());
            statement.setString(index++, record.normalizedSql());
            statement.setString(index++, record.tables());
            statement.setString(index++, record.queryState());
            setOptionalString(statement, index++, record.errorCode());
            setOptionalString(statement, index++, record.errorType());
            statement.setLong(index++, record.peakUserMemoryBytes());
            statement.setLong(index++, record.peakTaskUserMemoryBytes());
            statement.setLong(index++, record.peakTaskTotalMemoryBytes());
            statement.setLong(index++, record.physicalInputRows());
            statement.setLong(index++, record.physicalInputBytes());
            statement.setLong(index++, record.wallTimeMillis());
            statement.setLong(index++, record.cpuTimeMillis());
            statement.setLong(index++, record.queuedTimeMillis());
            statement.setString(index++, record.userName());
            setOptionalString(statement, index++, record.source());
            statement.setString(index++, record.clientTags());
            statement.setString(index++, record.sessionProperties());
            statement.setObject(index++, OffsetDateTime.ofInstant(record.createTime(), UTC));
            statement.setObject(index, OffsetDateTime.ofInstant(record.endTime(), UTC));
            statement.executeUpdate();
        }
    }

    private Connection connect()
            throws SQLException
    {
        // Use the driver directly: DriverManager does not see drivers loaded by the plugin class loader
        Connection connection = new Driver().connect(url, properties);
        if (connection == null) {
            throw new SQLException("Not a PostgreSQL JDBC URL: " + url);
        }
        return connection;
    }

    private static void setOptionalString(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException
    {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        }
        else {
            statement.setNull(index, Types.VARCHAR);
        }
    }
}
