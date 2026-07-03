package com.harnesslearn.agent.l4memory;
import org.springframework.jdbc.core.JdbcTemplate;
public class SchemaInitializer {
    private final JdbcTemplate jdbc;
    public SchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void init() {
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
}
