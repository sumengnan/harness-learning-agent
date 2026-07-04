package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.List;
import java.util.UUID;

public class VectorLongTermMemory implements LongTermMemory {
    private final EmbeddingModel embed;
    private final EmbeddingStore<TextSegment> store;
    public VectorLongTermMemory(EmbeddingModel embed, EmbeddingStore<TextSegment> store) {
        this.embed = embed; this.store = store;
    }

    @Override
    public void remember(MemoryItem item) {
        TextSegment seg = TextSegment.from(item.text(), Metadata.from(item.meta()));
        store.add(embed.embed(seg).content(), seg);
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int k) {
        Embedding q = embed.embed(query).content();
        var req = EmbeddingSearchRequest.builder().queryEmbedding(q).maxResults(k).build();
        return store.search(req).matches().stream()
            // id 仅为本次检索结果内的临时标识，非稳定主键、与底层存储的 segment 无关联：
            // 同一 segment 在不同 retrieve 调用中会得到不同 id，调用方不要用它做跨调用去重/引用。
            .map(m -> new RetrievedChunk(
                UUID.randomUUID().toString(),
                m.embedded().metadata().getString("uri"),
                m.embedded().text(),
                m.score()))
            .toList();
    }
}
