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

import io.trino.memoryhistory.fingerprint.QueryFingerprint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.net.http.HttpRequest.BodyPublishers.ofString;

/**
 * Prepares the predictor before its port takes requests: without it, the first {@code /v1/gate} call took
 * more than 500 ms (class loading, the first database connection, cold code).
 * A warm-up failure is logged and never stops the start. The predictor then works as without the warm-up.
 * When the database cannot be reached, the warm-up stops at the first connection attempt
 * (at most the connect timeout of 3 s).
 */
public final class WarmUp
{
    // A query shape that no cluster runs, so the warm-up does not touch the history of a real fingerprint
    static final String SQL = "SELECT count(*) FROM memory_history_predictor_warm_up WHERE id > 0";
    static final Optional<String> CATALOG = Optional.of("memory_history_predictor");
    static final Optional<String> SCHEMA = Optional.of("warm_up");
    // The bench sends up to 8 queries at the same time; more connections open on demand
    static final int CONNECTIONS = 8;
    private static final int ITERATIONS = 100;
    private static final int HTTP_REQUESTS = 20;

    private static final Logger log = Logger.getLogger(WarmUp.class.getName());

    private WarmUp() {}

    /**
     * Opens the database connections, runs the prediction code and then the HTTP code.
     * Returns true when the warm-up worked.
     */
    public static boolean beforeStart(HistoryRepository repository, PredictorService service)
    {
        long start = System.nanoTime();
        try {
            String fingerprint = QueryFingerprint.compute(SQL, CATALOG, SCHEMA).orElseThrow().hash();
            PredictionRule.predict(List.of(), 2.0);
            // The first random UUID initializes SecureRandom
            UUID.randomUUID();
            int connections = repository.warmUp(CONNECTIONS, fingerprint);
            for (int i = 0; i < ITERATIONS; i++) {
                service.predict(SQL, CATALOG, SCHEMA);
            }
            http(service);
            log.info("Warm-up done in %s ms, %s database connections open".formatted(elapsedMillis(start), connections));
            return true;
        }
        catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.log(Level.WARNING, "Warm-up failed after %s ms; the predictor starts anyway".formatted(elapsedMillis(start)), e);
            return false;
        }
    }

    /**
     * Sends {@code POST /v1/predict} and {@code POST /v1/gate} requests to a server on a free loopback port,
     * so the HTTP and JSON code is loaded and compiled before the real port takes requests. That server has
     * its own reservations, which are dropped with it; the warm-up query is unknown anyway.
     */
    private static void http(PredictorService service)
            throws Exception
    {
        try (PredictorServer server = new PredictorServer(service, new Reservations(service.config(), InstantSource.system()), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            server.start();
            String body = "{\"sql\": \"%s\", \"catalog\": \"%s\", \"schema\": \"%s\"}".formatted(SQL, CATALOG.orElseThrow(), SCHEMA.orElseThrow());
            for (int i = 0; i < HTTP_REQUESTS; i++) {
                String path = i % 2 == 0 ? "/v1/predict" : "/v1/gate";
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://%s:%s%s".formatted(InetAddress.getLoopbackAddress().getHostAddress(), server.port(), path)))
                        .timeout(Duration.ofSeconds(15))
                        .POST(ofString(body))
                        .build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            }
        }
    }

    private static long elapsedMillis(long startNanos)
    {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
