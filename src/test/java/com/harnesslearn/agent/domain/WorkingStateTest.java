package com.harnesslearn.agent.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorkingStateTest {
    @Test
    void tracksStepsAndBudget() {
        WorkingState s = WorkingState.start("run1", "写一篇综述", 3);
        s.recordStep("检索了官网");
        assertThat(s.stepsUsed()).isEqualTo(1);
        assertThat(s.completedSteps()).containsExactly("检索了官网");
        assertThat(s.budgetRemaining()).isEqualTo(2);
        s.recordStep("a"); s.recordStep("b");
        assertThat(s.budgetExhausted()).isTrue();
    }
}
