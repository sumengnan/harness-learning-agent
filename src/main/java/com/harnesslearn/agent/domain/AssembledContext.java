package com.harnesslearn.agent.domain;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
public record AssembledContext(String systemPrompt, List<ChatMessage> messages) {
    /**
     * 依赖 langchain4j {@link ChatMessage#toString()} 拼接消息文本，仅用于调试/日志/测试断言，
     * 非稳定序列化格式，下游勿当契约依赖。角色/系统提示已包含在 messages[0]（SystemMessage）中，
     * 故此处不再单独前置 {@code systemPrompt}，避免重复渲染。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) sb.append(m.toString()).append("\n");
        return sb.toString();
    }
}
