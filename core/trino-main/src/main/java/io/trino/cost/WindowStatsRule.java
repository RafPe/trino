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
import io.trino.sql.planner.plan.WindowNode;

import java.util.Optional;

import static io.trino.sql.planner.plan.Patterns.window;

/**
 * Stats rule for {@link WindowNode}.
 * <p>
 * A window node emits exactly one output row per input row and passes all source
 * columns through unchanged, so the source statistics are forwarded as they are.
 * Statistics for the window function outputs are left unknown.
 */
public class WindowStatsRule
        extends SimpleStatsRule<WindowNode>
{
    private static final Pattern<WindowNode> PATTERN = window();

    public WindowStatsRule(StatsNormalizer normalizer)
    {
        super(normalizer);
    }

    @Override
    public Pattern<WindowNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    protected Optional<PlanNodeStatsEstimate> doCalculate(WindowNode node, Context context)
    {
        return Optional.of(context.statsProvider().getStats(node.getSource()));
    }
}
