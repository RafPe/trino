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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.metadata.ResolvedFunction;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.spi.connector.SortOrder;
import io.trino.sql.ir.Expression;
import io.trino.sql.planner.OrderingScheme;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.DataOrganizationSpecification;
import io.trino.sql.planner.plan.WindowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.sql.analyzer.TypeDescriptorProvider.fromTypes;
import static io.trino.sql.planner.plan.WindowNode.Frame.DEFAULT_FRAME;

public class TestWindowStatsRule
        extends BaseStatsCalculatorTest
{
    private static final TestingFunctionResolution FUNCTIONS = new TestingFunctionResolution();
    private static final ResolvedFunction ROW_NUMBER = FUNCTIONS.resolveFunction("row_number", ImmutableList.of());
    private static final ResolvedFunction SUM = FUNCTIONS.resolveFunction("sum", fromTypes(DOUBLE));

    private final SymbolStatsEstimate aStats = SymbolStatsEstimate.builder()
            .setLowValue(1)
            .setHighValue(30)
            .setDistinctValuesCount(20)
            .setNullsFraction(0.3)
            .build();
    private final SymbolStatsEstimate bStats = SymbolStatsEstimate.builder()
            .setLowValue(13.5)
            .setHighValue(100)
            .setDistinctValuesCount(40)
            .setNullsFraction(0.1)
            .build();

    @Test
    public void testSourceStatsArePassedThrough()
    {
        tester().assertStatsFor(pb -> {
                    Symbol a = pb.symbol("a", BIGINT);
                    Symbol b = pb.symbol("b", DOUBLE);
                    return pb.window(
                            new DataOrganizationSpecification(
                                    ImmutableList.of(a),
                                    Optional.of(new OrderingScheme(ImmutableList.of(b), ImmutableMap.of(b, SortOrder.ASC_NULLS_LAST)))),
                            ImmutableMap.of(
                                    pb.symbol("row_number_1", BIGINT), function(ROW_NUMBER, ImmutableList.of()),
                                    pb.symbol("sum_1", DOUBLE), function(SUM, ImmutableList.of(b.toSymbolReference()))),
                            pb.values(a, b));
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
                        .symbolStatsUnknown("row_number_1", BIGINT)
                        .symbolStatsUnknown("sum_1", DOUBLE));
    }

    @Test
    public void testNoPartitionBy()
    {
        tester().assertStatsFor(pb -> {
                    Symbol a = pb.symbol("a", BIGINT);
                    return pb.window(
                            new DataOrganizationSpecification(ImmutableList.of(), Optional.empty()),
                            ImmutableMap.of(pb.symbol("row_number_1", BIGINT), function(ROW_NUMBER, ImmutableList.of())),
                            pb.values(a));
                })
                .withSourceStats(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addSymbolStatistics(new Symbol(BIGINT, "a"), aStats)
                        .build())
                .check(check -> check
                        .outputRowsCount(100)
                        .symbolStats("a", BIGINT, assertion -> assertion.isEqualTo(aStats))
                        .symbolStatsUnknown("row_number_1", BIGINT));
    }

    @Test
    public void testUnknownSourceStats()
    {
        tester().assertStatsFor(pb -> {
                    Symbol a = pb.symbol("a", BIGINT);
                    return pb.window(
                            new DataOrganizationSpecification(ImmutableList.of(a), Optional.empty()),
                            ImmutableMap.of(pb.symbol("row_number_1", BIGINT), function(ROW_NUMBER, ImmutableList.of())),
                            pb.values(a));
                })
                .withSourceStats(PlanNodeStatsEstimate.unknown())
                .check(PlanNodeStatsAssertion::outputRowsCountUnknown);
    }

    private static WindowNode.Function function(ResolvedFunction resolvedFunction, List<Expression> arguments)
    {
        return new WindowNode.Function(resolvedFunction, arguments, Optional.empty(), DEFAULT_FRAME, false, false);
    }
}
