package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import com.harnesslearn.agent.l4memory.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusIndexRebuilderTest {

    static class RecordingMemory implements LongTermMemory {
        final List<MemoryItem> items = new ArrayList<>();
        public void remember(MemoryItem item) { items.add(item); }
        public List<RetrievedChunk> retrieve(String q, int k) { return List.of(); }
    }

    static class FlakyMemory implements LongTermMemory {
        int calls = 0; final List<MemoryItem> ok = new ArrayList<>();
        public void remember(MemoryItem item) {
            if (++calls == 1) throw new RuntimeException("embed boom");
            ok.add(item);
        }
        public List<RetrievedChunk> retrieve(String q, int k) { return List.of(); }
    }

    private CorpusRepository repoWith3() {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:memRebuild?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        var repo = new CorpusRepository(jt);
        for (int i = 0; i < 3; i++)
            repo.upsertChunk(new CorpusChunk("id" + i, "s", "http://u/" + i, "T" + i, 0, "文本" + i, null));
        return repo;
    }

    @Test
    void rebuildsAllChunksIntoMemory() {
        var memory = new RecordingMemory();
        int n = new CorpusIndexRebuilder(repoWith3(), memory).rebuild();
        assertThat(n).isEqualTo(3);
        assertThat(memory.items).hasSize(3);
        assertThat(memory.items).allSatisfy(m -> assertThat(m.meta()).containsKey("uri"));
    }

    @Test
    void singleFailureIsSkippedNotFatal() {
        var memory = new FlakyMemory();
        int n = new CorpusIndexRebuilder(repoWith3(), memory).rebuild();
        assertThat(n).isEqualTo(2);            // 首条抛异常被跳过
        assertThat(memory.ok).hasSize(2);
    }
}
