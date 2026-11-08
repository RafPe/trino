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
import io.trino.sql.planner.plan.MarkDistinctNode;

import java.util.Optional;

import static io.trino.sql.planner.plan.Patterns.markDistinct;

public class MarkDistinctStatsRule
        implements Rule<MarkDistinctNode>
{
    private static final Pattern<MarkDistinctNode> PATTERN = markDistinct();

    @Override
    public Pattern<MarkDistinctNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNodeStatsEstimate> calculate(MarkDistinctNode node, Context context)
    {
        // MarkDistinct only appends a boolean marker to each row, so row count and source symbol statistics are unchanged.
        // Marker statistics are left unknown: the fraction of rows marked true depends on the number of distinct
        // value combinations of the distinct symbols, which cannot be estimated reliably without correlation information.
        return Optional.of(context.statsProvider().getStats(node.getSource()));
    }
}
