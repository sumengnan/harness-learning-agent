package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.l4memory.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusRepositoryTest {

    private JdbcTemplate jt(String memName) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + memName + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return jt;
    }

    @Test
    void seenRoundTrip() {
        var repo = new CorpusRepository(jt("memSeen"));
        assertThat(repo.hasSeen("fp-1")).isFalse();
        repo.markSeen("fp-1", "src-a", "http://x/1", "标题");
        assertThat(repo.hasSeen("fp-1")).isTrue();
    }

    @Test
    void upsertChunkIsIdempotent() {
        var repo = new CorpusRepository(jt("memChunk"));
        repo.upsertChunk(new CorpusChunk("id-1", "src-a", "http://x/1", "T", 0, "正文旧", 111L));
        repo.upsertChunk(new CorpusChunk("id-1", "src-a", "http://x/1", "T", 0, "正文新", 111L));
        assertThat(repo.chunkCount()).isEqualTo(1);
        assertThat(repo.allChunks()).singleElement()
            .satisfies(c -> assertThat(c.text()).isEqualTo("正文新"));
    }

    @Test
    void allChunksHandlesNullPublishedTs() {
        var repo = new CorpusRepository(jt("memNull"));
        repo.upsertChunk(new CorpusChunk("id-2", "src-a", "http://x/2", null, 0, "正文", null));
        assertThat(repo.allChunks()).singleElement()
            .satisfies(c -> {
                assertThat(c.publishedTs()).isNull();
                assertThat(c.title()).isNull();
            });
    }
}
