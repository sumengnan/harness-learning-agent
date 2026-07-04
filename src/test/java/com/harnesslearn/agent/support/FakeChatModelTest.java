package com.harnesslearn.agent.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FakeChatModelTest {
    @Test
    void returnsScriptedMessagesInOrder() {
        FakeChatModel fake = FakeChatModel.scripted(
            AiMessage.from("first"),
            AiMessage.from("second"));
        Response<AiMessage> r1 = fake.generate(List.of(UserMessage.from("q")), List.of());
        Response<AiMessage> r2 = fake.generate(List.of(UserMessage.from("q")), List.of());
        assertThat(r1.content().text()).isEqualTo("first");
        assertThat(r2.content().text()).isEqualTo("second");
        assertThat(fake.callCount()).isEqualTo(2);
    }
}
