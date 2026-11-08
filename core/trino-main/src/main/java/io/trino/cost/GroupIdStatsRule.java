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

import io.trino.cost.StatsCalculator.Context;
import io.trino.matching.Pattern;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.plan.GroupIdNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.sql.planner.plan.Patterns.groupId;

public class GroupIdStatsRule
        extends SimpleStatsRule<GroupIdNode>
{
    private static final Pattern<GroupIdNode> PATTERN = groupId();

    public GroupIdStatsRule(StatsNormalizer normalizer)
    {
        super(normalizer);
    }

    @Override
    public Pattern<GroupIdNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    protected Optional<PlanNodeStatsEstimate> doCalculate(GroupIdNode node, Context context)
    {
        PlanNodeStatsEstimate sourceStats = context.statsProvider().getStats(node.getSource());
        List<List<Symbol>> groupingSets = node.getGroupingSets();
        int groupingSetCount = groupingSets.size();

        // every input row is emitted once per grouping set
        PlanNodeStatsEstimate.Builder result = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(sourceStats.getOutputRowCount() * groupingSetCount);

        // a grouping column is null in the copies of the row produced for grouping sets that do not contain it
        for (Map.Entry<Symbol, Symbol> entry : node.getGroupingColumns().entrySet()) {
            Symbol output = entry.getKey();
            SymbolStatsEstimate inputStats = sourceStats.getSymbolStatistics(entry.getValue());
            long containingSetCount = groupingSets.stream()
                    .filter(groupingSet -> groupingSet.contains(output))
                    .count();
            double excludedFraction = (double) (groupingSetCount - containingSetCount) / groupingSetCount;
            result.addSymbolStatistics(output, inputStats.mapNullsFraction(nullsFraction -> nullsFraction + (1 - nullsFraction) * excludedFraction));
        }

        // aggregation arguments are copied unchanged into every grouping set, so their distribution does not change
        for (Symbol argument : node.getAggregationArguments()) {
            result.addSymbolStatistics(argument, sourceStats.getSymbolStatistics(argument));
        }

        result.addSymbolStatistics(node.getGroupIdSymbol(), SymbolStatsEstimate.builder()
                .setLowValue(0)
                .setHighValue(groupingSetCount - 1)
                .setDistinctValuesCount(groupingSetCount)
                .setNullsFraction(0)
                .setAverageRowSize(BIGINT.getFixedSize())
                .build());

        return Optional.of(result.build());
    }
}
