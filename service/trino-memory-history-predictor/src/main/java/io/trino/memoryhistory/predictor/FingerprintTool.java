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
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.trino.memoryhistory.fingerprint.QueryFingerprint;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;

/**
 * Offline fingerprint step of the query log analyzer. Reads JSON Lines on standard input,
 * each with {@code id}, {@code query} and the optional {@code catalog} and {@code schema}, and
 * writes one JSON line per input line to standard output: {@code id}, {@code supported} and
 * either {@code fingerprint}, {@code normalizedSql} and {@code tables}, or {@code error}.
 * A bad line gives a line with {@code supported: false}; the tool does not stop.
 * <p>
 * {@link QueryFingerprint} does not tell why it gives no fingerprint. The error text only names
 * the first keyword of the statement. Statements that start with SELECT, WITH, VALUES, TABLE,
 * a parenthesis or INSERT are always supported when they parse, so for them the reason is a parse error.
 */
public final class FingerprintTool
{
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern FIRST_WORD = Pattern.compile("\\A(?:\\s+|--[^\\n]*(?:\\n|\\z)|/\\*.*?\\*/)*(\\(|[A-Za-z_]+)", Pattern.DOTALL);
    private static final Set<String> QUERY_KEYWORDS = Set.of("SELECT", "WITH", "VALUES", "TABLE", "(", "INSERT");

    private FingerprintTool() {}

    public static void main(String[] args)
            throws IOException
    {
        run(new InputStreamReader(System.in, UTF_8), new OutputStreamWriter(System.out, UTF_8));
    }

    static void run(Reader input, Writer output)
            throws IOException
    {
        BufferedReader reader = new BufferedReader(input, 1 << 16);
        BufferedWriter writer = new BufferedWriter(output, 1 << 16);
        String line;
        while ((line = reader.readLine()) != null) {
            writer.write(process(line));
            writer.write('\n');
        }
        writer.flush();
    }

    static String process(String line)
    {
        ObjectNode result = MAPPER.createObjectNode();
        result.set("id", NullNode.getInstance());
        if (line.isBlank()) {
            return failure(result, "empty line");
        }
        JsonNode record;
        try {
            record = MAPPER.readTree(line);
        }
        catch (IOException e) {
            return failure(result, "invalid JSON");
        }
        if (record == null || !record.isObject()) {
            return failure(result, "invalid JSON");
        }
        result.set("id", record.path("id").isMissingNode() ? NullNode.getInstance() : record.get("id"));
        JsonNode query = record.path("query");
        if (!query.isTextual() || query.asText().isBlank()) {
            return failure(result, "no query text");
        }
        Optional<QueryFingerprint> fingerprint = QueryFingerprint.compute(query.asText(), text(record, "catalog"), text(record, "schema"));
        if (fingerprint.isEmpty()) {
            return failure(result, reason(query.asText()));
        }
        result.put("supported", true);
        result.put("fingerprint", fingerprint.get().hash());
        result.put("normalizedSql", fingerprint.get().normalizedSql());
        ArrayNode tables = result.putArray("tables");
        fingerprint.get().tables().forEach(tables::add);
        return write(result);
    }

    static String reason(String sql)
    {
        Matcher matcher = FIRST_WORD.matcher(sql);
        if (!matcher.find()) {
            return "invalid SQL (no statement keyword)";
        }
        String keyword = matcher.group(1).toUpperCase(ENGLISH);
        if (QUERY_KEYWORDS.contains(keyword)) {
            return "invalid SQL (parser rejected a statement that starts with " + keyword + ")";
        }
        if (keyword.equals("CREATE")) {
            return "CREATE that is not CREATE TABLE AS, or invalid SQL";
        }
        if (keyword.equals("EXECUTE")) {
            return "EXECUTE of a prepared statement, or EXECUTE IMMEDIATE with unsupported or invalid SQL";
        }
        return "statement not supported, or invalid SQL (starts with " + keyword + ")";
    }

    private static Optional<String> text(JsonNode record, String field)
    {
        JsonNode value = record.path(field);
        return value.isTextual() && !value.asText().isEmpty() ? Optional.of(value.asText()) : Optional.empty();
    }

    private static String failure(ObjectNode result, String error)
    {
        result.put("supported", false);
        result.put("error", error);
        return write(result);
    }

    private static String write(ObjectNode result)
    {
        try {
            return MAPPER.writeValueAsString(result);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
