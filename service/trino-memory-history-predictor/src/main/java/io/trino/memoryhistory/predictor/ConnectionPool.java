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

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A small fixed-size pool of database connections.
 * <p>
 * Opening a PostgreSQL connection costs about 7 ms (SCRAM authentication and a new backend process),
 * and the first query on a new backend costs about 4 ms more. A pooled connection answers the
 * history query in well under 1 ms.
 * <p>
 * Connections are opened on demand, up to {@code maxSize}. A caller that finds all connections in use
 * waits up to {@code acquireTimeout}. When a query fails on an idle connection because the connection
 * is broken (for example after a database restart), the pool closes that connection and all other idle
 * connections, and runs the query once more on a new connection. The pool only runs reads, so the
 * second attempt is safe.
 */
public class ConnectionPool
        implements Closeable
{
    private static final Logger log = Logger.getLogger(ConnectionPool.class.getName());
    private static final int VALIDATION_TIMEOUT_SECONDS = 1;

    private final ConnectionFactory factory;
    private final int maxSize;
    private final Semaphore permits;
    private final Duration acquireTimeout;
    private final ConcurrentLinkedDeque<Connection> idle = new ConcurrentLinkedDeque<>();
    private volatile boolean closed;

    public ConnectionPool(ConnectionFactory factory, int maxSize, Duration acquireTimeout)
    {
        this.factory = requireNonNull(factory, "factory is null");
        checkArgument(maxSize > 0, "maxSize must be positive");
        this.maxSize = maxSize;
        this.permits = new Semaphore(maxSize, true);
        this.acquireTimeout = requireNonNull(acquireTimeout, "acquireTimeout is null");
    }

    /**
     * Runs {@code work} on a pooled connection. The connection must be left in auto-commit mode.
     */
    public <T> T execute(SqlFunction<T> work)
            throws SQLException
    {
        requireNonNull(work, "work is null");
        if (closed) {
            throw new SQLException("connection pool is closed");
        }
        try {
            if (!permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SQLException("no database connection available after " + acquireTimeout.toMillis() + " ms");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for a database connection", e);
        }
        try {
            Connection pooled = idle.pollFirst();
            if (pooled != null) {
                try {
                    return runAndReturn(pooled, work);
                }
                catch (BrokenConnectionException e) {
                    // The database probably restarted: the other idle connections are broken too
                    log.log(Level.INFO, "Pooled database connection is broken, opening a new one", e.getCause());
                    closeIdle();
                }
            }
            try {
                return runAndReturn(factory.open(), work);
            }
            catch (BrokenConnectionException e) {
                throw e.getCause();
            }
        }
        finally {
            permits.release();
        }
    }

    /**
     * Opens connections until {@code count} (at most the pool size) are idle, and runs {@code work} once on each.
     * Returns the number of idle connections. Stops at the first failure. Call it before the pool is in use.
     */
    public int warmUp(int count, SqlFunction<?> work)
            throws SQLException
    {
        int target = Math.min(count, maxSize);
        List<Connection> opened = new ArrayList<>();
        try {
            while (idle.size() + opened.size() < target) {
                Connection connection = factory.open();
                opened.add(connection);
                work.apply(connection);
            }
        }
        finally {
            if (closed) {
                opened.forEach(ConnectionPool::closeQuietly);
            }
            else {
                opened.forEach(idle::addFirst);
            }
        }
        return idle.size();
    }

    public int idleCount()
    {
        return idle.size();
    }

    @Override
    public void close()
    {
        closed = true;
        closeIdle();
    }

    private <T> T runAndReturn(Connection connection, SqlFunction<T> work)
            throws SQLException
    {
        T result;
        try {
            result = work.apply(connection);
        }
        catch (SQLException e) {
            if (isBroken(connection, e)) {
                closeQuietly(connection);
                throw new BrokenConnectionException(e);
            }
            giveBack(connection);
            throw e;
        }
        catch (RuntimeException | Error e) {
            closeQuietly(connection);
            throw e;
        }
        giveBack(connection);
        return result;
    }

    private void giveBack(Connection connection)
    {
        // Most recently used first, so a burst reuses the warm connections
        idle.addFirst(connection);
        // close() may run at the same time
        if (closed && idle.remove(connection)) {
            closeQuietly(connection);
        }
    }

    private void closeIdle()
    {
        Connection connection;
        while ((connection = idle.pollFirst()) != null) {
            closeQuietly(connection);
        }
    }

    private static boolean isBroken(Connection connection, SQLException e)
    {
        // Class 08: connection exception. Class 57P: the server shut down or terminated the backend.
        String state = e.getSQLState();
        if (state != null && (state.startsWith("08") || state.startsWith("57P"))) {
            return true;
        }
        try {
            return connection.isClosed() || !connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        }
        catch (SQLException _) {
            return true;
        }
    }

    private static void closeQuietly(Connection connection)
    {
        try {
            connection.close();
        }
        catch (SQLException | RuntimeException e) {
            log.log(Level.FINE, "Closing a database connection failed", e);
        }
    }

    @FunctionalInterface
    public interface ConnectionFactory
    {
        Connection open()
                throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<T>
    {
        T apply(Connection connection)
                throws SQLException;
    }

    private static class BrokenConnectionException
            extends SQLException
    {
        BrokenConnectionException(SQLException cause)
        {
            super(cause.getMessage(), cause.getSQLState(), cause);
        }

        @Override
        public synchronized SQLException getCause()
        {
            return (SQLException) super.getCause();
        }
    }
}
