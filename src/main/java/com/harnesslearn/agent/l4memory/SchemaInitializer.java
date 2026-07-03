package com.harnesslearn.agent.l4memory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SchemaInitializer {
    // SQLite shared-cache in-memory databases (jdbc:sqlite:file:...?mode=memory&cache=shared)
    // are destroyed the instant their last open connection closes -- even with cache=shared.
    // Non-pooling DataSources (e.g. DriverManagerDataSource, used by tests) open and close a
    // fresh physical connection per JdbcTemplate call, so without something holding a connection
    // open, the schema created below would vanish before the next statement runs. Keep one
    // connection open for the JVM's lifetime to keep such in-memory databases alive.
    private static final Map<String, Connection> MEMORY_DB_KEEPALIVE = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    public SchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void init() {
        keepSharedMemoryDbAlive();
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS working_state(
              run_id TEXT PRIMARY KEY, goal TEXT, step_budget INT,
              completed_steps TEXT, open_questions TEXT)""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS artifact(
              id TEXT PRIMARY KEY, run_id TEXT, kind TEXT, key TEXT,
              content TEXT, meta TEXT)""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS trace_step(
              id TEXT PRIMARY KEY, run_id TEXT, seq INT, layer TEXT,
              event TEXT, detail TEXT, ts INTEGER)""");
    }

    private void keepSharedMemoryDbAlive() {
        try {
            Connection probe = jdbc.getDataSource().getConnection();
            String url = probe.getMetaData().getURL();
            boolean isSharedMemoryDb = url != null && url.contains("mode=memory") && url.contains("cache=shared");
            if (isSharedMemoryDb && !MEMORY_DB_KEEPALIVE.containsKey(url)) {
                MEMORY_DB_KEEPALIVE.put(url, probe); // intentionally never closed; lives for the JVM's duration
            } else {
                probe.close();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to keep SQLite in-memory schema alive", e);
        }
    }
}
