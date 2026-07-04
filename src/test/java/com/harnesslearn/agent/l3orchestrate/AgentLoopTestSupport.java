package com.harnesslearn.agent.l3orchestrate;

import com.harnesslearn.agent.observability.TraceStep;
import com.harnesslearn.agent.observability.TraceStore;
import java.util.List;

/** 测试用 no-op TraceStore，供全参 AgentLoop 构造器使用。 */
final class AgentLoopTestSupport {
    static final TraceStore NOOP_TRACE = new TraceStore() {
        @Override public void append(TraceStep s) {}
        @Override public List<TraceStep> load(String runId) { return List.of(); }
    };
    private AgentLoopTestSupport() {}
}
