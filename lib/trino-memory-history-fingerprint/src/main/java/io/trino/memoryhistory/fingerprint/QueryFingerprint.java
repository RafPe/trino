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

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import io.trino.grammar.sql.SqlBaseLexer;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.CreateTableAsSelect;
import io.trino.sql.tree.DefaultTraversalVisitor;
import io.trino.sql.tree.ExecuteImmediate;
import io.trino.sql.tree.Insert;
import io.trino.sql.tree.Query;
import io.trino.sql.tree.Statement;
import io.trino.sql.tree.Table;
import io.trino.sql.tree.WithQuery;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

/**
 * Literal-insensitive fingerprint of a SQL statement. Two statements that differ
 * only in literal values, whitespace, comments, letter case or IN-list length
 * have the same fingerprint.
 */
public record QueryFingerprint(String hash, String normalizedSql, List<String> tables)
{
    private static final SqlParser SQL_PARSER = new SqlParser();
    private static final String PLACEHOLDER = "?";
    private static final String UNKNOWN = "?";

    public QueryFingerprint
    {
        requireNonNull(hash, "hash is null");
        requireNonNull(normalizedSql, "normalizedSql is null");
        tables = ImmutableList.copyOf(requireNonNull(tables, "tables is null"));
    }

    /**
     * Returns the fingerprint of {@code sql}, or empty when the SQL cannot be parsed
     * or is not a query, INSERT or CREATE TABLE AS SELECT. Never throws.
     */
    public static Optional<QueryFingerprint> compute(String sql, Optional<String> catalog, Optional<String> schema)
    {
        requireNonNull(sql, "sql is null");
        requireNonNull(catalog, "catalog is null");
        requireNonNull(schema, "schema is null");
        try {
            Statement statement = SQL_PARSER.createStatement(sql);
            if (statement instanceof ExecuteImmediate executeImmediate) {
                sql = executeImmediate.getStatement().getValue();
                statement = SQL_PARSER.createStatement(sql);
            }
            if (!(statement instanceof Query || statement instanceof Insert || statement instanceof CreateTableAsSelect)) {
                return Optional.empty();
            }
            List<String> tables = extractTables(statement, catalog, schema);
            String normalizedSql = normalize(sql);
            String hash = Hashing.sha256()
                    .hashString(normalizedSql + "\n" + String.join(",", tables), UTF_8)
                    .toString();
            return Optional.of(new QueryFingerprint(hash, normalizedSql, tables));
        }
        catch (RuntimeException | StackOverflowError e) {
            // ParsingException for invalid SQL, or anything unexpected: no fingerprint
            return Optional.empty();
        }
    }

    static List<String> extractTables(Statement statement, Optional<String> catalog, Optional<String> schema)
    {
        TableCollector collector = new TableCollector();
        collector.process(statement, null);

        String defaultCatalog = catalog.map(value -> value.toLowerCase(ENGLISH)).orElse(UNKNOWN);
        String defaultSchema = schema.map(value -> value.toLowerCase(ENGLISH)).orElse(UNKNOWN);
        return collector.tables.stream()
                .filter(parts -> parts.size() != 1 || !collector.cteNames.contains(parts.getFirst()))
                .map(parts -> switch (parts.size()) {
                    case 1 -> String.join(".", defaultCatalog, defaultSchema, parts.getFirst());
                    case 2 -> String.join(".", defaultCatalog, parts.get(0), parts.get(1));
                    default -> String.join(".", parts);
                })
                .distinct()
                .sorted()
                .collect(toImmutableList());
    }

    static String normalize(String sql)
    {
        SqlBaseLexer lexer = new SqlBaseLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();

        List<String> tokens = new ArrayList<>();
        int previousType = Token.INVALID_TYPE;
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            int type = token.getType();
            switch (type) {
                case SqlBaseLexer.WS, SqlBaseLexer.SIMPLE_COMMENT, SqlBaseLexer.BRACKETED_COMMENT -> {
                    continue;
                }
                case SqlBaseLexer.INTEGER_VALUE -> {
                    if (previousType == SqlBaseLexer.LIMIT) {
                        tokens.add(token.getText());
                    }
                    else {
                        tokens.add(PLACEHOLDER);
                    }
                }
                case SqlBaseLexer.STRING,
                     SqlBaseLexer.UNICODE_STRING,
                     SqlBaseLexer.BINARY_LITERAL,
                     SqlBaseLexer.DECIMAL_VALUE,
                     SqlBaseLexer.DOUBLE_VALUE -> tokens.add(PLACEHOLDER);
                default -> tokens.add(token.getText().toLowerCase(ENGLISH));
            }
            previousType = type;
        }
        return String.join(" ", collapseInLists(tokens));
    }

    // in ( ? , ? , ... ) -> in ( ? )
    private static List<String> collapseInLists(List<String> tokens)
    {
        List<String> result = new ArrayList<>(tokens.size());
        int index = 0;
        while (index < tokens.size()) {
            int end = placeholderInListEnd(tokens, index);
            if (end > 0) {
                result.addAll(List.of("in", "(", PLACEHOLDER, ")"));
                index = end + 1;
            }
            else {
                result.add(tokens.get(index));
                index++;
            }
        }
        return result;
    }

    /**
     * Returns the index of the closing parenthesis when an IN list made only of
     * placeholders starts at {@code start}, or -1 otherwise.
     */
    private static int placeholderInListEnd(List<String> tokens, int start)
    {
        if (!tokens.get(start).equals("in") || start + 3 >= tokens.size() || !tokens.get(start + 1).equals("(")) {
            return -1;
        }
        int index = start + 2;
        while (index + 1 < tokens.size()) {
            if (!tokens.get(index).equals(PLACEHOLDER)) {
                return -1;
            }
            String next = tokens.get(index + 1);
            if (next.equals(")")) {
                return index + 1;
            }
            if (!next.equals(",")) {
                return -1;
            }
            index += 2;
        }
        return -1;
    }

    private static class TableCollector
            extends DefaultTraversalVisitor<Void>
    {
        private final List<List<String>> tables = new ArrayList<>();
        private final Set<String> cteNames = new HashSet<>();

        @Override
        protected Void visitTable(Table node, Void context)
        {
            tables.add(node.getName().getOriginalParts().stream()
                    .map(identifier -> identifier.getValue().toLowerCase(ENGLISH))
                    .collect(toImmutableList()));
            return super.visitTable(node, context);
        }

        @Override
        protected Void visitWithQuery(WithQuery node, Void context)
        {
            cteNames.add(node.getName().getValue().toLowerCase(ENGLISH));
            return super.visitWithQuery(node, context);
        }

        @Override
        protected Void visitInsert(Insert node, Void context)
        {
            process(node.getTable(), context);
            return super.visitInsert(node, context);
        }
    }
}
