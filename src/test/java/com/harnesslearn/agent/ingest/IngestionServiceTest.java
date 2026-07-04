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
import static org.assertj.core.api.Assertions.assertThatCode;

class IngestionServiceTest {

    // 记录式 LongTermMemory，避免加载 bge
    static class RecordingMemory implements LongTermMemory {
        final List<MemoryItem> items = new ArrayList<>();
        public void remember(MemoryItem item) { items.add(item); }
        public List<RetrievedChunk> retrieve(String q, int k) { return List.of(); }
    }

    private CorpusRepository repo(String mem) {
        var ds = new SingleConnectionDataSource(
            "jdbc:sqlite:file:" + mem + "?mode=memory&cache=shared", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jt = new JdbcTemplate(ds);
        new SchemaInitializer(jt).init();
        return new CorpusRepository(jt);
    }

    private SourceRegistry registryWithOneSource() {
        var src = new Source("src-a", SourceType.RSS, "http://feed", List.of());
        return new SourceRegistry(new IngestProperties(true, null, 800, List.of(src)));
    }

    // 桩：固定返回给定条目
    private FeedReader feedWith(FeedItem... items) {
        return new FeedReader() {
            @Override public List<FeedItem> read(Source s) { return List.of(items); }
        };
    }

    @Test
    void ingestsRelevantSkipsOffTopicAndDedups() {
        var repo = repo("memIngest1");
        var memory = new RecordingMemory();
        var itemA = new FeedItem("gA", "http://a", "文章A", null);
        var itemB = new FeedItem("gB", "http://b", "文章B", null);
        var svc = new IngestionService(
            registryWithOneSource(),
            feedWith(itemA, itemB),
            new ArticleFetcher() {
                @Override public String fetch(String url) {
                    return url.equals("http://a")
                        ? "harness 相关正文".repeat(20) : "离题正文".repeat(20);
                }
            },
            new RelevanceGate(null) {
                @Override public boolean isRelevant(String body) { return body.contains("harness"); }
            },
            new Chunker(800), repo, memory);

        svc.ingestAll();

        assertThat(repo.chunkCount()).isGreaterThan(0);
        assertThat(repo.allChunks()).allSatisfy(c -> assertThat(c.url()).isEqualTo("http://a"));
        assertThat(repo.hasSeen("gA")).isTrue();
        assertThat(repo.hasSeen("gB")).isTrue();              // 离题也标记已见
        assertThat(memory.items).isNotEmpty();

        int before = repo.chunkCount();
        svc.ingestAll();                                       // 第二轮：全部已见
        assertThat(repo.chunkCount()).isEqualTo(before);
    }

    @Test
    void perItemFailureIsBestEffort() {
        var repo = repo("memIngest2");
        var memory = new RecordingMemory();
        var itemA = new FeedItem("gA", "http://a", "A", null);
        var itemB = new FeedItem("gB", "http://b", "B", null);
        var svc = new IngestionService(
            registryWithOneSource(),
            feedWith(itemA, itemB),
            new ArticleFetcher() {
                @Override public String fetch(String url) {
                    if (url.equals("http://a")) throw new RuntimeException("boom");
                    return "harness 正文".repeat(20);
                }
            },
            new RelevanceGate(null) {
                @Override public boolean isRelevant(String body) { return true; }
            },
            new Chunker(800), repo, memory);

        assertThatCode(svc::ingestAll).doesNotThrowAnyException();
        assertThat(repo.hasSeen("gB")).isTrue();               // B 正常入库
    }
}
