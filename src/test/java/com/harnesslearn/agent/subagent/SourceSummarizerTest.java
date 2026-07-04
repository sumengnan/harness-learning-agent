package com.harnesslearn.agent.subagent;

import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SourceSummarizerTest {
    @Test
    void summarizesSourceIntoBulletPoints() {
        var fake = FakeChatModel.scripted(AiMessage.from("- 要点1\n- 要点2"));
        var agent = new SourceSummarizer(fake);
        String summary = agent.run(new SourceSummarizer.Input("http://x", "一大段原文……"));
        assertThat(summary).contains("要点1").contains("要点2");
    }
}
