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
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.sql.ir.Call;
import io.trino.sql.ir.Constant;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.UnnestNode.Mapping;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.trino.cost.PlanNodeStatsEstimate.unknown;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.sql.analyzer.TypeDescriptorProvider.fromTypes;
import static io.trino.sql.planner.plan.JoinType.FULL;
import static io.trino.sql.planner.plan.JoinType.INNER;
import static io.trino.sql.planner.plan.JoinType.LEFT;
import static io.trino.sql.planner.plan.JoinType.RIGHT;
import static io.trino.util.StructuralTestUtil.arrayBlockOf;
import static io.trino.util.StructuralTestUtil.mapType;
import static io.trino.util.StructuralTestUtil.sqlMapOf;
import static io.trino.util.StructuralTestUtil.sqlRowOf;

public class TestUnnestStatsRule
        extends BaseStatsCalculatorTest
{
    private static final ArrayType BIGINT_ARRAY = new ArrayType(BIGINT);
    private static final MapType BIGINT_VARCHAR_MAP = mapType(BIGINT, VARCHAR);
    private static final ResolvedFunction ARRAY_DISTINCT = new TestingFunctionResolution().resolveFunction("array_distinct", fromTypes(BIGINT_ARRAY));

    @Test
    public void testConstantArray()
    {
        tester().assertStatsFor(pb -> {
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            pb.values(
                                    ImmutableList.of(array),
                                    ImmutableList.of(ImmutableList.of(array(1L, 2L, 3L)))));
                })
                .check(outputStats -> outputStats
                        .outputRowsCount(3)
                        .symbolStatsUnknown("element", BIGINT));
    }

    @Test
    public void testMultipleRows()
    {
        // replicated symbols and ordinality do not change the row count
        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            Optional.of(pb.symbol("ordinality", BIGINT)),
                            INNER,
                            pb.values(
                                    ImmutableList.of(id, array),
                                    ImmutableList.of(
                                            ImmutableList.of(new Constant(BIGINT, 1L), array(1L, 2L)),
                                            ImmutableList.of(new Constant(BIGINT, 2L), array()),
                                            ImmutableList.of(new Constant(BIGINT, 3L), new Constant(BIGINT_ARRAY, null)),
                                            ImmutableList.of(new Constant(BIGINT, 4L), array(1L, 2L, 3L, 4L)))));
                })
                .check(outputStats -> outputStats.outputRowsCount(6));
    }

    @Test
    public void testOuterUnnest()
    {
        // empty and null arrays produce one row each
        for (var joinType : ImmutableList.of(LEFT, FULL)) {
            tester().assertStatsFor(pb -> {
                        Symbol array = pb.symbol("array", BIGINT_ARRAY);
                        return pb.unnest(
                                ImmutableList.of(),
                                ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                                Optional.empty(),
                                joinType,
                                pb.values(
                                        ImmutableList.of(array),
                                        ImmutableList.of(
                                                ImmutableList.of(array(1L, 2L)),
                                                ImmutableList.of(array()),
                                                ImmutableList.of(new Constant(BIGINT_ARRAY, null)))));
                    })
                    .check(outputStats -> outputStats.outputRowsCount(4));
        }

        tester().assertStatsFor(pb -> {
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            Optional.empty(),
                            RIGHT,
                            pb.values(
                                    ImmutableList.of(array),
                                    ImmutableList.of(
                                            ImmutableList.of(array(1L, 2L)),
                                            ImmutableList.of(array()),
                                            ImmutableList.of(new Constant(BIGINT_ARRAY, null)))));
                })
                .check(outputStats -> outputStats.outputRowsCount(2));
    }

    @Test
    public void testMultipleMappings()
    {
        // unnested columns are zipped, so the longest one determines the row count
        tester().assertStatsFor(pb -> {
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    Symbol map = pb.symbol("map", BIGINT_VARCHAR_MAP);
                    return pb.unnest(
                            ImmutableList.of(),
                            ImmutableList.of(
                                    new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT))),
                                    new Mapping(map, ImmutableList.of(pb.symbol("key", BIGINT), pb.symbol("value", VARCHAR)))),
                            pb.values(
                                    ImmutableList.of(array, map),
                                    ImmutableList.of(
                                            ImmutableList.of(array(1L), map(ImmutableMap.of(1L, "a", 2L, "b", 3L, "c"))),
                                            ImmutableList.of(array(1L, 2L), new Constant(BIGINT_VARCHAR_MAP, null)))));
                })
                .check(outputStats -> outputStats.outputRowsCount(5));
    }

    @Test
    public void testConstantRows()
    {
        RowType rowType = RowType.anonymous(ImmutableList.of(BIGINT, BIGINT_ARRAY));
        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            pb.valuesOfExpressions(
                                    ImmutableList.of(id, array),
                                    ImmutableList.of(
                                            new Constant(rowType, sqlRowOf(rowType, 1L, arrayBlockOf(BIGINT, 1L, 2L))),
                                            new Constant(rowType, sqlRowOf(rowType, 2L, arrayBlockOf(BIGINT, 1L, 2L, 3L))))));
                })
                .check(outputStats -> outputStats.outputRowsCount(5));
    }

    @Test
    public void testNonConstantArray()
    {
        tester().assertStatsFor(pb -> {
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            pb.values(
                                    ImmutableList.of(array),
                                    ImmutableList.of(
                                            ImmutableList.of(array(1L, 2L)),
                                            ImmutableList.of(new Call(ARRAY_DISTINCT, ImmutableList.of(array(1L, 1L)))))));
                })
                .check(outputStats -> outputStats.equalTo(unknown()));
    }

    @Test
    public void testConstantArrayOverProject()
    {
        SymbolStatsEstimate idStats = SymbolStatsEstimate.builder()
                .setNullsFraction(0.1)
                .setLowValue(1)
                .setHighValue(100)
                .setDistinctValuesCount(50)
                .build();
        PlanNodeStatsEstimate sourceStats = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(100)
                .addSymbolStatistics(new Symbol(BIGINT, "id"), idStats)
                .build();

        // every source row is repeated as many times as there are elements in the constant
        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            pb.project(
                                    Assignments.builder()
                                            .putIdentity(id)
                                            .put(array, array(1L, 2L, 3L))
                                            .build(),
                                    pb.values(id)));
                })
                .withSourceStats(sourceStats)
                .check(outputStats -> outputStats.equalTo(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(300)
                        .addSymbolStatistics(new Symbol(BIGINT, "id"), idStats)
                        .build()));

        // an empty constant produces no rows in an inner unnest, and one row per source row in an outer unnest
        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            pb.project(
                                    Assignments.builder()
                                            .putIdentity(id)
                                            .put(array, array())
                                            .build(),
                                    pb.values(id)));
                })
                .withSourceStats(sourceStats)
                .check(outputStats -> outputStats.equalTo(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(0)
                        .addSymbolStatistics(new Symbol(BIGINT, "id"), SymbolStatsEstimate.zero())
                        .build()));

        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            Optional.empty(),
                            LEFT,
                            pb.project(
                                    Assignments.builder()
                                            .putIdentity(id)
                                            .put(array, array())
                                            .build(),
                                    pb.values(id)));
                })
                .withSourceStats(sourceStats)
                .check(outputStats -> outputStats.equalTo(sourceStats));
    }

    @Test
    public void testNonConstantArrayOverProject()
    {
        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    Symbol otherArray = pb.symbol("other_array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(
                                    new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT))),
                                    new Mapping(otherArray, ImmutableList.of(pb.symbol("other_element", BIGINT)))),
                            pb.project(
                                    Assignments.builder()
                                            .putIdentity(id)
                                            .put(array, array(1L, 2L))
                                            .putIdentity(otherArray)
                                            .build(),
                                    pb.values(id, otherArray)));
                })
                .withSourceStats(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .build())
                .check(outputStats -> outputStats.equalTo(unknown()));
    }

    @Test
    public void testUnknownSourceRowCount()
    {
        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            pb.project(
                                    Assignments.builder()
                                            .putIdentity(id)
                                            .put(array, array(1L, 2L))
                                            .build(),
                                    pb.values(id)));
                })
                .check(outputStats -> outputStats.equalTo(unknown()));
    }

    @Test
    public void testNonValuesSource()
    {
        tester().assertStatsFor(pb -> {
                    Symbol id = pb.symbol("id", BIGINT);
                    Symbol array = pb.symbol("array", BIGINT_ARRAY);
                    return pb.unnest(
                            ImmutableList.of(id),
                            ImmutableList.of(new Mapping(array, ImmutableList.of(pb.symbol("element", BIGINT)))),
                            pb.limit(10, pb.values(id, array)));
                })
                .withSourceStats(PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .build())
                .check(outputStats -> outputStats.equalTo(unknown()));
    }

    private static Constant array(Long... values)
    {
        return new Constant(BIGINT_ARRAY, arrayBlockOf(BIGINT, (Object[]) values));
    }

    private static Constant map(ImmutableMap<Long, String> values)
    {
        return new Constant(BIGINT_VARCHAR_MAP, sqlMapOf(BIGINT, VARCHAR, values));
    }
}
