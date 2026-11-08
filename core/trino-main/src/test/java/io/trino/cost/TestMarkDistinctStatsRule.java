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
package io.trino.cost;

import io.trino.sql.planner.Symbol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DoubleType.DOUBLE;

public class TestMarkDistinctStatsRule
        extends BaseStatsCalculatorTest
{
    @Test
    public void testMarkDistinctPropagatesSourceStats()
    {
        SymbolStatsEstimate aStats = SymbolStatsEstimate.builder()
                .setNullsFraction(0.1)
                .setLowValue(1)
                .setHighValue(30)
                .setDistinctValuesCount(20)
                .build();
        SymbolStatsEstimate bStats = SymbolStatsEstimate.builder()
                .setNullsFraction(0.5)
                .setLowValue(13.5)
                .setHighValue(100)
                .setDistinctValuesCount(40)
                .build();

        tester()
                .assertStatsFor(pb -> {
                    Symbol a = pb.symbol("a", BIGINT);
                    Symbol b = pb.symbol("b", DOUBLE);
                    Symbol marker = pb.symbol("marker", BOOLEAN);
                    return pb.markDistinct(marker, List.of(a), pb.values(a, b));
                })
                .withSourceStats(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addSymbolStatistics(new Symbol(BIGINT, "a"), aStats)
                        .addSymbolStatistics(new Symbol(DOUBLE, "b"), bStats)
                        .build())
                .check(check -> check
                        .outputRowsCount(100)
                        .symbolStats("a", BIGINT, assertion -> assertion.isEqualTo(aStats))
                        .symbolStats("b", DOUBLE, assertion -> assertion.isEqualTo(bStats))
                        .symbolStatsUnknown("marker", BOOLEAN));
    }

    @Test
    public void testMarkDistinctWithUnknownSourceStats()
    {
        tester()
                .assertStatsFor(pb -> {
                    Symbol a = pb.symbol("a", BIGINT);
                    Symbol marker = pb.symbol("marker", BOOLEAN);
                    return pb.markDistinct(marker, List.of(a), pb.values(a));
                })
                .withSourceStats(PlanNodeStatsEstimate.unknown())
                .check(check -> check
                        .outputRowsCountUnknown()
                        .symbolStatsUnknown("a", BIGINT)
                        .symbolStatsUnknown("marker", BOOLEAN));
    }
}
