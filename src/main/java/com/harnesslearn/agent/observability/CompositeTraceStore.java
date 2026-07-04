package com.harnesslearn.agent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * TraceStore 装饰器：先落库（delegate），再把同一步发布到 RunEventBus 供 SSE 实时消费。
 * 两步各自 best-effort——任一失败只 WARN，不影响另一步、不上抛，绝不拖垮 agent 主循环。
 */
public class CompositeTraceStore implements TraceStore {
    private static final Logger log = LoggerFactory.getLogger(CompositeTraceStore.class);
    private final TraceStore delegate;
    private final RunEventBus bus;

    public CompositeTraceStore(TraceStore delegate, RunEventBus bus) {
        this.delegate = delegate;
        this.bus = bus;
    }

    @Override
    public void append(TraceStep step) {
        try {
            delegate.append(step);
        } catch (RuntimeException e) {
            log.warn("trace 落库失败，跳过: runId={}, seq={}", step.runId(), step.seq(), e);
        }
        try {
            bus.publish(step.runId(), step);
        } catch (RuntimeException e) {
            log.warn("trace 发布失败，跳过: runId={}, seq={}", step.runId(), step.seq(), e);
        }
    }

    @Override
    public List<TraceStep> load(String runId) {
        return delegate.load(runId);
    }
}
