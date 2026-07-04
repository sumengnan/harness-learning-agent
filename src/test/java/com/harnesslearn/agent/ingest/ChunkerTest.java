package com.harnesslearn.agent.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    @Test
    void packsSmallParagraphsIntoOneChunk() {
        var chunks = new Chunker(100).split("段落一。\n\n段落二。");
        assertThat(chunks).singleElement().satisfies(c -> {
            assertThat(c).contains("段落一");
            assertThat(c).contains("段落二");
        });
    }

    @Test
    void startsNewChunkWhenExceedingLimit() {
        String p1 = "a".repeat(60);
        String p2 = "b".repeat(60);
        var chunks = new Chunker(100).split(p1 + "\n\n" + p2);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(p1);
        assertThat(chunks.get(1)).isEqualTo(p2);
    }

    @Test
    void hardSplitsSingleOversizedParagraph() {
        String big = "x".repeat(250);
        var chunks = new Chunker(100).split(big);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(100);
        assertThat(chunks.get(2)).hasSize(50);
        assertThat(String.join("", chunks)).isEqualTo(big);
    }

    @Test
    void blankOrNullYieldsEmptyList() {
        var c = new Chunker(100);
        assertThat(c.split("")).isEmpty();
        assertThat(c.split("   \n\n  ")).isEmpty();
        assertThat(c.split(null)).isEmpty();
    }
}
