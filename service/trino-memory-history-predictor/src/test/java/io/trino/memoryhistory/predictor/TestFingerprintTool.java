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
import io.trino.memoryhistory.fingerprint.QueryFingerprint;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

final class TestFingerprintTool
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testGoodRecord()
            throws Exception
    {
        JsonNode result = MAPPER.readTree(FingerprintTool.process(
                "{\"id\": 7, \"query\": \"SELECT * FROM orders WHERE orderkey = 42\", \"catalog\": \"tpch\", \"schema\": \"sf1\", \"other\": 1}"));
        QueryFingerprint expected = QueryFingerprint.compute("SELECT * FROM orders WHERE orderkey = 1", Optional.of("tpch"), Optional.of("sf1")).orElseThrow();
        assertThat(result.get("id").asInt()).isEqualTo(7);
        assertThat(result.get("supported").asBoolean()).isTrue();
        assertThat(result.get("fingerprint").asText()).isEqualTo(expected.hash());
        assertThat(result.get("normalizedSql").asText()).isEqualTo("select * from orders where orderkey = ?");
        assertThat(MAPPER.convertValue(result.get("tables"), List.class)).isEqualTo(List.of("tpch.sf1.orders"));
        assertThat(result.has("error")).isFalse();
    }

    @Test
    void testWithoutCatalogAndSchema()
            throws Exception
    {
        JsonNode result = MAPPER.readTree(FingerprintTool.process("{\"id\": \"a\", \"query\": \"SELECT 1 FROM t\", \"catalog\": null}"));
        assertThat(result.get("id").asText()).isEqualTo("a");
        assertThat(MAPPER.convertValue(result.get("tables"), List.class)).isEqualTo(List.of("?.?.t"));
    }

    @Test
    void testUnsupportedStatement()
            throws Exception
    {
        JsonNode result = MAPPER.readTree(FingerprintTool.process("{\"id\": 1, \"query\": \"SHOW TABLES\"}"));
        assertThat(result.get("supported").asBoolean()).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("statement not supported, or invalid SQL (starts with SHOW)");
        assertThat(result.has("fingerprint")).isFalse();
    }

    @Test
    void testInvalidSql()
            throws Exception
    {
        JsonNode result = MAPPER.readTree(FingerprintTool.process("{\"id\": 2, \"query\": \"/* c */ select from where 'secret'\"}"));
        assertThat(result.get("supported").asBoolean()).isFalse();
        assertThat(result.get("error").asText()).isEqualTo("invalid SQL (parser rejected a statement that starts with SELECT)");
        // the error text never holds the SQL
        assertThat(result.toString()).doesNotContain("secret");
    }

    @Test
    void testEmptyAndBadLines()
    {
        assertThat(FingerprintTool.process("")).isEqualTo("{\"id\":null,\"supported\":false,\"error\":\"empty line\"}");
        assertThat(FingerprintTool.process("   ")).isEqualTo("{\"id\":null,\"supported\":false,\"error\":\"empty line\"}");
        assertThat(FingerprintTool.process("{not json")).isEqualTo("{\"id\":null,\"supported\":false,\"error\":\"invalid JSON\"}");
        assertThat(FingerprintTool.process("[1]")).isEqualTo("{\"id\":null,\"supported\":false,\"error\":\"invalid JSON\"}");
        assertThat(FingerprintTool.process("{\"id\": 3}")).isEqualTo("{\"id\":3,\"supported\":false,\"error\":\"no query text\"}");
    }

    @Test
    void testOneOutputLinePerInputLine()
            throws Exception
    {
        StringWriter output = new StringWriter();
        FingerprintTool.run(new StringReader("{\"id\": 1, \"query\": \"SELECT 1\"}\n\n{bad\n{\"id\": 4, \"query\": \"SELECT 2\"}\n"), output);
        List<String> lines = output.toString().lines().toList();
        assertThat(lines).hasSize(4);
        assertThat(MAPPER.readTree(lines.get(0)).get("supported").asBoolean()).isTrue();
        assertThat(MAPPER.readTree(lines.get(1)).get("error").asText()).isEqualTo("empty line");
        assertThat(MAPPER.readTree(lines.get(2)).get("error").asText()).isEqualTo("invalid JSON");
        assertThat(MAPPER.readTree(lines.get(3)).get("id").asInt()).isEqualTo(4);
    }

    @Test
    void testReason()
    {
        assertThat(FingerprintTool.reason("-- comment\n  ( select")).isEqualTo("invalid SQL (parser rejected a statement that starts with ()");
        assertThat(FingerprintTool.reason("create table t (a int)")).isEqualTo("CREATE that is not CREATE TABLE AS, or invalid SQL");
        assertThat(FingerprintTool.reason("EXECUTE stmt")).startsWith("EXECUTE of a prepared statement");
        assertThat(FingerprintTool.reason("123")).isEqualTo("invalid SQL (no statement keyword)");
    }
}
