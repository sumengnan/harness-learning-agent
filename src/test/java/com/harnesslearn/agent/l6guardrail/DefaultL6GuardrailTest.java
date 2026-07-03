package com.harnesslearn.agent.l6guardrail;

import com.harnesslearn.agent.domain.AgentOutput;
import com.harnesslearn.agent.domain.FailureContext;
import com.harnesslearn.agent.domain.RecoveryDecision;
import com.harnesslearn.agent.domain.RecoveryStrategy;
import com.harnesslearn.agent.domain.ToolCall;
import com.harnesslearn.agent.domain.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultL6GuardrailTest {
    private final L6Guardrail g = new DefaultL6Guardrail(new RecoveryPolicy(2));

    @Test
    void rejectsUnknownToolArgs() {
        ValidationResult r = g.validateAction(new ToolCall("1", "local_retrieve", "not-json"));
        assertThat(r.valid()).isFalse();
    }

    @Test
    void rejectsEmptyOutput() {
        assertThat(g.validateOutput(new AgentOutput("   ", java.util.List.of())).valid()).isFalse();
    }

    @Test
    void routesFailureToPolicy() {
        RecoveryDecision d = g.onFailure(new FailureContext("verification_failed", 1, ""));
        assertThat(d.strategy()).isEqualTo(RecoveryStrategy.RETRY);
    }
}
