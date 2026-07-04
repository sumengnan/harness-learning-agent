package com.harnesslearn.agent.l5eval;

import com.harnesslearn.agent.domain.*;
import com.harnesslearn.agent.support.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LlmL5EvaluatorTest {
    @Test
    void parsesVerdictFromCriticJson() {
        var fake = FakeChatModel.scripted(AiMessage.from("""
            {"pass": false, "confidence": 0.6,
             "issues": [{"dimension":"grounding","detail":"论断2缺来源"}]}"""));
        var evaluator = new LlmL5Evaluator(fake);
        Verdict v = evaluator.verify(
            new TaskSpec("run1", TaskType.SURVEY, "综述", java.util.Map.of()),
            new AgentOutput("正文…", List.of()),
            List.of(new Artifact("a","run1","summary","k","证据…", java.util.Map.of())));
        assertThat(v.pass()).isFalse();
        assertThat(v.confidence()).isEqualTo(0.6);
        assertThat(v.issues()).extracting(Issue::dimension).containsExactly("grounding");
    }

    @Test
    void failsClosedWhenOutputNotParseable() {
        var fake = FakeChatModel.scripted(AiMessage.from("抱歉，我无法完成评估。"));
        var evaluator = new LlmL5Evaluator(fake);
        Verdict v = evaluator.verify(
            new TaskSpec("run1", TaskType.SURVEY, "综述", java.util.Map.of()),
            new AgentOutput("正文…", List.of()),
            List.of(new Artifact("a","run1","summary","k","证据…", java.util.Map.of())));
        assertThat(v.pass()).isFalse();
        assertThat(v.confidence()).isEqualTo(0.0);
        assertThat(v.issues()).extracting(Issue::dimension).containsExactly("format");
    }
}
