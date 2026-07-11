package com.simplerag.adapter.out.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/** Shared transaction boundary for adapters that must update several SQLite tables atomically. */
public final class SqliteTransactionManager {
    private final DatabaseManager database;

    public SqliteTransactionManager(DatabaseManager database) { this.database = database; }

    public <T> T execute(String failureMessage, Work<T> work) {
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                if (failure instanceof SQLException sql) throw sql;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new SQLException(failure);
            }
        } catch (SQLException failure) {
            throw new DataAccessException(failureMessage, failure);
        }
    }

    @FunctionalInterface
    public interface Work<T> { T run(Connection connection) throws Exception; }
}
