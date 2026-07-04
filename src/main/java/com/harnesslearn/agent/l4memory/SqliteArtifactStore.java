package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.Artifact;
import com.harnesslearn.agent.domain.ArtifactQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

public class SqliteArtifactStore implements ArtifactStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    public SqliteArtifactStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void put(Artifact a) {
        jdbc.update("INSERT OR REPLACE INTO artifact(id,run_id,kind,key,content,meta) VALUES(?,?,?,?,?,?)",
            a.id(), a.runId(), a.kind(), a.key(), a.content(), writeJson(a.meta()));
    }

    @Override
    public List<Artifact> query(ArtifactQuery q) {
        return jdbc.query("SELECT * FROM artifact WHERE run_id=? AND kind=?",
            (rs, n) -> new Artifact(rs.getString("id"), rs.getString("run_id"),
                rs.getString("kind"), rs.getString("key"), rs.getString("content"),
                readJson(rs.getString("meta"))), q.runId(), q.kind());
    }

    private String writeJson(Map<String,String> m) {
        try { return mapper.writeValueAsString(m == null ? Map.of() : m); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    // 假设：meta 列里只存 String 值——由 Artifact.meta（Map<String,String>）的编译期泛型
    // 约束保证，正常经 put()/query() 往返时成立。若有绕过 put() 的写入（如直接写库）塞入
    // 非 String 值，此处强转不会立刻报错，而是在调用方读取该值赋给 String 时抛 ClassCastException。
    @SuppressWarnings("unchecked")
    private Map<String,String> readJson(String s) {
        try { return mapper.readValue(s, Map.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
