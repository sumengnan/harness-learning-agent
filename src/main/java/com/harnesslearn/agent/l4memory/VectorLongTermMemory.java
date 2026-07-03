package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
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
        TextSegment seg = TextSegment.from(item.text(),
            dev.langchain4j.data.document.Metadata.from(item.meta()));
        store.add(embed.embed(seg).content(), seg);
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int k) {
        Embedding q = embed.embed(query).content();
        var req = EmbeddingSearchRequest.builder().queryEmbedding(q).maxResults(k).build();
        return store.search(req).matches().stream()
            .map(m -> new RetrievedChunk(
                UUID.randomUUID().toString(),
                m.embedded().metadata().getString("uri"),
                m.embedded().text(),
                m.score()))
            .toList();
    }
}
