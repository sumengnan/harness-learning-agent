package com.harnesslearn.agent.ingest;

import com.harnesslearn.agent.l2tools.RelevanceFilter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelevanceGateTest {

    private RelevanceGate gate() {
        EmbeddingModel embed = new BgeSmallZhEmbeddingModel();
        var filter = new RelevanceFilter(embed, List.of(
            "AI agent 上下文工程与 harness 架构设计",
            "大模型工具调用、执行编排与多步推理"), 0.75, 0.05);
        return new RelevanceGate(filter);
    }

    @Test
    void relevantHarnessTextPasses() {
        assertThat(gate().isRelevant(
            "自主 Agent 的执行编排层负责把多步骤任务串起来，用上下文工程裁剪信息并做工具调用。"))
            .isTrue();
    }

    @Test
    void offTopicTextRejected() {
        assertThat(gate().isRelevant(
            "红烧肉的做法：五花肉切块，冷水下锅焯水，加冰糖炒糖色，小火慢炖四十分钟。"))
            .isFalse();
    }

    @Test
    void blankRejected() {
        assertThat(gate().isRelevant("  ")).isFalse();
    }
}
