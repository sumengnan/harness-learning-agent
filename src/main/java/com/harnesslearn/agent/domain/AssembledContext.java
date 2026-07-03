package com.harnesslearn.agent.domain;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
public record AssembledContext(String systemPrompt, List<ChatMessage> messages) {
    public String render() {
        StringBuilder sb = new StringBuilder(systemPrompt).append("\n");
        for (ChatMessage m : messages) sb.append(m.toString()).append("\n");
        return sb.toString();
    }
}
