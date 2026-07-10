package com.simplerag.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private final Path databasePath;

    public DatabaseManager() {
        this(Path.of(System.getProperty("user.home"), ".simplerag", "simplerag.db"));
    }

    public DatabaseManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        initialize();
    }

    public Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void initialize() {
        try {
            Files.createDirectories(databasePath.getParent());
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS knowledge_base (
                          id TEXT PRIMARY KEY,
                          name TEXT NOT NULL,
                          description TEXT NOT NULL DEFAULT '',
                          created_at INTEGER NOT NULL,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS knowledge_source (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          knowledge_base_id TEXT NOT NULL,
                          path TEXT NOT NULL,
                          UNIQUE(knowledge_base_id, path),
                          FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_base(id) ON DELETE CASCADE
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS app_setting (
                          setting_key TEXT PRIMARY KEY,
                          setting_value TEXT NOT NULL
                        )
                        """);
            }
        } catch (Exception failure) {
            throw new DataAccessException("无法初始化本地数据库", failure);
        }
    }

    public Path path() {
        return databasePath;
    }
}
