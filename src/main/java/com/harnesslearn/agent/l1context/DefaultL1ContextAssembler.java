package com.harnesslearn.agent.l1context;

import com.harnesslearn.agent.domain.*;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.Comparator;
import java.util.List;

public class DefaultL1ContextAssembler implements L1ContextAssembler {
    private final int maxInfo;
    public DefaultL1ContextAssembler(int maxInfo) {
        if (maxInfo < 0) throw new IllegalArgumentException("maxInfo 不能为负: " + maxInfo);
        this.maxInfo = maxInfo;
    }

    @Override
    public AssembledContext assemble(TaskSpec task, WorkingState state, List<RetrievedChunk> candidates) {
        String taskState = """
            ## 当前任务
            目标：%s
            已完成步骤：%s
            待解问题：%s
            剩余步数预算：%d""".formatted(
                state.goal(),
                state.completedSteps().isEmpty() ? "（无）" : String.join("；", state.completedSteps()),
                state.openQuestions().isEmpty() ? "（无）" : String.join("；", state.openQuestions()),
                state.budgetRemaining());

        String info = candidates.stream()
            .sorted(Comparator.comparingDouble(RetrievedChunk::relevanceScore).reversed())
            .limit(maxInfo)
            .map(c -> "- [来源 %s] %s".formatted(c.sourceUri(), c.text()))
            .collect(java.util.stream.Collectors.joining("\n", "## 相关资料\n", "\n"));

        List<ChatMessage> messages = List.of(
            SystemMessage.from(SystemPrompts.ROLE),
            UserMessage.from(taskState + "\n\n" + info + "\n\n用户请求：" + task.userQuery()));
        return new AssembledContext(SystemPrompts.ROLE, messages);
    }
}
