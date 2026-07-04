package com.harnesslearn.agent.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;

public class SqliteTraceStore implements TraceStore {
    private final JdbcTemplate jdbc;
    public SqliteTraceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void append(TraceStep s) {
        jdbc.update("INSERT INTO trace_step(id,run_id,seq,layer,event,detail,ts) VALUES(?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), s.runId(), s.seq(), s.layer(), s.event(), s.detail(),
            System.currentTimeMillis());
    }
    @Override public List<TraceStep> load(String runId) {
        return jdbc.query("SELECT run_id,seq,layer,event,detail FROM trace_step WHERE run_id=? ORDER BY seq",
            (rs,n) -> new TraceStep(rs.getString("run_id"), rs.getInt("seq"),
                rs.getString("layer"), rs.getString("event"), rs.getString("detail")), runId);
    }
}
