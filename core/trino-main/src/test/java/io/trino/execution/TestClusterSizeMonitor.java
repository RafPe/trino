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
package io.trino.execution;

import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.Duration;
import io.trino.node.InternalNode;
import io.trino.node.TestingInternalNodeManager;
import io.trino.spi.NodeVersion;
import io.trino.spi.QueryId;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class TestClusterSizeMonitor
{
    private static final QueryId QUERY_1 = new QueryId("query_1");
    private static final QueryId QUERY_2 = new QueryId("query_2");

    @Test
    public void testRequiredWorkersForRunningQueries()
    {
        ClusterSizeMonitor monitor = new ClusterSizeMonitor(TestingInternalNodeManager.createDefault(), true);
        try {
            monitor.start();
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(0);

            monitor.addRunningQuery(QUERY_1, 3);
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(3);

            monitor.addRunningQuery(QUERY_2, 5);
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(5);

            monitor.removeRunningQuery(QUERY_2);
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(3);

            // removing a query is idempotent
            monitor.removeRunningQuery(QUERY_2);
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(3);

            monitor.removeRunningQuery(QUERY_1);
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(0);

            // running queries do not affect the gauge for waiting queries
            monitor.addRunningQuery(QUERY_1, 7);
            assertThat(monitor.getRequiredWorkers()).isEqualTo(0);
        }
        finally {
            monitor.stop();
        }
    }

    @Test
    public void testRequiredWorkersAfterWaitCompletes()
            throws Exception
    {
        TestingInternalNodeManager nodeManager = TestingInternalNodeManager.createDefault();
        ClusterSizeMonitor monitor = new ClusterSizeMonitor(nodeManager, true);
        try {
            monitor.start();

            ListenableFuture<Void> future = monitor.waitForMinimumWorkers(2, new Duration(1, MINUTES));
            assertThat(future.isDone()).isFalse();
            assertThat(monitor.getRequiredWorkers()).isEqualTo(2);
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(0);

            nodeManager.addNodes(new InternalNode("worker", URI.create("http://127.0.0.1:8081"), NodeVersion.UNKNOWN, false));
            future.get(10, SECONDS);
            assertThat(monitor.getRequiredWorkers()).isEqualTo(0);

            // the caller registers the query once the wait has completed
            monitor.addRunningQuery(QUERY_1, 2);
            assertThat(monitor.getRequiredWorkers()).isEqualTo(0);
            assertThat(monitor.getRequiredWorkersForRunningQueries()).isEqualTo(2);
        }
        finally {
            monitor.stop();
        }
    }
}
