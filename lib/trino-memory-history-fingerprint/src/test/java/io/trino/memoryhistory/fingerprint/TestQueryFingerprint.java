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

import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

final class TestQueryFingerprint
{
    private static final Optional<String> CATALOG = Optional.of("tpch");
    private static final Optional<String> SCHEMA = Optional.of("tiny");

    @Test
    void testLiteralsIgnored()
    {
        QueryFingerprint first = fingerprint("SELECT count(*) FROM orders WHERE orderkey > 5 AND comment = 'abc' AND totalprice < 1.5 AND x = 1e3 AND d = DATE '2020-01-01' AND b = X'00'");
        QueryFingerprint second = fingerprint("SELECT count(*) FROM orders WHERE orderkey > 77 AND comment = 'xyz' AND totalprice < 9.25 AND x = 2e7 AND d = DATE '2021-12-31' AND b = X'ff'");
        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(first.normalizedSql())
                .isEqualTo("select count ( * ) from orders where orderkey > ? and comment = ? and totalprice < ? and x = ? and d = date ? and b = ?");
        assertThat(first.tables()).containsExactly("tpch.tiny.orders");
        assertThat(first.hash()).matches("[0-9a-f]{64}");
    }

    @Test
    void testHashDefinition()
    {
        QueryFingerprint fingerprint = fingerprint("SELECT 1 FROM orders");
        assertThat(fingerprint.hash())
                .isEqualTo(Hashing.sha256()
                        .hashString(fingerprint.normalizedSql() + "\n" + String.join(",", fingerprint.tables()), UTF_8)
                        .toString());
    }

    @Test
    void testDifferentTableOrSchema()
    {
        String hash = fingerprint("SELECT count(*) FROM orders").hash();
        assertThat(fingerprint("SELECT count(*) FROM lineitem").hash()).isNotEqualTo(hash);
        assertThat(QueryFingerprint.compute("SELECT count(*) FROM orders", CATALOG, Optional.of("sf1")).orElseThrow().hash()).isNotEqualTo(hash);
        assertThat(QueryFingerprint.compute("SELECT count(*) FROM orders", Optional.of("tpcds"), SCHEMA).orElseThrow().hash()).isNotEqualTo(hash);
        assertThat(fingerprint("SELECT count(*) FROM sf1.orders").hash()).isNotEqualTo(hash);
    }

    @Test
    void testTableQualification()
    {
        assertThat(fingerprint("SELECT * FROM a, s.b, c.s.d, TPCH.TINY.Orders").tables())
                .containsExactly("c.s.d", "tpch.s.b", "tpch.tiny.a", "tpch.tiny.orders");
        assertThat(QueryFingerprint.compute("SELECT * FROM a, s.b", Optional.empty(), Optional.empty()).orElseThrow().tables())
                .containsExactly("?.?.a", "?.s.b");
        assertThat(fingerprint("SELECT * FROM orders o1 JOIN orders o2 ON o1.orderkey = o2.orderkey").tables())
                .containsExactly("tpch.tiny.orders");
    }

    @Test
    void testWhitespaceCaseAndCommentsIgnored()
    {
        String hash = fingerprint("SELECT count(*) FROM orders WHERE orderkey > 5").hash();
        assertThat(fingerprint("select   COUNT( * )\n\tfrom ORDERS -- comment\n where /* block\n comment */ OrderKey>5").hash()).isEqualTo(hash);
    }

    @Test
    void testInListLengthIgnored()
    {
        QueryFingerprint first = fingerprint("SELECT * FROM orders WHERE orderkey IN (1, 2, 3)");
        QueryFingerprint second = fingerprint("SELECT * FROM orders WHERE orderkey IN (4)");
        QueryFingerprint third = fingerprint("SELECT * FROM orders WHERE orderstatus IN ('F', 'O', 'P', 'X', 'Y')");
        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(first.normalizedSql()).isEqualTo("select * from orders where orderkey in ( ? )");
        assertThat(third.normalizedSql()).isEqualTo("select * from orders where orderstatus in ( ? )");
        // an IN list with a non-literal element is not collapsed
        assertThat(fingerprint("SELECT * FROM orders WHERE orderkey IN (1, custkey)").normalizedSql())
                .isEqualTo("select * from orders where orderkey in ( ? , custkey )");
    }

    @Test
    void testLimitValueKept()
    {
        QueryFingerprint first = fingerprint("SELECT * FROM orders WHERE orderkey > 1 LIMIT 10");
        QueryFingerprint second = fingerprint("SELECT * FROM orders WHERE orderkey > 1 LIMIT 20");
        assertThat(first.hash()).isNotEqualTo(second.hash());
        assertThat(first.normalizedSql()).isEqualTo("select * from orders where orderkey > ? limit 10");
    }

    @Test
    void testCteNameNotTable()
    {
        QueryFingerprint fingerprint = fingerprint("WITH big AS (SELECT * FROM orders WHERE totalprice > 100) SELECT count(*) FROM big JOIN tpch.sf1.customer c ON big.custkey = c.custkey");
        assertThat(fingerprint.tables()).containsExactly("tpch.sf1.customer", "tpch.tiny.orders");
    }

    @Test
    void testInsertAndCreateTableAsSelect()
    {
        assertThat(fingerprint("INSERT INTO memory.default.t SELECT * FROM orders WHERE orderkey = 3").tables())
                .containsExactly("memory.default.t", "tpch.tiny.orders");
        assertThat(fingerprint("CREATE TABLE memory.default.t AS SELECT * FROM orders WHERE orderkey = 3").tables())
                .containsExactly("tpch.tiny.orders");
    }

    @Test
    void testExecuteImmediate()
    {
        QueryFingerprint direct = fingerprint("SELECT count(*) FROM orders WHERE orderkey > 5");
        QueryFingerprint immediate = fingerprint("EXECUTE IMMEDIATE 'SELECT count(*) FROM orders WHERE orderkey > 6'");
        assertThat(immediate).isEqualTo(direct);
    }

    @Test
    void testUnsupportedStatementOrInvalidSql()
    {
        assertThat(QueryFingerprint.compute("SHOW TABLES", CATALOG, SCHEMA)).isEmpty();
        assertThat(QueryFingerprint.compute("CREATE TABLE t (x bigint)", CATALOG, SCHEMA)).isEmpty();
        assertThat(QueryFingerprint.compute("DROP TABLE orders", CATALOG, SCHEMA)).isEmpty();
        assertThat(QueryFingerprint.compute("EXECUTE IMMEDIATE 'SHOW TABLES'", CATALOG, SCHEMA)).isEmpty();
        assertThat(QueryFingerprint.compute("SELEC 1 FROM", CATALOG, SCHEMA)).isEmpty();
        assertThat(QueryFingerprint.compute("SELECT 'unterminated", CATALOG, SCHEMA)).isEmpty();
        assertThat(QueryFingerprint.compute("", CATALOG, SCHEMA)).isEmpty();
    }

    private static QueryFingerprint fingerprint(String sql)
    {
        return QueryFingerprint.compute(sql, CATALOG, SCHEMA).orElseThrow();
    }
}
