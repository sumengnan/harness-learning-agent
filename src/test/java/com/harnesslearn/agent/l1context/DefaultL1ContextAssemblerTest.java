package com.harnesslearn.agent.l1context;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l2tools.Tool;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultL1ContextAssemblerTest {
    @Test
    void assemblesRoleTaskStateAndOnlyTopKRelevant() {
        var assembler = new DefaultL1ContextAssembler(2); // 最多注入 2 条相关信息
        WorkingState state = WorkingState.start("run1", "综述：上下文工程", 10);
        state.recordStep("已检索官网");
        List<RetrievedChunk> candidates = List.of(   // 故意打乱顺序，锁死"按分数降序裁剪"契约
            new RetrievedChunk("c3","u3","低相关C",0.2),
            new RetrievedChunk("c1","u1","高相关A",0.9),
            new RetrievedChunk("c2","u2","高相关B",0.8));
        TaskSpec task = new TaskSpec("run1", TaskType.SURVEY, "综述上下文工程", java.util.Map.of());

        AssembledContext ctx = assembler.assemble(task, state, candidates);

        assertThat(ctx.messages().get(0)).isInstanceOf(SystemMessage.class);
        String all = ctx.render();
        assertThat(all).contains("AI Agent Harness 学习助手");   // 角色
        assertThat(all).contains("已检索官网");                    // 任务状态
        assertThat(all).contains("高相关A").contains("高相关B");   // top-2
        assertThat(all).doesNotContain("低相关C");                 // 超预算被裁
    }

    @Test
    void injectsOutputProtocolAndToolCatalog() {
        Tool retrieve = new Tool() {
            public String name() { return "local_retrieve"; }
            public String description() { return "在本地知识库检索。参数: {query, k}"; }
            public ToolResult execute(ToolCall call) { return ToolResult.ok("x"); }
        };
        var assembler = new DefaultL1ContextAssembler(5, List.of(retrieve));
        WorkingState state = WorkingState.start("r", "g", 10);
        AssembledContext ctx = assembler.assemble(
            new TaskSpec("r", TaskType.QA, "q", java.util.Map.of()), state, List.of());

        String all = ctx.render();
        assertThat(all).contains("输出协议").contains("action").contains("final").contains("tool");
        assertThat(all).contains("可用工具").contains("local_retrieve").contains("在本地知识库检索");
    }
}
