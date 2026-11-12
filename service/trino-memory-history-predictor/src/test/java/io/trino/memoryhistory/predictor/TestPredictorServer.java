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
import io.trino.plugin.memoryhistory.MemoryHistoryConfig;
import io.trino.plugin.memoryhistory.QueryFingerprint;
import io.trino.plugin.memoryhistory.QueryHistoryRecord;
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
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;

import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
final class TestPredictorServer
{
    private static final long GB = 1024L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PostgreSQLContainer postgres;
    private PredictorServer server;
    private HttpClient client;

    @BeforeAll
    void setup()
            throws Exception
    {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();

        QueryHistoryStore store = new QueryHistoryStore(new MemoryHistoryConfig()
                .setUrl(postgres.getJdbcUrl())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword()));
        store.createTable();
        String fingerprint = fingerprint("SELECT count(*) FROM orders WHERE orderkey > 5").hash();
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        store.insert(row("q1", fingerprint, "FINISHED", Optional.empty(), 2 * GB, time));
        store.insert(row("q2", fingerprint, "FINISHED", Optional.empty(), 3 * GB, time.plusSeconds(10)));
        store.insert(row("q3", fingerprint, "FAILED", Optional.of("EXCEEDED_LOCAL_MEMORY_LIMIT"), GB, time.plusSeconds(20)));
        store.insert(row("q4", fingerprint, "FAILED", Optional.of("USER_CANCELED"), 50 * GB, time.plusSeconds(30)));

        PredictorConfig config = new PredictorConfig(postgres.getJdbcUrl(), Optional.of(postgres.getUsername()), Optional.of(postgres.getPassword()), GB, 2, 6, 1.2, 2.0, 10, 0);
        server = new PredictorServer(
                new PredictorService(config, new HistoryRepository(config)),
                new Reservations(config, InstantSource.system()),
                new InetSocketAddress("127.0.0.1", 0));
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    void teardown()
    {
        if (client != null) {
            client.close();
            client = null;
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
    void testPredictKnown()
            throws Exception
    {
        JsonNode response = post("/v1/predict", "{\"sql\": \"select count(*) from orders where orderkey > 123456\", \"catalog\": \"tpch\", \"schema\": \"tiny\"}", 200);
        assertThat(response.get("supported").asBoolean()).isTrue();
        assertThat(response.get("fingerprint").asText()).isEqualTo(fingerprint("SELECT count(*) FROM orders WHERE orderkey > 1").hash());
        assertThat(response.get("known").asBoolean()).isTrue();
        assertThat(response.get("runs").asInt()).isEqualTo(4);
        assertThat(response.get("successfulRuns").asInt()).isEqualTo(2);
        assertThat(response.get("memoryFailures").asInt()).isEqualTo(1);
        assertThat(response.get("predictedPeakUserMemoryBytes").asLong()).isEqualTo(3 * GB);
        assertThat(response.get("predictedPeakTaskUserMemoryBytes").asLong()).isEqualTo(3 * GB / 4);
        // ceil(3 GB * 1.2 / 1 GB) = 4
        assertThat(response.get("requiredWorkers").asInt()).isEqualTo(4);
        assertThat(response.get("basis").asText()).isEqualTo("max_of_last_successful_runs");
    }

    @Test
    void testPredictUnknown()
            throws Exception
    {
        JsonNode response = post("/v1/predict", "{\"sql\": \"SELECT * FROM lineitem WHERE quantity > 3\", \"catalog\": \"tpch\", \"schema\": \"tiny\"}", 200);
        assertThat(response.get("supported").asBoolean()).isTrue();
        assertThat(response.get("known").asBoolean()).isFalse();
        assertThat(response.get("runs").asInt()).isEqualTo(0);
        assertThat(response.get("predictedPeakUserMemoryBytes").isNull()).isTrue();
        assertThat(response.get("predictedPeakTaskUserMemoryBytes").isNull()).isTrue();
        assertThat(response.get("requiredWorkers").isNull()).isTrue();
        assertThat(response.get("basis").isNull()).isTrue();
    }

    @Test
    void testPredictUnsupported()
            throws Exception
    {
        assertThat(post("/v1/predict", "{\"sql\": \"SHOW TABLES\"}", 200).toString()).isEqualTo("{\"supported\":false,\"known\":false}");
        assertThat(post("/v1/predict", "{\"sql\": \"SELEC broken\"}", 200).toString()).isEqualTo("{\"supported\":false,\"known\":false}");
        post("/v1/predict", "{}", 400);
        post("/v1/predict", "not json", 400);
    }

    @Test
    void testReservationsAndHealth()
            throws Exception
    {
        assertThat(get("/v1/health").toString()).isEqualTo("{\"status\":\"ok\"}");
        post("/v1/reservations", "{\"id\": \"r1\", \"predictedBytes\": %s, \"ttlSeconds\": 900}".formatted(3 * GB), 201);
        JsonNode required = get("/v1/required-workers");
        assertThat(required.get("workers").asInt()).isEqualTo(4);
        assertThat(required.get("reservations").asInt()).isEqualTo(1);
        assertThat(send(HttpRequest.newBuilder(uri("/v1/reservations/r1")).DELETE().build()).statusCode()).isEqualTo(204);
        assertThat(send(HttpRequest.newBuilder(uri("/v1/reservations/unknown")).DELETE().build()).statusCode()).isEqualTo(204);
        required = get("/v1/required-workers");
        assertThat(required.get("workers").asInt()).isEqualTo(2);
        assertThat(required.get("reservations").asInt()).isEqualTo(0);
        post("/v1/reservations", "{\"predictedBytes\": 1}", 400);
        assertThat(send(HttpRequest.newBuilder(uri("/v1/required-workers")).POST(noBody()).build()).statusCode()).isEqualTo(405);
    }

    private static QueryFingerprint fingerprint(String sql)
    {
        return QueryFingerprint.compute(sql, Optional.of("tpch"), Optional.of("tiny")).orElseThrow();
    }

    private static QueryHistoryRecord row(String queryId, String fingerprint, String state, Optional<String> errorCode, long peakUserMemoryBytes, Instant endTime)
    {
        return new QueryHistoryRecord(
                queryId,
                fingerprint,
                "normalized",
                "tpch.tiny.orders",
                state,
                errorCode,
                errorCode.map(_ -> "INSUFFICIENT_RESOURCES"),
                peakUserMemoryBytes,
                peakUserMemoryBytes / 4,
                peakUserMemoryBytes / 3,
                0,
                0,
                0,
                0,
                0,
                "user",
                Optional.empty(),
                "",
                "{}",
                endTime.minusSeconds(5),
                endTime);
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
