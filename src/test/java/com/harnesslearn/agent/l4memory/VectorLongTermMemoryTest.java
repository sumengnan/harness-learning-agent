package com.harnesslearn.agent.l4memory;

import com.harnesslearn.agent.domain.MemoryItem;
import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class VectorLongTermMemoryTest {
    @Test
    void retrievesMostSimilar() {
        EmbeddingModel embed = new BgeSmallZhEmbeddingModel();
        var mem = new VectorLongTermMemory(embed, new InMemoryEmbeddingStore<TextSegment>());
        mem.remember(new MemoryItem("Agent 上下文工程：裁剪无关信息", Map.of("uri","doc1")));
        mem.remember(new MemoryItem("今天天气不错", Map.of("uri","doc2")));
        List<RetrievedChunk> hits = mem.retrieve("如何做上下文裁剪", 1);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).text()).contains("上下文工程");
    }
}
