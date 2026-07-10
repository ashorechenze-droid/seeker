package com.simplerag.repository;

import com.simplerag.model.KnowledgeBase;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AppRepository {
    private final DatabaseManager database;

    public AppRepository(DatabaseManager database) {
        this.database = database;
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        String sql = "SELECT id, name, description, created_at, updated_at FROM knowledge_base ORDER BY updated_at DESC";
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<KnowledgeBase> result = new ArrayList<>();
            while (rows.next()) result.add(readKnowledgeBase(rows));
            return result;
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取知识库", failure);
        }
    }

    public Optional<KnowledgeBase> findKnowledgeBase(String id) {
        String sql = "SELECT id, name, description, created_at, updated_at FROM knowledge_base WHERE id = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readKnowledgeBase(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取知识库", failure);
        }
    }

    public KnowledgeBase createKnowledgeBase(String name, String description) {
        String cleaned = requireName(name);
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO knowledge_base(id, name, description, created_at, updated_at) VALUES(?, ?, ?, ?, ?)";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, cleaned);
            statement.setString(3, description == null ? "" : description.strip());
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
            return new KnowledgeBase(id, cleaned, description == null ? "" : description.strip(), now, now);
        } catch (SQLException failure) {
            throw new DataAccessException("无法创建知识库", failure);
        }
    }

    public KnowledgeBase updateKnowledgeBase(String id, String name, String description) {
        String cleaned = requireName(name);
        long now = System.currentTimeMillis();
        String sql = "UPDATE knowledge_base SET name = ?, description = ?, updated_at = ? WHERE id = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, cleaned);
            statement.setString(2, description == null ? "" : description.strip());
            statement.setLong(3, now);
            statement.setString(4, id);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("知识库不存在");
            return findKnowledgeBase(id).orElseThrow();
        } catch (SQLException failure) {
            throw new DataAccessException("无法更新知识库", failure);
        }
    }

    public void deleteKnowledgeBase(String id) {
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM knowledge_base WHERE id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法删除知识库", failure);
        }
    }

    public List<Path> listSources(String knowledgeBaseId) {
        String sql = "SELECT path FROM knowledge_source WHERE knowledge_base_id = ? ORDER BY id";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Path> result = new ArrayList<>();
                while (rows.next()) result.add(Path.of(rows.getString(1)));
                return result;
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取数据源", failure);
        }
    }

    public void addSource(String knowledgeBaseId, Path path) {
        String sql = "INSERT OR IGNORE INTO knowledge_source(knowledge_base_id, path) VALUES(?, ?)";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, knowledgeBaseId);
            statement.setString(2, path.toAbsolutePath().normalize().toString());
            statement.executeUpdate();
            touch(knowledgeBaseId, connection);
        } catch (SQLException failure) {
            throw new DataAccessException("无法添加数据源", failure);
        }
    }

    public void removeSource(String knowledgeBaseId, Path path) {
        String sql = "DELETE FROM knowledge_source WHERE knowledge_base_id = ? AND path = ?";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, knowledgeBaseId);
            statement.setString(2, path.toAbsolutePath().normalize().toString());
            statement.executeUpdate();
            touch(knowledgeBaseId, connection);
        } catch (SQLException failure) {
            throw new DataAccessException("无法移除数据源", failure);
        }
    }

    public Optional<String> getSetting(String key) {
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT setting_value FROM app_setting WHERE setting_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new DataAccessException("无法读取应用设置", failure);
        }
    }

    public void putSetting(String key, String value) {
        String sql = "INSERT INTO app_setting(setting_key, setting_value) VALUES(?, ?) "
                + "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value";
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value == null ? "" : value);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DataAccessException("无法保存应用设置", failure);
        }
    }

    private static KnowledgeBase readKnowledgeBase(ResultSet rows) throws SQLException {
        return new KnowledgeBase(rows.getString("id"), rows.getString("name"), rows.getString("description"),
                rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    private static String requireName(String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty()) throw new IllegalArgumentException("知识库名称不能为空");
        if (cleaned.length() > 60) throw new IllegalArgumentException("知识库名称不能超过 60 个字符");
        return cleaned;
    }

    private static void touch(String knowledgeBaseId, Connection connection) throws SQLException {
        try (PreparedStatement touch = connection.prepareStatement(
                "UPDATE knowledge_base SET updated_at = ? WHERE id = ?")) {
            touch.setLong(1, System.currentTimeMillis());
            touch.setString(2, knowledgeBaseId);
            touch.executeUpdate();
        }
    }
}
