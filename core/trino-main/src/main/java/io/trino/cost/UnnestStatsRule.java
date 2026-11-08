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

import io.trino.cost.ComposableStatsCalculator.Rule;
import io.trino.cost.StatsCalculator.Context;
import io.trino.matching.Pattern;
import io.trino.spi.block.Block;
import io.trino.spi.block.SqlMap;
import io.trino.spi.block.SqlRow;
import io.trino.spi.type.Type;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.Row;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.planner.plan.UnnestNode;
import io.trino.sql.planner.plan.ValuesNode;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.type.TypeUtils.readNativeValue;
import static io.trino.sql.planner.plan.JoinType.FULL;
import static io.trino.sql.planner.plan.JoinType.LEFT;
import static io.trino.sql.planner.plan.Patterns.unnest;
import static java.lang.Math.max;

/**
 * Estimates the output of an {@link UnnestNode} only when the number of elements of the unnested
 * values is known exactly, because they are constants:
 * <ul>
 * <li>the source is a {@link ValuesNode} with constant rows, for example
 * {@code SELECT * FROM UNNEST(ARRAY[1, 2, 3])};</li>
 * <li>the source is a {@link ProjectNode} that assigns a constant to every unnested symbol, for example
 * {@code SELECT * FROM t CROSS JOIN UNNEST(ARRAY[1, 2, 3])}. Each source row is then repeated the same
 * number of times, so the statistics of the replicated symbols do not change.</li>
 * </ul>
 * The number of elements of a non-constant value is not known from table statistics, so in all
 * other cases the estimate is left unknown.
 */
public class UnnestStatsRule
        implements Rule<UnnestNode>
{
    private static final Pattern<UnnestNode> PATTERN = unnest();

    @Override
    public Pattern<UnnestNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNodeStatsEstimate> calculate(UnnestNode node, Context context)
    {
        return switch (context.lookup().resolve(node.getSource())) {
            case ValuesNode valuesNode -> calculateForValues(node, valuesNode);
            case ProjectNode projectNode -> calculateForConstants(node, projectNode, context.statsProvider().getStats(node.getSource()));
            default -> Optional.empty();
        };
    }

    private static Optional<PlanNodeStatsEstimate> calculateForValues(UnnestNode node, ValuesNode valuesNode)
    {
        if (valuesNode.getRows().isEmpty()) {
            return Optional.empty();
        }

        List<Symbol> sourceSymbols = valuesNode.getOutputSymbols();
        long outputRowCount = 0;
        for (Expression row : valuesNode.getRows().get()) {
            List<Optional<Constant>> values = node.getMappings().stream()
                    .map(UnnestNode.Mapping::getInput)
                    .map(input -> getConstant(row, sourceSymbols.indexOf(input), input.type()))
                    .collect(toImmutableList());
            OptionalLong rowOutputCount = getOutputCount(node, values);
            if (rowOutputCount.isEmpty()) {
                return Optional.empty();
            }
            outputRowCount += rowOutputCount.getAsLong();
        }

        return Optional.of(PlanNodeStatsEstimate.builder()
                .setOutputRowCount(outputRowCount)
                .build());
    }

    private static Optional<PlanNodeStatsEstimate> calculateForConstants(UnnestNode node, ProjectNode projectNode, PlanNodeStatsEstimate sourceStats)
    {
        if (sourceStats.isOutputRowCountUnknown()) {
            return Optional.empty();
        }

        List<Optional<Constant>> values = node.getMappings().stream()
                .map(mapping -> projectNode.getAssignments().get(mapping.getInput()) instanceof Constant constant ? Optional.of(constant) : Optional.<Constant>empty())
                .collect(toImmutableList());
        OptionalLong outputCountPerRow = getOutputCount(node, values);
        if (outputCountPerRow.isEmpty()) {
            return Optional.empty();
        }

        PlanNodeStatsEstimate.Builder result = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(sourceStats.getOutputRowCount() * outputCountPerRow.getAsLong());
        // each source row is repeated the same number of times, so the replicated symbols keep their statistics
        for (Symbol symbol : node.getReplicateSymbols()) {
            result.addSymbolStatistics(symbol, outputCountPerRow.getAsLong() == 0 ? SymbolStatsEstimate.zero() : sourceStats.getSymbolStatistics(symbol));
        }
        return Optional.of(result.build());
    }

    private static Optional<Constant> getConstant(Expression row, int channel, Type type)
    {
        return switch (row) {
            case Row(List<Expression> items, Type _) when items.get(channel) instanceof Constant constant -> Optional.of(constant);
            case Constant(Type _, SqlRow sqlRow) -> Optional.of(new Constant(type, readNativeValue(type, sqlRow.getRawFieldBlock(channel), sqlRow.getRawIndex())));
            default -> Optional.empty();
        };
    }

    /**
     * Returns the number of rows produced for a single source row with the given unnested values,
     * or empty if any of the values is not a constant.
     */
    private static OptionalLong getOutputCount(UnnestNode node, List<Optional<Constant>> values)
    {
        long outputCount = 0;
        for (Optional<Constant> value : values) {
            if (value.isEmpty()) {
                return OptionalLong.empty();
            }
            long cardinality;
            switch (value.get().value()) {
                case null -> cardinality = 0;
                case Block array -> cardinality = array.getPositionCount();
                case SqlMap map -> cardinality = map.getSize();
                default -> {
                    return OptionalLong.empty();
                }
            }
            // unnested values are zipped, so the longest one determines the number of rows
            outputCount = max(outputCount, cardinality);
        }
        if (node.getJoinType() == LEFT || node.getJoinType() == FULL) {
            // an outer unnest produces one row for a null or empty value
            outputCount = max(outputCount, 1);
        }
        return OptionalLong.of(outputCount);
    }
}
