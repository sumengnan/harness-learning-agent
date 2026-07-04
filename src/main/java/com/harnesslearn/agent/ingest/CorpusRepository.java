package com.harnesslearn.agent.ingest;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;

/** SQLite 读写：corpus_seen（增量去重指纹）+ corpus_chunk（真相源）。 */
public class CorpusRepository {
    private final JdbcTemplate jdbc;
    public CorpusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean hasSeen(String fingerprint) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM corpus_seen WHERE fingerprint=?", Integer.class, fingerprint);
        return n != null && n > 0;
    }

    public void markSeen(String fingerprint, String sourceId, String url, String title) {
        jdbc.update("INSERT OR REPLACE INTO corpus_seen"
            + "(fingerprint,source_id,url,title,first_seen_ts) VALUES(?,?,?,?,?)",
            fingerprint, sourceId, url, title, System.currentTimeMillis());
    }

    public void upsertChunk(CorpusChunk c) {
        jdbc.update("INSERT OR REPLACE INTO corpus_chunk"
            + "(id,source_id,url,title,seq,text,published_ts,ingested_ts) VALUES(?,?,?,?,?,?,?,?)",
            c.id(), c.sourceId(), c.url(), c.title(), c.seq(), c.text(),
            c.publishedTs(), System.currentTimeMillis());
    }

    public int chunkCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM corpus_chunk", Integer.class);
        return n == null ? 0 : n;
    }

    public List<CorpusChunk> allChunks() {
        return jdbc.query(
            "SELECT id,source_id,url,title,seq,text,published_ts FROM corpus_chunk",
            (rs, n) -> new CorpusChunk(
                rs.getString("id"), rs.getString("source_id"), rs.getString("url"),
                rs.getString("title"), rs.getInt("seq"), rs.getString("text"),
                rs.getObject("published_ts") == null ? null : rs.getLong("published_ts")));
    }
}
