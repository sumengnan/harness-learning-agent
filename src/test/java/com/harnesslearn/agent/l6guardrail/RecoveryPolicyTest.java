package com.harnesslearn.agent.l6guardrail;

import com.harnesslearn.agent.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryPolicyTest {
    private final RecoveryPolicy policy = new RecoveryPolicy(2 /*maxRetries*/);

    @Test
    void webSearchFailureDegrades() {
        var d = policy.decide(new FailureContext("web_search_failed", 1, ""));
        assertThat(d.strategy()).isEqualTo(RecoveryStrategy.DEGRADE);
    }

    @Test
    void invalidOutputRetriesThenAborts() {
        assertThat(policy.decide(new FailureContext("invalid_output", 1, "")).strategy())
            .isEqualTo(RecoveryStrategy.RETRY);
        assertThat(policy.decide(new FailureContext("invalid_output", 3, "")).strategy())
            .isEqualTo(RecoveryStrategy.ABORT);
    }

    @Test
    void verificationFailedRollsBackAfterMaxRetries() {
        assertThat(policy.decide(new FailureContext("verification_failed", 1, "")).strategy())
            .isEqualTo(RecoveryStrategy.RETRY);
        assertThat(policy.decide(new FailureContext("verification_failed", 3, "")).strategy())
            .isEqualTo(RecoveryStrategy.ROLLBACK);
    }

    @Test
    void llmCallFailedRetriesThenDegrades() {
        assertThat(policy.decide(new FailureContext("llm_call_failed", 2, "")).strategy())
            .isEqualTo(RecoveryStrategy.RETRY);
        assertThat(policy.decide(new FailureContext("llm_call_failed", 3, "")).strategy())
            .isEqualTo(RecoveryStrategy.DEGRADE);
    }

    @Test
    void budgetExhaustedAborts() {
        assertThat(policy.decide(new FailureContext("budget_exhausted", 1, "")).strategy())
            .isEqualTo(RecoveryStrategy.ABORT);
    }

    @Test
    void unknownFailureAborts() {
        var d = policy.decide(new FailureContext("something_weird", 1, ""));
        assertThat(d.strategy()).isEqualTo(RecoveryStrategy.ABORT);
        assertThat(d.note()).contains("something_weird");
    }

    @Test
    void attemptAtMaxRetriesStillRetries() {
        // exhausted = attempt > maxRetries, so attempt == maxRetries is NOT exhausted
        assertThat(policy.decide(new FailureContext("invalid_output", 2, "")).strategy())
            .isEqualTo(RecoveryStrategy.RETRY);
    }

    @Test
    void negativeMaxRetriesRejected() {
        assertThatThrownBy(() -> new RecoveryPolicy(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
