package com.harnesslearn.agent.observability;

import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LoggingChatModelListenerTest {
    @Test
    void recordsRequestAndResponse() {
        List<String> sink = new ArrayList<>();
        var listener = new LoggingChatModelListener(sink::add);
        listener.onRequest(new ChatModelRequestContext(
            ChatModelRequest.builder().messages(List.of(UserMessage.from("hi"))).build(),
            new java.util.concurrent.ConcurrentHashMap<>()));
        listener.onResponse(new ChatModelResponseContext(
            ChatModelResponse.builder().aiMessage(AiMessage.from("hello")).build(),
            ChatModelRequest.builder().messages(List.of(UserMessage.from("hi"))).build(),
            new java.util.concurrent.ConcurrentHashMap<>()));
        assertThat(sink).anyMatch(s -> s.contains("REQUEST"));
        assertThat(sink).anyMatch(s -> s.contains("RESPONSE") && s.contains("hello"));
    }
}
