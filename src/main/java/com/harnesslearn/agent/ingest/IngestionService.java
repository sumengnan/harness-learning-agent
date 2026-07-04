package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

/**
 * 采集流水线编排：遍历来源 → 解析条目 → 增量去重 → 抓正文 → 相关性过滤 → 切块
 * → 落 SQLite + 灌内存向量库。全线 best-effort：单来源/单条目异常 WARN 跳过，主流程不崩。
 * markSeen 放在所有块落库成功之后：中途失败下轮重抓（幂等 upsert 兜底），宁可重抓不丢内容。
 */
public class IngestionService {
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int MIN_BODY = 100;   // 短于此判为无效正文

    private final SourceRegistry registry;
    private final FeedReader feedReader;
    private final ArticleFetcher fetcher;
    private final RelevanceGate gate;
    private final Chunker chunker;
    private final CorpusRepository repo;
    private final LongTermMemory memory;

    public IngestionService(SourceRegistry registry, FeedReader feedReader, ArticleFetcher fetcher,
            RelevanceGate gate, Chunker chunker, CorpusRepository repo, LongTermMemory memory) {
        this.registry = registry; this.feedReader = feedReader; this.fetcher = fetcher;
        this.gate = gate; this.chunker = chunker; this.repo = repo; this.memory = memory;
    }

    public void ingestAll() {
        for (Source source : registry.sources()) {
            try { ingestSource(source); }
            catch (RuntimeException e) { log.warn("来源处理异常，跳过: id={}", source.id(), e); }
        }
    }

    private void ingestSource(Source source) {
        for (FeedItem item : feedReader.read(source)) {
            try { ingestItem(source, item); }
            catch (RuntimeException e) { log.warn("条目处理异常，跳过: url={}", item.url(), e); }
        }
    }

    private void ingestItem(Source source, FeedItem item) {
        String fp = fingerprint(item);
        if (repo.hasSeen(fp)) return;                                    // ① 增量去重
        String body = fetcher.fetch(item.url());
        if (body == null || body.length() < MIN_BODY) {                  // 死链/空正文
            repo.markSeen(fp, source.id(), item.url(), item.title()); return;
        }
        if (!gate.isRelevant(body)) {                                    // ② 相关性剔除
            repo.markSeen(fp, source.id(), item.url(), item.title()); return;
        }
        List<String> chunks = chunker.split(body);
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            repo.upsertChunk(new CorpusChunk(                            // ③ 落 SQLite
                fp + ":" + i, source.id(), item.url(), item.title(), i, chunk, item.publishedEpochMs()));
            memory.remember(new MemoryItem(chunk, Map.of(               // ④ 灌内存库
                "uri", nz(item.url()), "title", nz(item.title()), "source", nz(source.id()))));
        }
        repo.markSeen(fp, source.id(), item.url(), item.title());        // ⑤ 全部成功后标记
    }

    static String fingerprint(FeedItem item) {
        return (item.guid() != null && !item.guid().isBlank()) ? item.guid() : item.url();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
