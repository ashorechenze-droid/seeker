package com.simplerag.adapter.out.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteTransactionManagerTest {
    @TempDir Path temp;

    @Test
    void rollsBackAllWritesWhenWorkFails() throws Exception {
        DatabaseManager database = new DatabaseManager(temp.resolve("app.db"));
        SqliteTransactionManager transactions = new SqliteTransactionManager(database);

        assertThrows(DataAccessException.class, () -> transactions.execute("failed", connection -> {
            connection.createStatement().executeUpdate(
                    "INSERT INTO app_setting(setting_key, setting_value) VALUES('key', 'value')");
            throw new java.sql.SQLException("boom");
        }));

        try (var connection = database.connect();
             var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM app_setting")) {
            assertEquals(0, rows.getInt(1));
        }
    }
}
