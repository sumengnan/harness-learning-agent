package com.harnesslearn.agent.l4memory;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusSeederTest {

    private VectorLongTermMemory newMemory() {
        EmbeddingModel embed = new BgeSmallZhEmbeddingModel();
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        return new VectorLongTermMemory(embed, store);
    }

    @Test
    void seedsFixtureAndRetrieveReturnsHits() {
        var memory = newMemory();
        int n = new CorpusSeeder(memory, "/test-seed-corpus.json").seed();
        assertThat(n).isEqualTo(3);
        var hits = memory.retrieve("上下文工程", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits).anyMatch(h -> h.text().contains("上下文"));
    }

    @Test
    void missingResourceIsBestEffortReturnsZero() {
        var memory = newMemory();
        int n = new CorpusSeeder(memory, "/no-such-seed.json").seed();
        assertThat(n).isZero();
        assertThat(memory.retrieve("任意", 3)).isEmpty();
    }
}
