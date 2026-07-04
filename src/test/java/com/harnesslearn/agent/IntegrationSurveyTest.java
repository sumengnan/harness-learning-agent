package com.harnesslearn.agent;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.DefaultL1ContextAssembler;
import com.harnesslearn.agent.l2tools.*;
import com.harnesslearn.agent.l3orchestrate.AgentLoop;
import com.harnesslearn.agent.l5eval.LlmL5Evaluator;
import com.harnesslearn.agent.l6guardrail.*;
import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IntegrationSurveyTest {
    @Test
    void surveyRunEndToEnd() {
        // planner 脚本：先检索，再 final；L5 用另一个 fake 判 pass
        var planner = FakeChatModel.scripted(
            AiMessage.from("""
                {"thought":"检索","action":"tool",
                 "tool":{"name":"local_retrieve","arguments":{"query":"上下文工程"}}}"""),
            AiMessage.from("""
                {"thought":"够了","action":"final","answer":"# 综述\\n上下文工程要点…"}"""));
        var critic = FakeChatModel.scripted(AiMessage.from(
            "{\"pass\":true,\"confidence\":0.9,\"issues\":[]}"));

        var embed = new BgeSmallZhEmbeddingModel();
        var filter = new RelevanceFilter(embed, List.of("AI agent 脚手架与上下文工程"), 0.35, 0.05);
        // local_retrieve 桩工具返回一条相关内容
        Tool retrieve = new Tool() {
            public String name() { return "local_retrieve"; }
            public String description() { return "d"; }
            public ToolResult execute(ToolCall c) {
                return ToolResult.ok("[{\"id\":\"a\",\"sourceUri\":\"u1\",\"text\":\"agent 上下文工程与工具编排要点\",\"relevanceScore\":0}]");
            }
        };
        var l2 = new DefaultL2ToolSystem(new ToolRegistry(List.of(retrieve)), filter);
        var loop = new AgentLoop(planner, new DefaultL1ContextAssembler(5), l2,
            new LlmL5Evaluator(critic), new DefaultL6Guardrail(new RecoveryPolicy(2)), 10);

        AgentRun run = loop.run(new TaskSpec("run-e2e", TaskType.SURVEY, "综述上下文工程", java.util.Map.of()));

        assertThat(run.success()).isTrue();
        assertThat(run.output().content()).contains("综述");
        assertThat(run.output().evidence()).isNotEmpty();          // 证据被收集
        assertThat(planner.callCount()).isEqualTo(2);              // 两步循环
        assertThat(critic.callCount()).isEqualTo(1);               // L5 被独立调用一次
    }
}
