package com.harnesslearn.agent.l1context;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.l2tools.Tool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.Comparator;
import java.util.List;

public class DefaultL1ContextAssembler implements L1ContextAssembler {
    private final int maxInfo;
    /** ROLE + L3 输出协议 + 可用工具目录，一次算好；每步 assemble 复用为 system 消息。 */
    private final String systemPrompt;

    public DefaultL1ContextAssembler(int maxInfo) {
        this(maxInfo, List.of());
    }

    public DefaultL1ContextAssembler(int maxInfo, List<Tool> tools) {
        if (maxInfo < 0) throw new IllegalArgumentException("maxInfo 不能为负: " + maxInfo);
        this.maxInfo = maxInfo;
        this.systemPrompt = buildSystemPrompt(tools);
    }

    /** 把角色、输出协议与可用工具目录拼成 system 提示词；工具目录复用各 Tool 自己的 description()。 */
    private static String buildSystemPrompt(List<Tool> tools) {
        StringBuilder sb = new StringBuilder(SystemPrompts.ROLE)
            .append("\n\n").append(SystemPrompts.L3_OUTPUT_PROTOCOL);
        if (tools != null && !tools.isEmpty()) {
            sb.append("\n\n## 可用工具\n");
            for (Tool t : tools) {
                sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n");
            }
            sb.append("（若无需工具即可作答，直接用 action:\"final\"）");
        }
        return sb.toString();
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
            SystemMessage.from(systemPrompt),
            UserMessage.from(taskState + "\n\n" + info + "\n\n用户请求：" + task.userQuery()));
        return new AssembledContext(systemPrompt, messages);
    }
}
