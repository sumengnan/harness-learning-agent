package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l1context.DefaultL1ContextAssembler;
import com.harnesslearn.agent.l2tools.*;
import com.harnesslearn.agent.l5eval.L5Evaluator;
import com.harnesslearn.agent.l6guardrail.*;
import com.harnesslearn.agent.observability.TraceStep;
import com.harnesslearn.agent.observability.TraceStore;
import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {
    @Test
    void runsToolThenFinishesAndVerifies() {
        // 第1步：模型决定调 local_retrieve；第2步：模型给出 final
        var fake = FakeChatModel.scripted(
            AiMessage.from("""
                {"thought":"先检索","action":"tool",
                 "tool":{"name":"local_retrieve","arguments":{"query":"上下文工程"}}}"""),
            AiMessage.from("""
                {"thought":"信息足够","action":"final","answer":"综述正文：上下文工程要点…"}"""));

        // L2 桩：返回一条相关 chunk（不接真实过滤，用放行过滤）
        L2ToolSystem l2 = new L2ToolSystem() {
            public List<String> availableTools() { return List.of("local_retrieve"); }
            public DistilledResult invoke(ToolCall c) {
                return new DistilledResult(List.of(
                    new RetrievedChunk("a","u1","上下文工程要点",0.9)), 0, "ok");
            }
        };
        L5Evaluator l5 = (t,o,e) -> new Verdict(true, List.of(), 0.9);   // 直接通过
        L6Guardrail l6 = new DefaultL6Guardrail(new RecoveryPolicy(2));

        var loop = new AgentLoop(fake, new DefaultL1ContextAssembler(5), l2, l5, l6, 10);
        AgentRun run = loop.run(new TaskSpec("run1", TaskType.SURVEY, "综述上下文工程", java.util.Map.of()));

        assertThat(run.success()).isTrue();
        assertThat(run.output().content()).contains("综述正文");
        assertThat(fake.callCount()).isEqualTo(2);
    }

    @Test
    void abortsWhenBudgetExhausted() {
        // 模型永远只调工具，从不 finish → 撞上步数上限
        AiMessage toolStep = AiMessage.from("""
            {"thought":"再查","action":"tool",
             "tool":{"name":"local_retrieve","arguments":{"query":"x"}}}""");
        var fake = FakeChatModel.scripted(toolStep, toolStep, toolStep, toolStep);
        L2ToolSystem l2 = new L2ToolSystem() {
            public List<String> availableTools() { return List.of("local_retrieve"); }
            public DistilledResult invoke(ToolCall c) { return new DistilledResult(List.of(), 0, "ok"); }
        };
        L5Evaluator l5 = (t,o,e) -> new Verdict(true, List.of(), 1.0);
        var loop = new AgentLoop(fake, new DefaultL1ContextAssembler(5), l2, l5,
            new DefaultL6Guardrail(new RecoveryPolicy(2)), 3);   // maxSteps=3
        AgentRun run = loop.run(new TaskSpec("run2", TaskType.QA, "q", java.util.Map.of()));
        assertThat(run.terminationReason()).contains("budget");
    }

    @Test
    void verificationFailsThenRollsBackAfterRetries() {
        // 模型每步都给合法 final，但 L5 恒不过：前 2 次 RETRY、第 3 次 attempt=3>maxRetries=2 → ROLLBACK。
        // 若 attempt 用全局 stepsUsed，第 1 次就会超限走错——此用例回归验证 attempt 语义修复。
        var fake = FakeChatModel.scripted(
            AiMessage.from("{\"thought\":\"t\",\"action\":\"final\",\"answer\":\"正文A\"}"),
            AiMessage.from("{\"thought\":\"t\",\"action\":\"final\",\"answer\":\"正文B\"}"),
            AiMessage.from("{\"thought\":\"t\",\"action\":\"final\",\"answer\":\"正文C\"}"));
        L2ToolSystem l2 = new L2ToolSystem() {
            public List<String> availableTools() { return List.of("local_retrieve"); }
            public DistilledResult invoke(ToolCall c) { return new DistilledResult(List.of(), 0, "ok"); }
        };
        L5Evaluator l5 = (t,o,e) -> new Verdict(false, List.of(new Issue("grounding","无据")), 0.3);
        var loop = new AgentLoop(fake, new DefaultL1ContextAssembler(5), l2, l5,
            new DefaultL6Guardrail(new RecoveryPolicy(2)), 10);
        AgentRun run = loop.run(new TaskSpec("run3", TaskType.QA, "q", java.util.Map.of()));

        assertThat(run.success()).isFalse();
        assertThat(run.terminationReason()).contains("verification_failed");
    }

    @Test
    void emptyOutputTriggersRetry() {
        // 第1步 final 但 answer 空白 → validateOutput 拦截并重试；第2步正常 final → 通过。
        var fake = FakeChatModel.scripted(
            AiMessage.from("{\"thought\":\"t\",\"action\":\"final\",\"answer\":\"   \"}"),
            AiMessage.from("{\"thought\":\"t\",\"action\":\"final\",\"answer\":\"最终正文\"}"));
        L2ToolSystem l2 = new L2ToolSystem() {
            public List<String> availableTools() { return List.of("local_retrieve"); }
            public DistilledResult invoke(ToolCall c) { return new DistilledResult(List.of(), 0, "ok"); }
        };
        L5Evaluator l5 = (t,o,e) -> new Verdict(true, List.of(), 1.0);
        var loop = new AgentLoop(fake, new DefaultL1ContextAssembler(5), l2, l5,
            new DefaultL6Guardrail(new RecoveryPolicy(2)), 10);
        AgentRun run = loop.run(new TaskSpec("run4", TaskType.QA, "q", java.util.Map.of()));

        assertThat(run.success()).isTrue();
        assertThat(fake.callCount()).isEqualTo(2);
    }

    @Test
    void appendsTraceForEachStep() {
        // 一步工具 + 一步 final：trace 至少含 model_step/L2/L5 若干条
        var fake = FakeChatModel.scripted(
            AiMessage.from("""
                {"thought":"先检索","action":"tool",
                 "tool":{"name":"local_retrieve","arguments":{"query":"x"}}}"""),
            AiMessage.from("""
                {"thought":"够了","action":"final","answer":"正文"}"""));
        L2ToolSystem l2 = new L2ToolSystem() {
            public List<String> availableTools() { return List.of("local_retrieve"); }
            public DistilledResult invoke(ToolCall c) {
                return new DistilledResult(List.of(new RetrievedChunk("a","u","t",0.9)), 0, "ok");
            }
        };
        L5Evaluator l5 = (t,o,e) -> new Verdict(true, List.of(), 0.9);
        var captured = new java.util.ArrayList<TraceStep>();
        TraceStore trace = new TraceStore() {
            public void append(TraceStep s) { captured.add(s); }
            public List<TraceStep> load(String runId) { return captured; }
        };
        var loop = new AgentLoop(fake, new DefaultL1ContextAssembler(5), l2, l5,
            new DefaultL6Guardrail(new RecoveryPolicy(2)), 10, trace);
        loop.run(new TaskSpec("runT", TaskType.QA, "q", java.util.Map.of()));

        assertThat(captured).isNotEmpty();
        assertThat(captured).extracting(TraceStep::layer).contains("L2","L5");
    }
}
