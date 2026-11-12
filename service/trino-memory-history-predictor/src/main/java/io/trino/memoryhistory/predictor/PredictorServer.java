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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableMap;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.Objects.requireNonNull;

/**
 * HTTP API of the predictor: {@code /v1/predict}, {@code /v1/reservations},
 * {@code /v1/required-workers} and {@code /v1/health}.
 */
public class PredictorServer
        implements Closeable
{
    private static final Logger log = Logger.getLogger(PredictorServer.class.getName());
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final long DEFAULT_TTL_SECONDS = 900;
    private static final String RESERVATIONS_PATH = "/v1/reservations";

    private final PredictorService predictor;
    private final Reservations reservations;
    private final HttpServer server;
    private final ExecutorService executor;

    public PredictorServer(PredictorService predictor, Reservations reservations, InetSocketAddress address)
            throws IOException
    {
        this.predictor = requireNonNull(predictor, "predictor is null");
        this.reservations = requireNonNull(reservations, "reservations is null");
        this.server = HttpServer.create(address, 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/predict", exchange -> handle(exchange, this::predict));
        server.createContext(RESERVATIONS_PATH, exchange -> handle(exchange, this::reservations));
        server.createContext("/v1/required-workers", exchange -> handle(exchange, this::requiredWorkers));
        server.createContext("/v1/health", exchange -> handle(exchange, this::health));
        server.createContext("/", exchange -> handle(exchange, _ -> new Response(404, ImmutableMap.of("error", "not found"))));
    }

    public void start()
    {
        server.start();
    }

    public int port()
    {
        return server.getAddress().getPort();
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.close();
    }

    private Response predict(HttpExchange exchange)
            throws Exception
    {
        if (!exchange.getRequestMethod().equals("POST")) {
            return methodNotAllowed();
        }
        JsonNode request = readJson(exchange.getRequestBody());
        Optional<String> sql = text(request, "sql");
        if (sql.isEmpty()) {
            return badRequest("sql is required");
        }
        return new Response(200, predictor.predict(sql.get(), text(request, "catalog"), text(request, "schema")));
    }

    private Response reservations(HttpExchange exchange)
            throws Exception
    {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(RESERVATIONS_PATH) || path.equals(RESERVATIONS_PATH + "/")) {
            if (!exchange.getRequestMethod().equals("POST")) {
                return methodNotAllowed();
            }
            JsonNode request = readJson(exchange.getRequestBody());
            Optional<String> id = text(request, "id");
            JsonNode predictedBytes = request.path("predictedBytes");
            if (id.isEmpty() || !predictedBytes.canConvertToLong() || predictedBytes.asLong() < 0) {
                return badRequest("id and a non-negative predictedBytes are required");
            }
            long ttlSeconds = request.path("ttlSeconds").asLong(DEFAULT_TTL_SECONDS);
            if (ttlSeconds <= 0) {
                return badRequest("ttlSeconds must be positive");
            }
            reservations.add(id.get(), predictedBytes.asLong(), Duration.ofSeconds(ttlSeconds));
            return new Response(201, ImmutableMap.of("id", id.get()));
        }
        if (!exchange.getRequestMethod().equals("DELETE")) {
            return methodNotAllowed();
        }
        reservations.remove(path.substring(RESERVATIONS_PATH.length() + 1));
        return new Response(204, null);
    }

    private Response requiredWorkers(HttpExchange exchange)
    {
        if (!exchange.getRequestMethod().equals("GET")) {
            return methodNotAllowed();
        }
        Reservations.RequiredWorkers required = reservations.requiredWorkers();
        return new Response(200, ImmutableMap.of("workers", required.workers(), "reservations", required.reservations()));
    }

    private Response health(HttpExchange exchange)
    {
        if (!exchange.getRequestMethod().equals("GET")) {
            return methodNotAllowed();
        }
        return new Response(200, ImmutableMap.of("status", "ok"));
    }

    private static void handle(HttpExchange exchange, Handler handler)
            throws IOException
    {
        Response response;
        try {
            response = handler.handle(exchange);
        }
        catch (InvalidRequestException e) {
            response = badRequest(e.getMessage());
        }
        catch (Exception e) {
            log.log(Level.WARNING, "Request failed: " + exchange.getRequestMethod() + " " + exchange.getRequestURI(), e);
            response = new Response(500, ImmutableMap.of("error", String.valueOf(e.getMessage())));
        }
        try (exchange) {
            if (response.body() == null) {
                exchange.sendResponseHeaders(response.status(), -1);
                return;
            }
            byte[] body = MAPPER.writeValueAsBytes(response.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private static JsonNode readJson(InputStream input)
    {
        try (input) {
            JsonNode node = MAPPER.readTree(input);
            if (node == null || !node.isObject()) {
                throw new InvalidRequestException("request body must be a JSON object");
            }
            return node;
        }
        catch (IOException e) {
            throw new InvalidRequestException("invalid JSON: " + e.getMessage());
        }
    }

    private static Optional<String> text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new InvalidRequestException(field + " must be a string");
        }
        return Optional.of(value.asText());
    }

    private static Response badRequest(String message)
    {
        return new Response(400, ImmutableMap.of("error", message));
    }

    private static Response methodNotAllowed()
    {
        return new Response(405, ImmutableMap.of("error", "method not allowed"));
    }

    private interface Handler
    {
        Response handle(HttpExchange exchange)
                throws Exception;
    }

    private record Response(int status, Object body) {}

    private static class InvalidRequestException
            extends RuntimeException
    {
        public InvalidRequestException(String message)
        {
            super(message);
        }
    }
}
