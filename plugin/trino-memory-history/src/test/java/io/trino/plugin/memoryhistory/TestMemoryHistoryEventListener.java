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

import com.google.common.collect.ImmutableMap;
import io.trino.plugin.base.eventlistener.testing.TestingEventListenerContext;
import io.trino.spi.eventlistener.EventListener;
import io.trino.spi.eventlistener.QueryCompletedEvent;
import io.trino.spi.eventlistener.QueryContext;
import io.trino.spi.eventlistener.QueryFailureInfo;
import io.trino.spi.eventlistener.QueryIOMetadata;
import io.trino.spi.eventlistener.QueryMetadata;
import io.trino.spi.eventlistener.QueryStatistics;
import io.trino.spi.session.ResourceEstimates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.trino.spi.StandardErrorCode.EXCEEDED_LOCAL_MEMORY_LIMIT;
import static io.trino.spi.type.TimeZoneKey.UTC_KEY;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
final class TestMemoryHistoryEventListener
{
    private static final Instant CREATE_TIME = Instant.parse("2026-01-02T03:04:05.123Z");
    private static final Instant END_TIME = Instant.parse("2026-01-02T03:05:06.456Z");

    private PostgreSQLContainer postgres;
    private MemoryHistoryEventListener listener;

    @BeforeAll
    void setup()
    {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        EventListener eventListener = new MemoryHistoryEventListenerFactory().create(
                Map.of(
                        "memory-history.db.url", postgres.getJdbcUrl(),
                        "memory-history.db.user", postgres.getUsername(),
                        "memory-history.db.password", postgres.getPassword()),
                new TestingEventListenerContext());
        assertThat(eventListener).isInstanceOf(MemoryHistoryEventListener.class);
        listener = (MemoryHistoryEventListener) eventListener;
    }

    @AfterAll
    void teardown()
    {
        if (listener != null) {
            listener.shutdown();
            listener = null;
        }
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @Test
    void testFinishedQuery()
            throws Exception
    {
        listener.queryCompleted(event(
                "finished_1",
                "SELECT count(*) FROM orders WHERE orderkey > 5",
                Optional.empty(),
                "FINISHED",
                Optional.empty(),
                1000));
        listener.queryCompleted(event(
                "finished_2",
                "EXECUTE stmt USING 7",
                Optional.of("SELECT count(*) FROM orders WHERE orderkey > 99"),
                "FINISHED",
                Optional.empty(),
                2000));
        assertThat(listener.awaitIdle(30_000)).isTrue();

        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT * FROM query_history WHERE query_id IN ('finished_1', 'finished_2') ORDER BY query_id")) {
            assertThat(resultSet.next()).isTrue();
            String fingerprint = resultSet.getString("fingerprint");
            assertThat(fingerprint).isEqualTo(QueryFingerprint.compute("SELECT count(*) FROM orders WHERE orderkey > 1", Optional.of("tpch"), Optional.of("tiny")).orElseThrow().hash());
            assertThat(resultSet.getString("normalized_sql")).isEqualTo("select count ( * ) from orders where orderkey > ?");
            assertThat(resultSet.getString("tables")).isEqualTo("tpch.tiny.orders");
            assertThat(resultSet.getString("query_state")).isEqualTo("FINISHED");
            assertThat(resultSet.getString("error_code")).isNull();
            assertThat(resultSet.getString("error_type")).isNull();
            assertThat(resultSet.getLong("peak_user_memory_bytes")).isEqualTo(1000);
            assertThat(resultSet.getLong("peak_task_user_memory_bytes")).isEqualTo(500);
            assertThat(resultSet.getLong("peak_task_total_memory_bytes")).isEqualTo(600);
            assertThat(resultSet.getLong("physical_input_bytes")).isEqualTo(4000);
            assertThat(resultSet.getLong("physical_input_rows")).isEqualTo(5000);
            assertThat(resultSet.getLong("cpu_time_ms")).isEqualTo(101);
            assertThat(resultSet.getLong("wall_time_ms")).isEqualTo(103);
            assertThat(resultSet.getLong("queued_time_ms")).isEqualTo(104);
            assertThat(resultSet.getString("user_name")).isEqualTo("alice");
            assertThat(resultSet.getString("source")).isEqualTo("gate");
            assertThat(resultSet.getString("client_tags")).isEqualTo("a=1,b=2");
            assertThat(resultSet.getString("session_properties")).isEqualTo("{\"join_distribution_type\":\"PARTITIONED\",\"task_concurrency\":\"4\"}");
            assertThat(resultSet.getTimestamp("create_time").toInstant()).isEqualTo(CREATE_TIME);
            assertThat(resultSet.getTimestamp("end_time").toInstant()).isEqualTo(END_TIME);

            // the prepared query is fingerprinted, not the EXECUTE statement
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("query_id")).isEqualTo("finished_2");
            assertThat(resultSet.getString("fingerprint")).isEqualTo(fingerprint);
            assertThat(resultSet.getLong("peak_user_memory_bytes")).isEqualTo(2000);
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    void testFailedQuery()
            throws Exception
    {
        QueryFailureInfo failure = new QueryFailureInfo(
                EXCEEDED_LOCAL_MEMORY_LIMIT.toErrorCode(),
                Optional.of("failureType"),
                Optional.of("failureMessage"),
                Optional.empty(),
                Optional.empty(),
                "failureJson");
        listener.queryCompleted(event("failed_1", "SELECT * FROM lineitem ORDER BY comment", Optional.empty(), "FAILED", Optional.of(failure), 7000));
        assertThat(listener.awaitIdle(30_000)).isTrue();

        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT query_state, error_code, error_type, peak_user_memory_bytes FROM query_history WHERE query_id = 'failed_1'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("query_state")).isEqualTo("FAILED");
            assertThat(resultSet.getString("error_code")).isEqualTo("EXCEEDED_LOCAL_MEMORY_LIMIT");
            assertThat(resultSet.getString("error_type")).isEqualTo("INSUFFICIENT_RESOURCES");
            assertThat(resultSet.getLong("peak_user_memory_bytes")).isEqualTo(7000);
        }
    }

    @Test
    void testQueryWithoutFingerprintSkipped()
            throws Exception
    {
        listener.queryCompleted(event("skipped_show", "SHOW TABLES", Optional.empty(), "FINISHED", Optional.empty(), 1));
        listener.queryCompleted(event("skipped_invalid", "SELEC broken", Optional.empty(), "FAILED", Optional.empty(), 1));
        assertThat(listener.awaitIdle(30_000)).isTrue();

        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM query_history WHERE query_id LIKE 'skipped_%'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong(1)).isEqualTo(0);
        }
        assertThat(listener.failedInserts()).isEqualTo(0);
        assertThat(listener.droppedRecords()).isEqualTo(0);
    }

    @Test
    void testUnavailableDatabaseDoesNotThrow()
            throws Exception
    {
        MemoryHistoryEventListener unavailable = new MemoryHistoryEventListener(new QueryHistoryStore(
                new MemoryHistoryConfig().setUrl("jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1")));
        try {
            unavailable.queryCompleted(event("unavailable", "SELECT 1", Optional.empty(), "FINISHED", Optional.empty(), 1));
            assertThat(unavailable.awaitIdle(30_000)).isTrue();
            assertThat(unavailable.failedInserts()).isEqualTo(1);
        }
        finally {
            unavailable.shutdown();
        }
    }

    private Connection connect()
            throws SQLException
    {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static QueryCompletedEvent event(
            String queryId,
            String query,
            Optional<String> preparedQuery,
            String state,
            Optional<QueryFailureInfo> failure,
            long peakUserMemoryBytes)
    {
        QueryMetadata metadata = new QueryMetadata(
                queryId,
                Optional.empty(),
                Optional.empty(),
                query,
                Optional.empty(),
                preparedQuery,
                state,
                List.of(),
                List.of(),
                URI.create("http://localhost"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        QueryStatistics statistics = new QueryStatistics(
                ofMillis(101),
                ofMillis(102),
                ofMillis(103),
                ofMillis(104),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                peakUserMemoryBytes,
                500L,
                600L,
                4000L,
                5000L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0.0,
                0.0,
                List.of(),
                0,
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ImmutableMap.of(),
                ImmutableMap.of(),
                Optional.empty());

        QueryContext context = new QueryContext(
                "alice",
                "alice",
                Set.of(),
                Optional.empty(),
                Set.of(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Set.of("b=2", "a=1"),
                Set.of(),
                Optional.of("gate"),
                UTC_KEY.getId(),
                Optional.of("tpch"),
                Optional.of("tiny"),
                Optional.empty(),
                Map.of("task_concurrency", "4", "join_distribution_type", "PARTITIONED"),
                new ResourceEstimates(Optional.empty(), Optional.empty(), Optional.empty()),
                "serverAddress",
                "serverVersion",
                "environment",
                Optional.empty(),
                "NONE");

        return new QueryCompletedEvent(
                metadata,
                statistics,
                context,
                new QueryIOMetadata(List.of(), Optional.empty()),
                Optional.empty(),
                failure,
                List.of(),
                CREATE_TIME,
                Instant.now(),
                END_TIME);
    }
}
