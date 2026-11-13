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
package io.trino.memoryhistory.predictor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
final class TestConnectionPool
{
    private PostgreSQLContainer postgres;

    @BeforeAll
    void setup()
    {
        postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
    }

    @AfterAll
    void teardown()
    {
        if (postgres != null) {
            postgres.close();
            postgres = null;
        }
    }

    @Test
    void testReusesConnection()
            throws Exception
    {
        AtomicInteger opened = new AtomicInteger();
        try (ConnectionPool pool = pool(opened, 4, Duration.ofSeconds(3))) {
            int first = pool.execute(TestConnectionPool::backendPid);
            for (int i = 0; i < 10; i++) {
                assertThat(pool.execute(TestConnectionPool::backendPid)).isEqualTo(first);
            }
            assertThat(opened.get()).isEqualTo(1);
            assertThat(pool.idleCount()).isEqualTo(1);
        }
    }

    @Test
    void testBrokenConnectionIsReplaced()
            throws Exception
    {
        AtomicInteger opened = new AtomicInteger();
        try (ConnectionPool pool = pool(opened, 4, Duration.ofSeconds(3))) {
            int first = pool.execute(TestConnectionPool::backendPid);
            terminate(first);

            // The pooled connection fails; the pool replaces it and the call succeeds
            int second = pool.execute(TestConnectionPool::backendPid);
            assertThat(second).isNotEqualTo(first);
            assertThat(opened.get()).isEqualTo(2);
            assertThat(pool.execute(TestConnectionPool::backendPid)).isEqualTo(second);
        }
    }

    @Test
    void testAllIdleConnectionsReplacedAfterRestart()
            throws Exception
    {
        AtomicInteger opened = new AtomicInteger();
        try (ConnectionPool pool = pool(opened, 4, Duration.ofSeconds(3))) {
            assertThat(pool.warmUp(3, TestConnectionPool::backendPid)).isEqualTo(3);
            // As after a database restart: every pooled backend is gone
            terminateAll();
            pool.execute(TestConnectionPool::backendPid);
            // The first broken connection makes the pool drop the other idle connections too
            assertThat(opened.get()).isEqualTo(4);
            assertThat(pool.idleCount()).isEqualTo(1);
        }
    }

    @Test
    void testQueryErrorKeepsConnection()
            throws Exception
    {
        AtomicInteger opened = new AtomicInteger();
        try (ConnectionPool pool = pool(opened, 4, Duration.ofSeconds(3))) {
            int pid = pool.execute(TestConnectionPool::backendPid);
            assertThatThrownBy(() -> pool.execute(connection -> {
                try (Statement statement = connection.createStatement()) {
                    return statement.execute("SELECT * FROM missing_table");
                }
            }))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("missing_table");
            assertThat(pool.execute(TestConnectionPool::backendPid)).isEqualTo(pid);
            assertThat(opened.get()).isEqualTo(1);
        }
    }

    @Test
    void testDatabaseDownFailsAndFreesPermit()
    {
        try (ConnectionPool pool = new ConnectionPool(() -> DriverManager.getConnection("jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1"), 2, Duration.ofSeconds(1))) {
            // More calls than the pool size: every failed call gives its permit back
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> pool.execute(TestConnectionPool::backendPid)).isInstanceOf(SQLException.class);
            }
            assertThat(pool.idleCount()).isEqualTo(0);
        }
    }

    @Test
    void testLimitAndAcquireTimeout()
            throws Exception
    {
        AtomicInteger opened = new AtomicInteger();
        try (ConnectionPool pool = pool(opened, 1, Duration.ofMillis(200))) {
            CountDownLatch inUse = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            CompletableFuture<Integer> holder = CompletableFuture.supplyAsync(() -> {
                try {
                    return pool.execute(connection -> {
                        inUse.countDown();
                        try {
                            done.await(10, TimeUnit.SECONDS);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return backendPid(connection);
                    });
                }
                catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(inUse.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> pool.execute(TestConnectionPool::backendPid))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no database connection available");
            done.countDown();
            int pid = holder.get(10, TimeUnit.SECONDS);
            assertThat(pool.execute(TestConnectionPool::backendPid)).isEqualTo(pid);
            assertThat(opened.get()).isEqualTo(1);
        }
    }

    @Test
    void testCloseClosesIdleConnections()
            throws Exception
    {
        AtomicInteger opened = new AtomicInteger();
        ConnectionPool pool = pool(opened, 4, Duration.ofSeconds(3));
        pool.warmUp(2, TestConnectionPool::backendPid);
        pool.close();
        assertThat(pool.idleCount()).isEqualTo(0);
        assertThatThrownBy(() -> pool.execute(TestConnectionPool::backendPid)).hasMessageContaining("closed");
    }

    private ConnectionPool pool(AtomicInteger opened, int maxSize, Duration acquireTimeout)
    {
        return new ConnectionPool(() -> {
            opened.incrementAndGet();
            return open();
        }, maxSize, acquireTimeout);
    }

    private Connection open()
            throws SQLException
    {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void terminate(int pid)
            throws SQLException, InterruptedException
    {
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(" + pid + ")");
        }
        awaitGone("pid = " + pid);
    }

    private void terminateAll()
            throws SQLException, InterruptedException
    {
        String others = "pid <> pg_backend_pid() AND backend_type = 'client backend' AND datname = current_database()";
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE " + others);
        }
        awaitGone(others);
    }

    private void awaitGone(String condition)
            throws SQLException, InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try (Connection connection = open();
                Statement statement = connection.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE " + condition.replace("pg_backend_pid()", String.valueOf(backendPid(connection))))) {
                    resultSet.next();
                    if (resultSet.getInt(1) == 0) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("backends still alive: " + condition);
    }

    private static int backendPid(Connection connection)
            throws SQLException
    {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
