package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.*;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultL2ToolSystemTest {
    // 三个用例共享同一个 filter，bge onnx 模型只加载一次。
    // 阈值 0.80：非量化 BGE 中文相似度地板偏高（无关句≈0.73、相关句≈0.87），
    // 取两者中点 0.80 才能把垃圾挡掉又保留相关块。切勿用 0.35（形同虚设）。
    private static RelevanceFilter filter;

    @BeforeAll
    static void setUp() {
        var embed = new BgeSmallZhEmbeddingModel();
        filter = new RelevanceFilter(embed, List.of("AI agent 脚手架与上下文工程"), 0.80, 0.05);
    }

    @Test
    void invokeDistillsAndFiltersGarbage() {
        // local_retrieve 返回 2 条：1 条相关 + 1 条垃圾
        Tool retrieve = tool("local_retrieve", ToolResult.ok("""
            [{"id":"a","sourceUri":"u1","text":"agent 上下文工程与工具编排","relevanceScore":0},
             {"id":"b","sourceUri":"u2","text":"红烧肉的家常做法","relevanceScore":0}]"""));
        var l2 = new DefaultL2ToolSystem(new ToolRegistry(List.of(retrieve)), filter);

        DistilledResult res = l2.invoke(new ToolCall("1","local_retrieve","{\"query\":\"x\"}"));
        assertThat(res.chunks()).extracting(RetrievedChunk::id).containsExactly("a");
        assertThat(res.droppedCount()).isEqualTo(1);
    }

    @Test
    void invokeReturnsFailureNoteWhenToolFails() {
        // 工具失败：!ok 在 filter 之前提前返回，不触及相关性过滤逻辑。
        Tool boom = tool("boom", ToolResult.fail("boom"));
        var l2 = new DefaultL2ToolSystem(new ToolRegistry(List.of(boom)), filter);

        DistilledResult res = l2.invoke(new ToolCall("1","boom","{}"));
        assertThat(res.chunks()).isEmpty();
        assertThat(res.droppedCount()).isZero();
        assertThat(res.note()).startsWith("工具失败:");
    }

    @Test
    void invokePlainTextFallsBackToSingleInlineChunk() {
        // 非 JSON 纯文本 → toChunks 回退成单个 inline 块；用与 anchor 相关的文本确保通过 0.80 阈值。
        Tool fetch = tool("fetch_page", ToolResult.ok("AI agent 上下文工程与工具编排"));
        var l2 = new DefaultL2ToolSystem(new ToolRegistry(List.of(fetch)), filter);

        DistilledResult res = l2.invoke(new ToolCall("1","fetch_page","{}"));
        assertThat(res.chunks()).hasSize(1);
        assertThat(res.chunks().get(0).sourceUri()).isEqualTo("inline");
    }

    private static Tool tool(String name, ToolResult result) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "d"; }
            public ToolResult execute(ToolCall c) { return result; }
        };
    }
}
