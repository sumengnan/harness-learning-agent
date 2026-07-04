package com.harnesslearn.agent.l2tools.tools;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l4memory.LongTermMemory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LocalRetrieveToolTest {
    @Test
    void returnsRetrievedTextAsJson() {
        LongTermMemory mem = new LongTermMemory() {
            @Override public void remember(MemoryItem item) { }
            @Override public List<RetrievedChunk> retrieve(String query, int k) {
                return List.of(new RetrievedChunk("c1","doc1","上下文裁剪要点",0.8));
            }
        };
        var tool = new LocalRetrieveTool(mem);
        ToolResult r = tool.execute(new ToolCall("1","local_retrieve","{\"query\":\"裁剪\",\"k\":3}"));
        assertThat(r.ok()).isTrue();
        assertThat(r.rawContent()).contains("上下文裁剪要点").contains("doc1");
    }
}
