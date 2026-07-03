package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RelevanceFilterTest {
    @Test
    void dropsIrrelevantAndDuplicates() {
        EmbeddingModel embed = new BgeSmallZhEmbeddingModel();
        var filter = new RelevanceFilter(embed,
            List.of("AI agent 脚手架与上下文工程", "工具编排与自主决策循环"),
            // τ=0.80：非量化 BGE 中文句向量相似度地板偏高（实测无关句"火锅蘸料"对质心≈0.73、
            // 相关句≈0.87），故阈值取两者中点 0.80 才能剔无关、留相关；计划原值 0.35 是按量化模型设定、此处不适用。
            0.80, 0.05);

        List<RetrievedChunk> in = List.of(
            new RetrievedChunk("a","u1","如何设计 agent 的上下文裁剪与工具调用",0),
            new RetrievedChunk("b","u2","如何设计 agent 的上下文裁剪与工具调用",0), // 与 a 重复
            new RetrievedChunk("c","u3","今晚吃火锅的最佳蘸料配方",0));            // 无关垃圾

        RelevanceFilter.Result res = filter.filter(in);
        assertThat(res.kept()).extracting(RetrievedChunk::id).containsExactly("a");
        assertThat(res.droppedCount()).isEqualTo(2);
    }
}
