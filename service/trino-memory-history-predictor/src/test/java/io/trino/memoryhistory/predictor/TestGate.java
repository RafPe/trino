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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.trino.memoryhistory.fingerprint.QueryFingerprint;
import io.trino.plugin.memoryhistory.MemoryHistoryConfig;
import io.trino.plugin.memoryhistory.QueryHistoryStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;

import static io.trino.memoryhistory.predictor.TestPredictorServer.row;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
final class TestGate
{
    private static final long GB = 1024L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KNOWN_SQL = "SELECT count(*) FROM orders WHERE orderkey > 5";

    private PostgreSQLContainer postgres;
    private QueryHistoryStore store;
    private PredictorConfig config;
    private Reservations reservations;
    private PredictorServer server;
    private AutoRelease autoRelease;
    private HttpClient client;

    @BeforeAll
    void setup()
            throws Exception
    {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();

        store = new QueryHistoryStore(new MemoryHistoryConfig()
                .setUrl(postgres.getJdbcUrl())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword()));
        store.createTable();
        String fingerprint = fingerprint(KNOWN_SQL);
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        store.insert(row("q1", fingerprint, "FINISHED", Optional.empty(), 2 * GB, time));
        store.insert(row("q2", fingerprint, "FINISHED", Optional.empty(), 3 * GB, time.plusSeconds(10)));

        config = new PredictorConfig(postgres.getJdbcUrl(), Optional.of(postgres.getUsername()), Optional.of(postgres.getPassword()), GB, 2, 6, 1.2, 2.0, 10, 0, "7m", 3600, 200);
        HistoryRepository repository = new HistoryRepository(config);
        reservations = new Reservations(config, InstantSource.system());
        server = new PredictorServer(new PredictorService(config, repository), reservations, new InetSocketAddress("127.0.0.1", 0));
        server.start();
        autoRelease = new AutoRelease(repository::completedSince, reservations, Duration.ofMillis(config.autoReleaseIntervalMs()), AutoRelease.DEFAULT_OVERLAP, Instant.now());
        autoRelease.start();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    void teardown()
    {
        if (client != null) {
            client.close();
            client = null;
        }
        if (autoRelease != null) {
            autoRelease.close();
            autoRelease = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @Test
    void testGateKnownAndAutoRelease()
            throws Exception
    {
        String body = "{\"sql\": \"select count(*) from orders where orderkey > 42\", \"catalog\": \"tpch\", \"schema\": \"tiny\"}";
        JsonNode gate = post("/v1/gate", body, 200);
        assertThat(gate.get("known").asBoolean()).isTrue();
        assertThat(gate.get("fingerprint").asText()).isEqualTo(fingerprint(KNOWN_SQL));
        assertThat(gate.get("reservationId").asText()).isNotEmpty();
        assertThat(gate.get("sessionProperties").toString())
                .isEqualTo("{\"required_workers_count\":\"4\",\"required_workers_max_wait_time\":\"7m\"}");
        // all fields of /v1/predict, with the same values
        ObjectNode predictFields = gate.deepCopy();
        predictFields.remove("reservationId");
        predictFields.remove("sessionProperties");
        assertThat(predictFields).isEqualTo(post("/v1/predict", body, 200));

        JsonNode required = get("/v1/required-workers");
        assertThat(required.get("reservations").asInt()).isEqualTo(1);
        assertThat(required.get("workers").asInt()).isEqualTo(4);

        // a reservation made with /v1/reservations is not released by a completed query
        post("/v1/reservations", "{\"id\": \"api\", \"predictedBytes\": 1, \"ttlSeconds\": 900}", 201);
        assertThat(get("/v1/required-workers").get("reservations").asInt()).isEqualTo(2);

        Thread.sleep(10);
        store.insert(row("q-gated", fingerprint(KNOWN_SQL), "FINISHED", Optional.empty(), 3 * GB, Instant.now()));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (get("/v1/required-workers").get("reservations").asInt() > 1 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(get("/v1/required-workers").get("reservations").asInt()).isEqualTo(1);
        // more runs do not release the other reservation
        Thread.sleep(3 * config.autoReleaseIntervalMs());
        assertThat(get("/v1/required-workers").get("reservations").asInt()).isEqualTo(1);
        assertThat(send(HttpRequest.newBuilder(uri("/v1/reservations/api")).DELETE().build()).statusCode()).isEqualTo(204);
        assertThat(get("/v1/required-workers").get("reservations").asInt()).isEqualTo(0);
    }

    @Test
    void testGateUnknownAndUnsupported()
            throws Exception
    {
        int before = reservations.requiredWorkers().reservations();
        JsonNode unknown = post("/v1/gate", "{\"sql\": \"SELECT * FROM lineitem WHERE quantity > 3\", \"catalog\": \"tpch\", \"schema\": \"tiny\"}", 200);
        assertThat(unknown.get("supported").asBoolean()).isTrue();
        assertThat(unknown.get("known").asBoolean()).isFalse();
        assertThat(unknown.get("reservationId").isNull()).isTrue();
        assertThat(unknown.get("sessionProperties").toString()).isEqualTo("{}");

        assertThat(post("/v1/gate", "{\"sql\": \"SHOW TABLES\"}", 200).toString())
                .isEqualTo("{\"supported\":false,\"known\":false,\"reservationId\":null,\"sessionProperties\":{}}");
        post("/v1/gate", "{}", 400);
        assertThat(send(HttpRequest.newBuilder(uri("/v1/gate")).GET().build()).statusCode()).isEqualTo(405);
        assertThat(reservations.requiredWorkers().reservations()).isEqualTo(before);
    }

    @Test
    void testGateDatabaseUnavailable()
            throws Exception
    {
        PredictorConfig unavailableConfig = new PredictorConfig("jdbc:postgresql://127.0.0.1:1/none", Optional.empty(), Optional.empty(), GB, 2, 6, 1.2, 2.0, 10, 0);
        Reservations unavailableReservations = new Reservations(unavailableConfig, InstantSource.system());
        try (PredictorServer unavailable = new PredictorServer(
                new PredictorService(unavailableConfig, new HistoryRepository(unavailableConfig)),
                unavailableReservations,
                new InetSocketAddress("127.0.0.1", 0))) {
            unavailable.start();
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:%s/v1/gate".formatted(unavailable.port())))
                    .POST(ofString("{\"sql\": \"SELECT 1 FROM orders\"}"))
                    .build());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(unavailableReservations.requiredWorkers().reservations()).isEqualTo(0);
        }
    }

    @Test
    void testMissingTableIsEmpty()
            throws Exception
    {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS empty");
        }
        String url = postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=empty";
        PredictorConfig emptyConfig = new PredictorConfig(url, Optional.of(postgres.getUsername()), Optional.of(postgres.getPassword()), GB, 2, 6, 1.2, 2.0, 10, 0);
        HistoryRepository repository = new HistoryRepository(emptyConfig);
        assertThat(repository.completedSince(Instant.EPOCH)).isEmpty();
        try (AutoRelease empty = new AutoRelease(repository::completedSince, new Reservations(emptyConfig, InstantSource.system()), Duration.ofSeconds(1), AutoRelease.DEFAULT_OVERLAP, Instant.EPOCH)) {
            assertThat(empty.runOnce()).isEqualTo(0);
        }
    }

    private static String fingerprint(String sql)
    {
        return QueryFingerprint.compute(sql, Optional.of("tpch"), Optional.of("tiny")).orElseThrow().hash();
    }

    private JsonNode post(String path, String body, int expectedStatus)
            throws Exception
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).POST(ofString(body)).build());
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return MAPPER.readTree(response.body());
    }

    private JsonNode get(String path)
            throws Exception
    {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path)).GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request)
            throws Exception
    {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path)
    {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
