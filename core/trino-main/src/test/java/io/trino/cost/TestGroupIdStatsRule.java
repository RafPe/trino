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
import io.trino.sql.planner.Symbol;
import org.junit.jupiter.api.Test;

import static io.trino.spi.type.BigintType.BIGINT;

public class TestGroupIdStatsRule
        extends BaseStatsCalculatorTest
{
    private static final Symbol A = new Symbol(BIGINT, "a");
    private static final Symbol B = new Symbol(BIGINT, "b");
    private static final Symbol C = new Symbol(BIGINT, "c");
    private static final Symbol A_OUT = new Symbol(BIGINT, "a_out");
    private static final Symbol B_OUT = new Symbol(BIGINT, "b_out");
    private static final Symbol GROUP_ID = new Symbol(BIGINT, "group_id");

    private static final SymbolStatsEstimate A_STATS = SymbolStatsEstimate.builder()
            .setLowValue(1)
            .setHighValue(10)
            .setDistinctValuesCount(10)
            .setNullsFraction(0)
            .build();
    private static final SymbolStatsEstimate B_STATS = SymbolStatsEstimate.builder()
            .setLowValue(1)
            .setHighValue(100)
            .setDistinctValuesCount(20)
            .setNullsFraction(0.2)
            .build();
    private static final SymbolStatsEstimate C_STATS = SymbolStatsEstimate.builder()
            .setLowValue(-5)
            .setHighValue(5)
            .setDistinctValuesCount(11)
            .setNullsFraction(0.1)
            .build();

    @Test
    public void testRollup()
    {
        // GROUP BY ROLLUP (a, b) with c as an aggregation argument
        tester().assertStatsFor(pb -> pb.groupId(
                        ImmutableList.of(ImmutableList.of(A_OUT, B_OUT), ImmutableList.of(A_OUT), ImmutableList.of()),
                        ImmutableMap.of(A_OUT, A, B_OUT, B),
                        ImmutableList.of(C),
                        GROUP_ID,
                        pb.values(A, B, C)))
                .withSourceStats(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addSymbolStatistics(A, A_STATS)
                        .addSymbolStatistics(B, B_STATS)
                        .addSymbolStatistics(C, C_STATS)
                        .build())
                .check(check -> check
                        .outputRowsCount(300)
                        .symbolsWithKnownStats(A_OUT, B_OUT, C, GROUP_ID)
                        // a_out is null in 1 of 3 grouping sets
                        .symbolStats(A_OUT, assertion -> assertion
                                .lowValue(1)
                                .highValue(10)
                                .distinctValuesCount(10)
                                .nullsFraction(1.0 / 3))
                        // b_out is null in 2 of 3 grouping sets, and in 20% of rows of the remaining one
                        .symbolStats(B_OUT, assertion -> assertion
                                .lowValue(1)
                                .highValue(100)
                                .distinctValuesCount(20)
                                .nullsFraction((0.2 + 2) / 3))
                        .symbolStats(C, assertion -> assertion.isEqualTo(C_STATS))
                        .symbolStats(GROUP_ID, assertion -> assertion
                                .lowValue(0)
                                .highValue(2)
                                .distinctValuesCount(3)
                                .nullsFraction(0)));
    }

    @Test
    public void testGroupingColumnInAllGroupingSets()
    {
        // GROUP BY GROUPING SETS ((a, b), (a))
        tester().assertStatsFor(pb -> pb.groupId(
                        ImmutableList.of(ImmutableList.of(A_OUT, B_OUT), ImmutableList.of(A_OUT)),
                        ImmutableMap.of(A_OUT, A, B_OUT, B),
                        ImmutableList.of(),
                        GROUP_ID,
                        pb.values(A, B)))
                .withSourceStats(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addSymbolStatistics(A, A_STATS)
                        .addSymbolStatistics(B, B_STATS)
                        .build())
                .check(check -> check
                        .outputRowsCount(200)
                        .symbolStats(A_OUT, assertion -> assertion.isEqualTo(A_STATS))
                        .symbolStats(B_OUT, assertion -> assertion
                                .distinctValuesCount(20)
                                .nullsFraction(0.6))
                        .symbolStats(GROUP_ID, assertion -> assertion
                                .lowValue(0)
                                .highValue(1)
                                .distinctValuesCount(2)
                                .nullsFraction(0)));
    }

    @Test
    public void testUnknownSourceStats()
    {
        tester().assertStatsFor(pb -> pb.groupId(
                        ImmutableList.of(ImmutableList.of(A_OUT), ImmutableList.of()),
                        ImmutableMap.of(A_OUT, A),
                        ImmutableList.of(C),
                        GROUP_ID,
                        pb.values(A, C)))
                .withSourceStats(PlanNodeStatsEstimate.unknown())
                .check(check -> check
                        .outputRowsCountUnknown()
                        .symbolStatsUnknown(A_OUT)
                        .symbolStatsUnknown(C)
                        .symbolStats(GROUP_ID, assertion -> assertion
                                .lowValue(0)
                                .highValue(1)
                                .distinctValuesCount(2)
                                .nullsFraction(0)));
    }
}
