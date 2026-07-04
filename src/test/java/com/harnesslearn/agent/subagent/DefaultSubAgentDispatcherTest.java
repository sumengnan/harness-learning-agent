package com.harnesslearn.agent.subagent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultSubAgentDispatcherTest {
    static class UpperAgent implements SubAgent<String,String> {
        public String name() { return "upper"; }
        public String run(String in) {
            if (in.equals("boom")) throw new RuntimeException("fail");
            return in.toUpperCase();
        }
    }

    @Test
    void dispatchParallelCollectsResultsAndDegradesOnFailure() {
        var dispatcher = new DefaultSubAgentDispatcher();
        var agent = new UpperAgent();
        List<String> out = dispatcher.dispatchParallel(agent, List.of("a","boom","c"), "FALLBACK");
        assertThat(out).containsExactly("A","FALLBACK","C");   // 失败项降级为 fallback
    }
}
