package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

/**
 * 启动重建：从 corpus_chunk（真相源）读全量，逐条重新 embed 灌回内存向量库。
 * InMemoryEmbeddingStore 进程内、重启即空，故每次启动必须重建。best-effort：单条失败跳过。
 */
public class CorpusIndexRebuilder {
    private static final Logger log = LoggerFactory.getLogger(CorpusIndexRebuilder.class);
    private final CorpusRepository repo;
    private final LongTermMemory memory;

    public CorpusIndexRebuilder(CorpusRepository repo, LongTermMemory memory) {
        this.repo = repo; this.memory = memory;
    }

    public int rebuild() {
        List<CorpusChunk> chunks = repo.allChunks();
        int ok = 0;
        for (CorpusChunk c : chunks) {
            try {
                memory.remember(new MemoryItem(c.text(), Map.of(
                    "uri", nz(c.url()), "title", nz(c.title()), "source", nz(c.sourceId()))));
                ok++;
            } catch (RuntimeException e) {
                log.warn("重建索引单条失败，跳过: id={}", c.id(), e);
            }
        }
        log.info("重建向量索引：{}/{} 块", ok, chunks.size());
        return ok;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
